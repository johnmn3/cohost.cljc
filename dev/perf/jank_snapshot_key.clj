;; jank-FAST Step 6 stage 2. Decide whether the AOT snapshot under
;; target/jank-snapshot/ is safe to load; rebuild it when it is not.
;;
;; The key is one sha256 over every input of the snapshot: the stock sources
;; (resources/cohost/clojure-1.12.0), cohost's sources (src/cohost), the
;; scripts that make and load it (dev/perf/jank_snapshot_{record,emit,key,
;; ensure,run}.*; not the timing scripts), and
;; `jank print-binary-version`. It is written to target/jank-snapshot/stamp
;; after a build. A missing stamp, a different key, or a missing object is
;; stale: the snapshot is deleted and built again (record, emit, compile)
;; before anything loads it. A stale snapshot is never loaded.
;;
;; This file only defines: dev/perf/jank_snapshot_ensure.clj builds when
;; stale (ensure!); bb boot:jank and bench:jank only ask (fresh-path), and
;; boot core.clj when the answer is nil.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])
(load-file (str (babashka.fs/parent (babashka.fs/absolutize *file*)) "/jank_inputs.clj"))
(load-file (str (babashka.fs/parent (babashka.fs/absolutize *file*)) "/sha256.clj"))

;; X1-7: the jank binary is JANK_BIN when set (default jank on PATH), as for the AOT
;; objects (dev/perf/jank_aot_ensure.clj): a JANK_BIN binary's snapshot lives under its
;; own root (suffix: sha256 of the binary's real path, 12 hex), and its key's version
;; line also carries the binary's real path, size and mtime (print-binary-version does
;; not move when a patch changes the compiler). The default jank keeps its root and key.
(def ^:private snap-bin
  (let [b (System/getenv "JANK_BIN")] (if (str/blank? b) "jank" b)))
(def ^:private snap-bin-path
  (when-not (= "jank" snap-bin) (str (fs/real-path snap-bin))))
(def ^:private root
  (if snap-bin-path
    (str "target/jank-snapshot-"
         (subs (-> (shell {:out :string :in snap-bin-path} "sha256sum") :out str/trim) 0 12))
    "target/jank-snapshot"))
(def ^:private src-dir (str root "/src"))
(def ^:private aot-dir (str root "/aot"))

;; Eve-in-cohost unit 6 step 3: cohost.hosts.jank-host requires Eve's completion (option 3), so every jank step of the
;; snapshot reads Eve's jank realization from EVE_JANK_PATH (module path) and the completion's header dir
;; (CPLUS_INCLUDE_PATH), and the key covers the completion's sources -- as dev/perf/jank_aot_ensure.clj does for the
;; AOT objects. Unset is an error, not a fallback.
(defn- eve-jank-path []
  (let [p (System/getenv "EVE_JANK_PATH")]
    (when (str/blank? p)
      (throw (ex-info "EVE_JANK_PATH is unset: cohost's jank seat needs Eve's completion" {})))
    p))

(defn- eve-completion-dirs []
  (->> (str/split (eve-jank-path) #":")
       (filter #(str/ends-with? % "adapters/completion/jank/src"))))

;; the completion's header dir (eve/eve_obj.hpp): its src root, include for src
(defn- eve-include-dirs []
  (map #(str (subs % 0 (- (count %) 3)) "include") (eve-completion-dirs)))

(defn- eve-env [] {:extra-env {"CPLUS_INCLUDE_PATH" (str/join ":" (eve-include-dirs))}})

(defn- input-files []
  (vec (sort (concat (source-files "resources/cohost/clojure-1.12.0")
                     (source-files "src/cohost")
                     (mapcat source-files (eve-completion-dirs))
                     (mapcat source-files (filter fs/directory? (eve-include-dirs)))
                     (source-files "dev/perf" "jank_{inputs.clj,snapshot_record.jank,snapshot_emit.clj,snapshot_key.clj,snapshot_ensure.clj,snapshot_run.jank}")))))

(defn- jank-version []
  (let [v (str/trim (:out (shell {:out :string} snap-bin "print-binary-version")))]
    (if snap-bin-path
      (str v " " snap-bin-path " " (fs/size snap-bin-path) " " (.toMillis (fs/last-modified-time snap-bin-path)))
      v)))

(defn snapshot-key []
  (let [files (input-files)
        sums (cohost.dev.sha256/sha256sum-listing files)]
    (cohost.dev.sha256/sha256-hex (str sums "jank " (jank-version) "\n"))))

(defn- caches [] (mapv str (fs/glob aot-dir "_cache/*")))

(defn- cache-dir []
  (let [cs (caches)]
    (when (= 1 (count cs)) (first cs))))

(defn- objects-present? [cache]
  (and cache
       (fs/regular-file? (fs/path cache "cohost/core.o"))
       (fs/regular-file? (fs/path cache "cohost/walk.o"))
       (fs/regular-file? (fs/path cache "cohost/spec/alpha.o"))))

(defn- stamp [] (let [p (str root "/stamp")] (when (fs/regular-file? p) (str/trim (slurp p)))))

(defn stale-reason
  "nil when the snapshot is fresh for key k, else why not."
  [k]
  (cond (nil? (stamp)) "no stamp"
        (not= k (stamp)) (str "key " (stamp) " is not " k)
        (not (objects-present? (cache-dir))) "objects missing"
        :else nil))

(defn- module-path [cache] (str cache ":" src-dir ":src:" (eve-jank-path)))

;; The launcher: cohost.cli compiled with the snapshot into one jank program
;; (--runtime dynamic keeps eval, so -e and a project's namespaces work; the
;; static runtime has none). Its entry requires cohost.snapshot, so the ten
;; library modules are linked and registered in the binary: a compiled jank
;; program has no module path at run time, and a library a project requires
;; (cohost.string ...) is otherwise "could not be found". bin/cohost execs it
;; with the config; its objects, in aot/_cache, also serve the run-main path.
(def ^:private launcher-dir (str root "/launcher"))
(def ^:private launcher-bin (str aot-dir "/cohost-jank"))
(def ^:private launcher-entry
  (str "(clojure.core/ns cohost.app-main\n"
       "  (:require [cohost.snapshot] [cohost.cli]))\n"
       ";; a directory the module loader searches from now on: the per-project\n"
       ";; snapshot's (the jank adapter's :add-module-path! calls this; it is\n"
       ";; defined here because jank's static runtime has no add_path to link)\n"
       "(clojure.core/defn add-module-path! [path]\n"
       "  (clojure.core/let [p (clojure.core/str path)]\n"
       "    (clojure.core/-> cpp/jank.runtime.__rt_ctx (.-module_loader) (.add_path (.to_string p)))\n"
       "    nil))\n"
       "(clojure.core/defn -main [& args]\n"
       "  (clojure.core/apply cohost.cli/-main args))\n"))

(defn launcher-path
  "The launcher binary's absolute path when it is built, else nil."
  []
  (when (fs/executable? launcher-bin) (str (fs/absolutize launcher-bin))))

(defn rebuild! [k]
  (let [t0 (System/nanoTime)
        stage1 (load-file "dev/perf/jank_aot_ensure.clj")]
    (fs/delete-tree root)
    (fs/create-dirs aot-dir)
    ;; 1. record: a boot with record mode on, and the twelve libraries
    (shell (eve-env) snap-bin "--module-path" (str stage1 ":src:" (eve-jank-path)) "run" "dev/perf/jank_snapshot_record.jank"
           "--" "--out" (str root "/events.edn"))
    ;; 2. emit one module per namespace
    (shell "bb" "dev/perf/jank_snapshot_emit.clj" "--events" (str root "/events.edn") "--out" src-dir)
    ;; 3. compile them all, with cohost.cli, into the launcher (one -O3 compile; its
    ;;    objects land in aot/_cache); when that fails, the modules alone as before
    (fs/create-dirs (str launcher-dir "/cohost"))
    (spit (str launcher-dir "/cohost/app_main.jank") launcher-entry)
    (let [r (shell (assoc (eve-env) :continue true) snap-bin "-O3" "--module-path" (str launcher-dir ":src:" aot-dir ":" src-dir ":" (eve-jank-path))
                   "compile" "cohost.app-main" "--runtime" "dynamic" "--name" "cohost-jank" "--target-dir" aot-dir)]
      (when-not (and (zero? (:exit r)) (launcher-path))
        (println "SNAPSHOT-LAUNCHER not built (exit" (:exit r) "); compiling the modules alone")
        (fs/delete-if-exists launcher-bin)
        (shell (eve-env) snap-bin "--module-path" (str src-dir ":src:" (eve-jank-path)) "compile-module" "cohost.snapshot"
               "-O3" "--target-dir" aot-dir)))
    (let [cache (cache-dir)]
      (when-not (objects-present? cache)
        (throw (ex-info "snapshot compile finished without its objects" {:caches (caches)})))
      (spit (str root "/stamp") (str k "\n"))
      (println "SNAPSHOT-BUILT" k "in" (quot (- (System/nanoTime) t0) 1000000) "ms")
      (module-path cache))))

(defn fresh-path
  "The module path of the snapshot when it is fresh, else nil (printing
   SNAPSHOT-STALE and why). Never builds."
  []
  (let [k (snapshot-key)]
    (if-let [why (stale-reason k)]
      (do (println "SNAPSHOT-STALE" why "(bb boot:jank-snapshot builds it)") nil)
      (do (println "SNAPSHOT-FRESH" k) (module-path (cache-dir))))))

(defn ensure! []
  (let [k (snapshot-key)]
    (if-let [why (stale-reason k)]
      (do (println "SNAPSHOT-STALE" why)
          (rebuild! k))
      (do (println "SNAPSHOT-FRESH" k)
          (module-path (cache-dir))))))

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

(defn- input-files []
  (vec (sort (concat (source-files "resources/cohost/clojure-1.12.0")
                     (source-files "src/cohost")
                     (source-files "dev/perf" "jank_{inputs.clj,snapshot_record.jank,snapshot_emit.clj,snapshot_key.clj,snapshot_ensure.clj,snapshot_run.jank}")))))

(defn- jank-version []
  (let [v (str/trim (:out (shell {:out :string} snap-bin "print-binary-version")))]
    (if snap-bin-path
      (str v " " snap-bin-path " " (fs/size snap-bin-path) " " (.toMillis (fs/last-modified-time snap-bin-path)))
      v)))

(defn snapshot-key []
  (let [files (input-files)
        sums (:out (shell {:out :string :in (str/join "\n" files)} "xargs" "-d" "\n" "sha256sum"))]
    (-> (shell {:out :string :in (str sums "jank " (jank-version) "\n")} "sha256sum")
        :out str/trim (str/split #"\s+") first)))

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

(defn- module-path [cache] (str cache ":" src-dir ":src"))

(defn rebuild! [k]
  (let [t0 (System/nanoTime)
        stage1 (load-file "dev/perf/jank_aot_ensure.clj")]
    (fs/delete-tree root)
    (fs/create-dirs aot-dir)
    ;; 1. record: a boot with record mode on, and the twelve libraries
    (shell snap-bin "--module-path" (str stage1 ":src") "run" "dev/perf/jank_snapshot_record.jank"
           "--" "--out" (str root "/events.edn"))
    ;; 2. emit one module per namespace
    (shell "bb" "dev/perf/jank_snapshot_emit.clj" "--events" (str root "/events.edn") "--out" src-dir)
    ;; 3. compile them all through the entry module, -O3
    (shell snap-bin "--module-path" (str src-dir ":src") "compile-module" "cohost.snapshot"
           "-O3" "--target-dir" aot-dir)
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

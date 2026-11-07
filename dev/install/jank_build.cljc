;; jank_build.cljc — the bb-free jank snapshot build: dev/perf's
;; jank_aot_ensure.clj + jank_snapshot_key.clj, ported to raw jank so the
;; cohost installer (and the jank cohost tool) can build and check the
;; core snapshot with NO Babashka and NO Clojure CLI present.
;;
;;   jank --module-path dev/install/lib run dev/install/jank_build.cljc -- fresh
;;   jank --module-path dev/install/lib run dev/install/jank_build.cljc -- ensure
;;
;; Run from the repo root (all paths are the repo's, as in the originals).
;; fresh never builds: it prints "STALE <reason>", or "PATH <module-path>"
;; and, when the compiled launcher exists, "LAUNCHER <absolute path>".
;; ensure builds when stale (record -> emit -> compile, as
;; jank_snapshot_key.clj's rebuild! does; its one bb call, the emit, runs
;; under jank itself here, with dev/install/shim's babashka.fs on the
;; module path so dev/perf/jank_snapshot_emit.clj runs unmodified), then
;; prints the same lines. Stamps and keys are byte-identical to the bb
;; originals (dev/install/lib/cohost/install/shim.cljc's sha256 is
;; sha256sum's own output), so a snapshot built by either flow is fresh
;; to the other.
;;
;; JANK_BIN is honored as in the originals (its own cache roots, its
;; path/size/mtime in the version line); the default is the jank that is
;; running this file.
(ns cohost.install.jank-build
  (:require [cohost.install.shim :as shim]
            [clojure.string :as str]))

(def repo (shim/cwd))

(def jank-bin (or (shim/env "JANK_BIN") (shim/self-exe) "jank"))

(def jank-bin-path
  (when (shim/env "JANK_BIN")
    (let [p (shim/capture-trim (str "realpath " (shim/q jank-bin)))]
      (when-not (str/blank? p) p))))

(defn- suffix [] (when jank-bin-path (subs (shim/sha256-hex jank-bin-path) 0 12)))

(def aot-root
  (if jank-bin-path (str "target/jank-aot-" (suffix)) "target/jank-aot"))

(def snap-root
  (if jank-bin-path (str "target/jank-snapshot-" (suffix)) "target/jank-snapshot"))

(defn- jank-version []
  (let [v (str/trim (shim/capture (str (shim/q jank-bin) " print-binary-version")))]
    (if jank-bin-path
      (str v " " jank-bin-path " " (shim/file-size jank-bin-path) " " (shim/file-mtime-ms jank-bin-path))
      v)))

;; ------------------------------------------------------------ inputs
;; dev/perf/jank_inputs.clj, ported: host cache files are not inputs.

(defn host-cache-file? [path]
  (let [parts (str/split (str path) #"/")]
    (or (some #(= "__pycache__" %) parts)
        (str/ends-with? (str path) ".pyc")
        (str/ends-with? (str path) ".lpyc"))))

(defn source-files
  "The input files under dir, sorted; with a name set, only those names
   (the dev/perf glob's brace list, as a set of file names)."
  ([dir] (source-files dir nil))
  ([dir names]
   (->> (shim/walk-files dir)
        (remove host-cache-file?)
        (filter (fn [f] (or (nil? names) (contains? names (shim/file-name f)))))
        sort
        vec)))

;; ------------------------------------------------------------ AOT objects
;; dev/perf/jank_aot_ensure.clj, ported.

(defn- aot-source-hash []
  (let [files (source-files "src/cohost")]
    (when (empty? files) (throw (ex-info "no files under src/cohost" {})))
    (shim/sha256-hex (shim/sha256sum-listing files))))

(defn- run-objects []
  (if (shim/directory? aot-root)
    (filter #(str/ends-with? % "/cohost/run.o") (shim/walk-files aot-root))
    []))

(defn- cache-of-run [run-o] (shim/parent (shim/parent run-o)))

(defn- aot-fresh? [cache hash version]
  (and (shim/regular-file? (shim/path cache "cohost/hosts/jank_host.o"))
       (shim/regular-file? (shim/path cache "cohost/shim.o"))
       (let [stamp (shim/path cache "stamp")]
         (and (shim/regular-file? stamp)
              (= [hash version] (str/split-lines (slurp stamp)))))))

(defn- aot-recompile! [hash version]
  (println "AOT-RECOMPILE")
  (when (shim/exists? (shim/path aot-root "_cache"))
    (shim/rm-rf (shim/path aot-root "_cache")))
  (shim/mkdir-p aot-root)
  (let [rc (shim/sh! (str (shim/q jank-bin) " --module-path src compile-module cohost.run -O3 --target-dir " (shim/q aot-root)))]
    (when-not (zero? rc) (throw (ex-info (str "jank compile-module cohost.run failed (" rc ")") {}))))
  (let [runs (run-objects)]
    (when-not (= 1 (count runs))
      (throw (ex-info (str "expected one cohost/run.o, found " (count runs)) {:runs runs})))
    (let [cache (cache-of-run (first runs))]
      (when-not (shim/regular-file? (shim/path cache "cohost/hosts/jank_host.o"))
        (throw (ex-info "compile finished without cohost/hosts/jank_host.o" {:cache cache})))
      (when-not (shim/regular-file? (shim/path cache "cohost/shim.o"))
        (throw (ex-info "compile finished without cohost/shim.o" {:cache cache})))
      (spit (shim/path cache "stamp") (str hash "\n" version "\n"))
      (println "AOT-DIR" cache)
      cache)))

(defn aot-ensure!
  "The cache directory holding a fresh cohost/run.o (recompiling first
   when the stamp does not match), as a repo-relative path."
  []
  (let [hash (aot-source-hash)
        version (jank-version)
        runs (run-objects)]
    (if (= 1 (count runs))
      (let [cache (cache-of-run (first runs))]
        (if (aot-fresh? cache hash version)
          (do (println "AOT-FRESH") (println "AOT-DIR" cache) cache)
          (aot-recompile! hash version)))
      (aot-recompile! hash version))))

;; ------------------------------------------------------------ snapshot
;; dev/perf/jank_snapshot_key.clj, ported.

(def snap-src-dir (str snap-root "/src"))
(def snap-aot-dir (str snap-root "/aot"))
(def launcher-bin (str snap-aot-dir "/cohost-jank"))

(def ^:private perf-names
  #{"jank_inputs.clj" "jank_snapshot_record.jank" "jank_snapshot_emit.clj"
    "jank_snapshot_key.clj" "jank_snapshot_ensure.clj" "jank_snapshot_run.jank"})

(defn- snapshot-key []
  (let [files (vec (concat (source-files "resources/cohost/clojure-1.12.0")
                           (source-files "src/cohost")
                           (source-files "dev/perf" perf-names)))
        sums (shim/sha256sum-listing files)]
    (shim/sha256-hex (str sums "jank " (jank-version) "\n"))))

(defn- snap-caches []
  (let [d (shim/path snap-aot-dir "_cache")]
    (if (shim/directory? d)
      (let [out (shim/capture (str "find " (shim/q d) " -mindepth 1 -maxdepth 1 -type d | LC_ALL=C sort"))]
        (if (str/blank? out) [] (str/split-lines out)))
      [])))

(defn- snap-cache-dir []
  (let [cs (snap-caches)]
    (when (= 1 (count cs)) (first cs))))

(defn- objects-present? [cache]
  (and cache
       (shim/regular-file? (shim/path cache "cohost/core.o"))
       (shim/regular-file? (shim/path cache "cohost/walk.o"))
       (shim/regular-file? (shim/path cache "cohost/spec/alpha.o"))))

(defn- snap-stamp []
  (let [p (str snap-root "/stamp")]
    (when (shim/regular-file? p) (str/trim (slurp p)))))

(defn- stale-reason [k]
  (cond (nil? (snap-stamp)) "no stamp"
        (not= k (snap-stamp)) (str "key " (snap-stamp) " is not " k)
        (not (objects-present? (snap-cache-dir))) "objects missing"
        :else nil))

(defn- module-path [cache] (str cache ":" snap-src-dir ":src"))

(defn- print-fresh [k]
  (println "PATH" (module-path (snap-cache-dir)))
  (when (shim/executable? launcher-bin)
    (println "LAUNCHER" (shim/absolutize launcher-bin))))

(def ^:private launcher-entry
  (str "(clojure.core/ns cohost.app-main\n"
       "  (:require [cohost.snapshot] [cohost.cli]))\n"
       "(clojure.core/defn add-module-path! [path]\n"
       "  (clojure.core/let [p (clojure.core/str path)]\n"
       "    (clojure.core/-> cpp/jank.runtime.__rt_ctx (.-module_loader) (.add_path (.to_string p)))\n"
       "    nil))\n"
       "(clojure.core/defn -main [& args]\n"
       "  (clojure.core/apply cohost.cli/-main args))\n"))

(defn- must-replace
  "str/replace that fails loud when its form is absent — a transform
   that silently stops matching would emit a broken copy downstream."
  [s from to]
  (when-not (str/includes? s from)
    (throw (ex-info (str "emit transform found nothing to replace: "
                         (subs from 0 (min 60 (count from)))) {})))
  (str/replace s from to))

(defn- transform-emit-line
  "T1 by line: swap the one line holding the phel lookbehind literal
   for the same call with a parseable literal."
  [s]
  (let [lines (str/split-lines s)
        idx (first (keep-indexed (fn [i l] (when (str/includes? l "(?<!") i)) lines))]
    (when-not idx (throw (ex-info "emit transform T1: no lookbehind line found" {})))
    (let [body (str/join "\n" (assoc lines idx "         (str/replace text #\"\\\\\\$\" \"$1\\\\$\")"))]
      (if (str/ends-with? s "\n") (str body "\n") body))))

(defn- rebuild! [k]
  (let [stage1 (aot-ensure!)]
    (shim/rm-rf snap-root)
    (shim/mkdir-p snap-aot-dir)
    ;; 1. record: a boot with record mode on, and the twelve libraries
    (let [rc (shim/sh! (str (shim/q jank-bin) " --module-path " (shim/q (str stage1 ":src"))
                            " run dev/perf/jank_snapshot_record.jank -- --out " (shim/q (str snap-root "/events.edn"))))]
      (when-not (zero? rc) (throw (ex-info (str "snapshot record failed (" rc ")") {}))))
    ;; 2. emit one module per namespace — the bb original's single bb
    ;;    call, here under jank with the babashka.fs shim on its path.
    ;;    Jank cannot parse the one Java-lookbehind regex literal in
    ;;    emit (line 63, on the phel-only path the jank build never
    ;;    takes), so the jank run uses a regenerated copy with that one
    ;;    literal swapped for a parseable one; nothing else differs.
    (shim/mkdir-p "target/install")
    (spit "target/install/jank_snapshot_emit.clj"
          (-> (slurp "dev/perf/jank_snapshot_emit.clj")
              ;; T1: the phel-only lookbehind literal (never evaluated
              ;;     on the jank path) for a parseable one — replaced by
              ;;     line, because the literal's backslash pile defies
              ;;     exact string matching
              (transform-emit-line)
              ;; (the remaining transforms match exact forms and assert)
              ;; T2: clojure.lang.IObj does not resolve on jank; with-meta
              ;;     throws there on values that cannot carry meta, which
              ;;     is exactly each guard's meaning (three sites)
              (must-replace "(if (instance? clojure.lang.IObj v) (with-meta v m) v)"
                            "(try (with-meta v m) (catch :default _e v))")
              (must-replace "(if (and (instance? clojure.lang.IObj y) (meta x))\n                   (with-meta y (strip (meta x)))\n                   y)"
                            "(if (meta x) (try (with-meta y (strip (meta x))) (catch :default _e y)) y)")
              (must-replace "(if (and (instance? clojure.lang.IObj y) (meta x)) (with-meta y (meta x)) y)"
                            "(if (meta x) (try (with-meta y (meta x)) (catch :default _e y)) y)")
              ;; T3: jank's regex type prints as re_pattern
              (must-replace "(instance? java.util.regex.Pattern x)"
                            "(= \"re_pattern\" (str (type x)))")
              ;; T4: ^String hints do not resolve on jank; hints are
              ;;     inert for the string operations emit performs
              (must-replace "^String " "")
              ;; T5: jank has no *print-length* / *print-level* /
              ;;     *print-namespace-maps* vars, and its printer does
              ;;     not truncate by default, so binding only
              ;;     *print-meta* (which jank has) prints identically
              (must-replace "(binding [*print-length* nil *print-level* nil *print-meta* true *print-namespace-maps* false]"
                            "(binding [*print-meta* true]")
              ;; T6: String.indexOf interop (after T4 stripped its
              ;;     hints); jank's str/index-of has the same 3-arity
              ;;     but answers nil where Java answers -1
              (must-replace "(.indexOf text (first cs) idx)"
                            "(or (str/index-of text (first cs) idx) -1)")))
    (let [rc (shim/sh! (str (shim/q jank-bin) " --module-path dev/install/shim run target/install/jank_snapshot_emit.clj"
                            " -- --events " (shim/q (str snap-root "/events.edn"))
                            " --out " (shim/q snap-src-dir)))]
      (when-not (zero? rc) (throw (ex-info (str "snapshot emit failed (" rc ")") {}))))
    ;; 3. compile the modules with cohost.cli into the launcher
    (shim/mkdir-p (str snap-root "/launcher/cohost"))
    (spit (str snap-root "/launcher/cohost/app_main.jank") launcher-entry)
    (let [rc (shim/sh! (str (shim/q jank-bin) " -O3 --module-path " (shim/q (str snap-root "/launcher:src:" snap-aot-dir ":" snap-src-dir))
                            " compile cohost.app-main --runtime dynamic --name cohost-jank --target-dir " (shim/q snap-aot-dir)))]
      (when-not (and (zero? rc) (shim/executable? launcher-bin))
        (println "SNAPSHOT-LAUNCHER not built (exit" rc "); compiling the modules alone")
        (shim/delete-file launcher-bin)
        (let [rc2 (shim/sh! (str (shim/q jank-bin) " --module-path " (shim/q (str snap-src-dir ":src"))
                                 " compile-module cohost.snapshot -O3 --target-dir " (shim/q snap-aot-dir)))]
          (when-not (zero? rc2) (throw (ex-info (str "snapshot compile-module failed (" rc2 ")") {}))))))
    (let [cache (snap-cache-dir)]
      (when-not (objects-present? cache)
        (throw (ex-info "snapshot compile finished without its objects" {:caches (snap-caches)})))
      (spit (str snap-root "/stamp") (str k "\n"))
      (println "SNAPSHOT-BUILT" k)
      (print-fresh k))))

(defn ensure! []
  (let [k (snapshot-key)]
    (if-let [why (stale-reason k)]
      (do (println "SNAPSHOT-STALE" why)
          (rebuild! k))
      (do (println "SNAPSHOT-FRESH" k)
          (print-fresh k)))))

(defn fresh []
  (let [k (snapshot-key)]
    (if-let [why (stale-reason k)]
      (println "STALE" why)
      (print-fresh k))))

;; ------------------------------------------------------------ main

(let [cmd (first (filter #{"ensure" "fresh"} *command-line-args*))]
  (case cmd
    "ensure" (ensure!)
    "fresh" (fresh)
    (do (binding [*out* *err*]
          (println "usage: jank_build.cljc -- ensure|fresh   (from the repo root)"))
        (throw (ex-info "no command" {})))))

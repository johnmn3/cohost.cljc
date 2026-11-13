;; D-6: Basilisp's cached boot, keyed and stale-checked as jank's snapshot is
;; (dev/perf/jank_snapshot_key.clj): one sha256 over the stock sources,
;; src/cohost, the snapshot scripts (the emitter and these lpy_snapshot_*
;; files) and `basilisp version`, stamped in target/lpy-snapshot/stamp.
;; Stale (no stamp, another key, no core module): deleted, recorded, emitted
;; (jank_snapshot_emit.clj --host lpy), then loaded once, which compiles
;; Basilisp's bytecode cache (__pycache__/*.lpyc) beside the modules.
;; Loading this file defines lpy-fresh-src (the modules' directory when
;; fresh, else nil; never builds) and lpy-ensure! (builds when stale).
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])
(load-file (str (babashka.fs/parent (babashka.fs/absolutize *file*)) "/sha256.clj"))
(load-file (str (babashka.fs/parent (babashka.fs/absolutize *file*)) "/jank_inputs.clj"))

(def ^:private lpy-root "target/lpy-snapshot")
(def ^:private lpy-src (str lpy-root "/src"))

(defn- lpy-key []
  ;; basilisp writes its bytecode cache (__pycache__/, *.lpyc) into src/cohost/ on
  ;; every run: source-files leaves it out, or the key went stale after each run.
  (let [files (sort (concat (source-files "resources/cohost/clojure-1.12.0") (source-files "src/cohost")
                            (source-files "dev/perf" "{jank_snapshot_emit.clj,lpy_snapshot_*}")))
        sums (cohost.dev.sha256/sha256sum-listing files)
        version (str/trim (:out (shell {:out :string} "basilisp" "version")))]
    (cohost.dev.sha256/sha256-hex (str sums "basilisp " version "\n"))))

(defn- lpy-core-cache
  "The compiled bytecode cache of the snapshot's core module, or nil.
   The snapshot's boot loads the modules through Basilisp's importer,
   which looks for exactly this cache; a snapshot without it is not
   fresh however good its stamp looks (a build under
   PYTHONDONTWRITEBYTECODE produces one -- see lpy-ensure!)."
  []
  (first (fs/glob lpy-src "cohost/__pycache__/core.*.lpyc")))

(defn- lpy-stale-reason [k]
  (let [stamp (str lpy-root "/stamp")]
    (cond (not (fs/exists? stamp)) "no stamp"
          (not= k (str/trim (slurp stamp))) "another key"
          (not (fs/exists? (str lpy-src "/cohost/core.lpy"))) "no core module"
          (not (lpy-core-cache)) "no core cache"
          :else nil)))

(defn lpy-fresh-src []
  (let [why (lpy-stale-reason (lpy-key))]
    (if why
      (do (println "LPY-SNAPSHOT-STALE" why "(bb boot:lpy-snapshot builds it)") nil)
      (do (println "LPY-SNAPSHOT-FRESH") lpy-src))))

(defn lpy-ensure! []
  (let [k (lpy-key)]
    (if-let [why (lpy-stale-reason k)]
      (let [t0 (System/nanoTime) events (str lpy-root "/events.edn")]
        (println "LPY-SNAPSHOT-STALE" why)
        (fs/delete-tree lpy-root)
        (fs/create-dirs lpy-root)
        (shell "basilisp" "run" "-p" "src" "dev/perf/lpy_snapshot_record.lpy" events)
        (shell "bb" "dev/perf/jank_snapshot_emit.clj" "--host" "lpy" "--events" events "--out" lpy-src)
        ;; one load: Basilisp compiles each module's bytecode cache.
        ;; The load's whole product is the caches, so it runs with
        ;; PYTHONDONTWRITEBYTECODE stripped from its environment --
        ;; inherited, it suppresses every cache write, and the build
        ;; would stamp a snapshot whose boot dies in the importer on
        ;; the missing files. The stamp is written only once the core
        ;; cache is on disk, for the same reason.
        (shell "env" "-u" "PYTHONDONTWRITEBYTECODE"
               "basilisp" "run" "-p" lpy-src "-p" "src" "-c" "(require 'cohost.hosts.basilisp-prime) (require 'cohost.run) (require 'cohost.core)")
        (when-not (lpy-core-cache)
          (throw (ex-info (str "lpy snapshot build produced no bytecode cache at "
                               lpy-src "/cohost/__pycache__ -- not stamping it fresh")
                          {:snapshot lpy-src})))
        (spit (str lpy-root "/stamp") (str k "\n"))
        (println "LPY-SNAPSHOT-BUILT" k "in" (quot (- (System/nanoTime) t0) 1000000) "ms")
        lpy-src)
      (do (println "LPY-SNAPSHOT-FRESH") lpy-src))))

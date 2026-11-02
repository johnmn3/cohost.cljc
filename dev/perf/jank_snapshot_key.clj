;; jank-FAST Step 6 stage 2. Decide whether the AOT snapshot under
;; target/jank-snapshot/ is safe to load; rebuild it when it is not.
;;
;; The key is one sha256 over every input of the snapshot: the stock sources
;; (resources/cohost/clojure-1.12.0), cohost's sources (src/cohost), the
;; scripts that make and load it (dev/perf/jank_snapshot_{record,emit,key,
;; ensure,run}.*; not the timing scripts), and
;; `jank print-binary-version`. It is written to target/jank-snapshot/stamp
;; after a build. A missing stamp, a different key, or a missing object is
;; stale: the stamp is deleted and the snapshot built again (record, emit,
;; compile) before anything loads it. A stale snapshot is never loaded. The
;; build is incremental (D-9, rebuild! below): the last build's objects are
;; kept when this jank binary made them, and only the stale modules recompile.
;;
;; This file only defines: dev/perf/jank_snapshot_ensure.clj builds when
;; stale (ensure!); bb boot:jank and bench:jank only ask (fresh-path), and
;; boot core.clj when the answer is nil.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(def ^:private root "target/jank-snapshot")
(def ^:private src-dir (str root "/src"))
(def ^:private aot-dir (str root "/aot"))

(defn- input-files []
  (->> (concat (fs/glob "resources/cohost/clojure-1.12.0" "**")
               (fs/glob "src/cohost" "**")
               (fs/glob "dev/perf" "jank_snapshot_{record.jank,emit.clj,key.clj,ensure.clj,run.jank}"))
       (filter fs/regular-file?)
       (map str)
       sort
       vec))

(defn- jank-version []
  (str/trim (:out (shell {:out :string} "jank" "print-binary-version"))))

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

(defn- ms-since [t0] (quot (- (System/nanoTime) t0) 1000000))

(defn- jank-version-file [] (str root "/jank-version"))

(defn rebuild!
  "Build the snapshot for key k, incrementally (D-9). The objects of the last
   build are kept when they were made by this jank binary; otherwise the whole
   snapshot is deleted first. Then, each step printing its time:
     1. compile-module cohost.run -O3 into the snapshot's cache: jank 0.1
        recompiles a src/cohost module whose source is newer than its object,
        and only those (cohost's sources define no macros: see the emitter);
     2. record a boot from those objects (dev/perf/jank_snapshot_record.jank);
     3. emit: a module file whose text and dependencies are unchanged keeps
        its bytes and mtime (dev/perf/jank_snapshot_emit.clj);
     4. compile-module cohost.snapshot -O3, which recompiles the modules the
        emitter wrote and skips the rest.
   COHOST_JANK_SNAPSHOT=full: delete it all first, every time."
  [k]
  (let [t0 (System/nanoTime)
        version (jank-version)
        full? (or (= "full" (System/getenv "COHOST_JANK_SNAPSHOT"))
                  (not (fs/regular-file? (jank-version-file)))
                  (not= version (str/trim (slurp (jank-version-file))))
                  (> (count (caches)) 1))
        step (fn [nm f] (let [t (System/nanoTime)] (f) (println "SNAPSHOT-STEP" nm (ms-since t) "ms")))]
    (if full?
      (fs/delete-tree root)
      (fs/delete-if-exists (str root "/stamp")))
    (fs/create-dirs aot-dir)
    (spit (jank-version-file) (str version "\n"))
    (step "aot" #(shell "jank" "--module-path" "src" "compile-module" "cohost.run" "-O3" "--target-dir" aot-dir))
    (let [cache (cache-dir)]
      ;; 1. record: a boot with record mode on, and the twelve libraries
      (step "record" #(shell "jank" "--module-path" (str cache ":src") "run" "dev/perf/jank_snapshot_record.jank"))
      ;; 2. emit one module per namespace
      (step "emit" #(shell "bb" "dev/perf/jank_snapshot_emit.clj"))
      ;; 3. compile them all through the entry module, -O3
      (step "compile" #(shell "jank" "--module-path" (str src-dir ":src") "compile-module" "cohost.snapshot"
                              "-O3" "--target-dir" aot-dir))
      (when-not (objects-present? cache)
        (throw (ex-info "snapshot compile finished without its objects" {:caches (caches)})))
      (spit (str root "/stamp") (str k "\n"))
      (println "SNAPSHOT-BUILT" k "in" (ms-since t0) "ms" (if full? "(full)" "(incremental)"))
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

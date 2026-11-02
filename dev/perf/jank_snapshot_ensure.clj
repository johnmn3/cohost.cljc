;; jank-FAST Step 6 stage 2. Decide whether the AOT snapshot under
;; target/jank-snapshot/ is safe to load; rebuild it when it is not.
;;
;; The key is one sha256 over every input of the snapshot: the stock sources
;; (resources/cohost/clojure-1.12.0), cohost's sources (src/cohost), the
;; snapshot's own scripts (dev/perf/jank_snapshot_*), and
;; `jank print-binary-version`. It is written to target/jank-snapshot/stamp
;; after a build. A missing stamp, a different key, or a missing object is
;; stale: the snapshot is deleted and built again (record, emit, compile)
;; before anything loads it. A stale snapshot is never loaded.
;;
;; Loading this file returns the module path a snapshot boot runs with (the
;; value of the trailing ensure! call):
;;   bb -e '(println (load-file "dev/perf/jank_snapshot_ensure.clj"))'
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(def ^:private root "target/jank-snapshot")
(def ^:private src-dir (str root "/src"))
(def ^:private aot-dir (str root "/aot"))

(defn- input-files []
  (->> (concat (fs/glob "resources/cohost/clojure-1.12.0" "**")
               (fs/glob "src/cohost" "**")
               (fs/glob "dev/perf" "jank_snapshot_*"))
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

(defn rebuild! [k]
  (let [t0 (System/nanoTime)
        stage1 (load-file "dev/perf/jank_aot_ensure.clj")]
    (fs/delete-tree root)
    (fs/create-dirs aot-dir)
    ;; 1. record: a boot with record mode on, and the twelve libraries
    (shell "jank" "--module-path" (str stage1 ":src") "run" "dev/perf/jank_snapshot_record.jank")
    ;; 2. emit one module per namespace
    (shell "bb" "dev/perf/jank_snapshot_emit.clj")
    ;; 3. compile them all through the entry module, -O3
    (shell "jank" "--module-path" (str src-dir ":src") "compile-module" "cohost.snapshot"
           "-O3" "--target-dir" aot-dir)
    (let [cache (cache-dir)]
      (when-not (objects-present? cache)
        (throw (ex-info "snapshot compile finished without its objects" {:caches (caches)})))
      (spit (str root "/stamp") (str k "\n"))
      (println "SNAPSHOT-BUILT" k "in" (quot (- (System/nanoTime) t0) 1000000) "ms")
      (module-path cache))))

(defn ensure! []
  (let [k (snapshot-key)]
    (if-let [why (stale-reason k)]
      (do (println "SNAPSHOT-STALE" why)
          (rebuild! k))
      (do (println "SNAPSHOT-FRESH" k)
          (module-path (cache-dir))))))

(ensure!)

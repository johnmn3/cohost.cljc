;; jank-FAST Step 6. Decide whether cohost's AOT objects are safe to load.
;;
;; The stamp beside the objects is two lines: a hash of every regular file
;; under src/cohost (path and bytes), and `jank print-binary-version`. A
;; missing object, a missing stamp, or a mismatch deletes the cache and
;; recompiles cohost.run at -O3 before anything loads those objects.
;;
;; Loading this file recompiles when the stamp is stale and returns the
;; cache directory (the value of the trailing ensure! call).
;;   bb -e '(println (load-file "dev/perf/jank_aot_ensure.clj"))'
;;
;; JANK_BIN=<path> picks the jank binary (default: jank on PATH). A JANK_BIN
;; binary gets its own cache root, target/jank-aot-<sha256 of its real path,
;; 12 hex>, so runs that alternate the apt jank and a source build never load
;; each other's objects, and its stamp's version line also carries the binary's
;; real path, size and mtime: print-binary-version does not change when a
;; patch changes the compiler, a rebuild does change those.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])
(load-file (str (babashka.fs/parent (babashka.fs/absolutize *file*)) "/jank_inputs.clj"))

(def jank-bin
  (let [b (System/getenv "JANK_BIN")]
    (if (str/blank? b) "jank" b)))

(defn- jank-bin-path []
  (when-not (= "jank" jank-bin)
    (str (fs/real-path jank-bin))))

(def ^:private aot-root
  (if-let [p (jank-bin-path)]
    (str "target/jank-aot-"
         (subs (-> (shell {:out :string :in p} "sha256sum") :out str/trim) 0 12))
    "target/jank-aot"))

(defn- cohost-files [] (source-files "src/cohost"))

(defn source-hash []
  (let [files (cohost-files)
        _ (when (empty? files) (throw (ex-info "no files under src/cohost" {})))
        sums (:out (shell {:out :string :in (str/join "\n" files)}
                          "xargs" "-d" "\n" "sha256sum"))]
    (-> (shell {:out :string :in sums} "sha256sum")
        :out
        str/trim
        (str/split #"\s+")
        first)))

(defn jank-version []
  (let [v (str/trim (:out (shell {:out :string} jank-bin "print-binary-version")))]
    (if-let [p (jank-bin-path)]
      (str v " " p " " (fs/size p) " " (.toMillis (fs/last-modified-time p)))
      v)))

(defn- run-objects []
  (mapv str (fs/glob aot-root "**/cohost/run.o")))

(defn- cache-of-run [run-o]
  (str (fs/parent (fs/parent run-o))))

(defn- stamp-file [cache]
  (str (fs/path cache "stamp")))

(defn- read-stamp [cache]
  (let [p (stamp-file cache)]
    (when (fs/regular-file? p)
      (str/split-lines (slurp p)))))

(defn- host-object? [cache]
  (fs/regular-file? (fs/path cache "cohost/hosts/jank_host.o")))

(defn- shim-object? [cache]
  (fs/regular-file? (fs/path cache "cohost/shim.o")))

(defn- fresh? [cache hash version]
  (and (host-object? cache)
       (shim-object? cache)
       (= [hash version] (read-stamp cache))))

(defn- recompile! [hash version]
  (println "AOT-RECOMPILE")
  (when (fs/exists? (fs/path aot-root "_cache"))
    (fs/delete-tree (fs/path aot-root "_cache")))
  (fs/create-dirs aot-root)
  (shell jank-bin "--module-path" "src" "compile-module" "cohost.run"
         "-O3" "--target-dir" aot-root)
  (let [runs (run-objects)]
    (when-not (= 1 (count runs))
      (throw (ex-info (str "expected one cohost/run.o, found " (count runs))
                      {:runs runs})))
    (let [cache (cache-of-run (first runs))]
      (when-not (host-object? cache)
        (throw (ex-info "compile finished without cohost/hosts/jank_host.o" {:cache cache})))
      (when-not (shim-object? cache)
        (throw (ex-info "compile finished without cohost/shim.o" {:cache cache})))
      (spit (stamp-file cache) (str hash "\n" version "\n"))
      (println "AOT-DIR" cache)
      cache)))

(defn ensure!
  "Return the cache directory that holds a fresh cohost/run.o. Recompiles first
   when the stamp does not match the sources and the jank binary."
  []
  (let [hash (source-hash)
        version (jank-version)
        runs (run-objects)]
    (if (= 1 (count runs))
      (let [cache (cache-of-run (first runs))]
        (if (fresh? cache hash version)
          (do (println "AOT-FRESH")
              (println "AOT-DIR" cache)
              cache)
          (recompile! hash version)))
      (recompile! hash version))))

(ensure!)

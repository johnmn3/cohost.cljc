;; A deployable native executable of a cohost app on jank (D-7): the booted
;; core's snapshot plus the app's namespaces, compiled -O3 and linked by
;; `jank compile` into one file whose entry is the app's -main.
;;
;; Plain Clojure on the JVM (and babashka): the jank task of lane C's
;; cohost.build (tools.build, `cohost -T:build jank-exe`) calls build!; so does
;; `bb build:jank` in this repo. The steps, each in the cohost checkout:
;;   1. cohost's own namespaces as -O3 AOT objects (dev/perf/jank_aot_ensure.clj);
;;   2. record a boot with the project's source roots, the twelve libraries and
;;      the app's main namespace (dev/perf/jank_snapshot_record.jank);
;;   3. one module per booted namespace plus cohost.app-main, whose -main boots
;;      from the snapshot and calls the app's (dev/perf/jank_snapshot_emit.clj);
;;   4. jank -O3 compile cohost.app-main --runtime static: one executable.
;; The inputs (the stock sources, src/cohost, the snapshot scripts, the jank
;; binary version, every file under the app's roots, its main and name) are
;; hashed into a key stamped beside the executable: a fresh one is not built
;; again, a stale one always is.
;;
;; The executable reads nothing from the checkout or the project when it runs:
;; jank's static runtime links every module it was compiled with. It cannot
;; eval or load code that was not in the build (no JIT in the static runtime).
(ns cohost.build-jank
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn- log [& xs] (binding [*out* *err*] (println (str/join " " xs))))

(defn- sh!
  "Run cmd in dir with extra env; -> its stdout. Throws with its output when
   it fails."
  [dir env & cmd]
  (let [r (apply sh/sh (concat cmd [:dir (str dir) :env (merge (into {} (System/getenv)) env)]))]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "cohost.build-jank: failed (exit " (:exit r) "): " (str/join " " cmd)
                           "\n" (:out r) "\n" (:err r))
                      {:cmd cmd :exit (:exit r)})))
    (:out r)))

(defn- abs [& parts] (.getCanonicalPath (apply io/file parts)))

(defn- host-cache-file?
  "A file a host writes beside its sources (Basilisp's __pycache__/, *.pyc,
   *.lpyc): not an input, as in dev/perf/jank_inputs.clj -- keyed in, any
   basilisp run made the executable stale."
  [^String path]
  (or (some #(= "__pycache__" %) (str/split path #"/"))
      (str/ends-with? path ".pyc")
      (str/ends-with? path ".lpyc")))

(defn- files-under [dir]
  (let [f (io/file dir)]
    (if (.exists f)
      (sort (remove host-cache-file? (map #(.getPath %) (filter #(.isFile %) (file-seq f)))))
      [])))

(defn- sha256 [^String s]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")]
    (.update d (.getBytes s "UTF-8"))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d)))))

(defn- file-sha [path]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")
        b (java.nio.file.Files/readAllBytes (.toPath (io/file path)))]
    (.update d b)
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d)))))

(def ^:private snapshot-scripts
  ["dev/perf/jank_snapshot_record.jank" "dev/perf/jank_snapshot_emit.clj"
   "dev/perf/jank_snapshot_key.clj" "dev/perf/jank_snapshot_ensure.clj"
   "dev/perf/jank_snapshot_run.jank" "dev/perf/jank_aot_ensure.clj"
   "src/cohost/build_jank.clj"])

(defn input-key
  "One sha256 over every input of the executable."
  [{:keys [repo roots main name]}]
  (let [repo-files (concat (files-under (abs repo "resources/cohost/clojure-1.12.0"))
                           (files-under (abs repo "src/cohost"))
                           (map #(abs repo %) snapshot-scripts))
        root-files (mapcat files-under roots)
        version (str/trim (sh! repo {} "jank" "print-binary-version"))]
    (sha256 (str/join "\n" (concat (map #(str % " " (file-sha %)) (concat repo-files root-files))
                                   [(str "jank " version) (str "main " main) (str "name " name)])))))

(defn build!
  "Build (when stale) the executable of the app whose -main is in main.
   opts: :repo (the cohost checkout), :project (the app's directory), :roots
   (its source roots, absolute: deps.edn's :paths, :local/root directories,
   extracted jars, as bin/cohost -Spath prints them), :main (the namespace),
   :name (the executable's file name, default the last segment of main),
   :target (default <project>/target/jank). -> the executable's path."
  [{:keys [repo project roots main name target] :as opts}]
  (let [name (or name (last (str/split (str main) #"\.")))
        target (abs (or target (str project "/target/jank")))
        work (abs target "cohost-build")
        exe (abs target name)
        k (input-key (assoc opts :name name))
        stamp (io/file work "stamp")
        gc {"GC_INITIAL_HEAP_SIZE" "512M"}]
    (if (and (.exists (io/file exe)) (.exists stamp) (= k (str/trim (slurp stamp))))
      (do (log "JANK-EXE-FRESH" exe) exe)
      (let [t0 (System/nanoTime)
            events (abs work "events.edn")
            src (abs work "src")]
        (log "JANK-EXE-BUILD" exe (if (.exists stamp) "(stale)" "(new)"))
        (.mkdirs (io/file work))
        (io/delete-file stamp true)
        ;; 1. cohost's namespaces, -O3 AOT objects (recompiled when stale)
        (let [aot-out (sh! repo {} "bb" "-e" "(println (str \"AOT \" (load-file \"dev/perf/jank_aot_ensure.clj\")))")
              aot (abs repo (second (re-find #"(?m)^AOT (.*)$" aot-out)))]
          ;; 2. record the boot, the libraries and the app
          (let [out (apply sh! repo gc "jank" "--module-path" (str aot ":" (abs repo "src")) "run"
                           (abs repo "dev/perf/jank_snapshot_record.jank") "--" "--out" events "--lib" (str main)
                           (mapcat (fn [r] ["--root" r]) roots))]
            (log (last (filter #(str/starts-with? % "SNAPSHOT-EVENTS") (str/split-lines out))))
            (doseq [l (filter #(str/starts-with? % "RECORD-LIB-ERROR") (str/split-lines out))] (log l))))
        ;; 3. the modules and the entry
        (log (last (str/split-lines (sh! repo {} "bb" (abs repo "dev/perf/jank_snapshot_emit.clj")
                                          "--events" events "--out" src "--main" (str main)))))
        ;; 4. one executable
        (sh! repo gc "jank" "-O3" "--module-path" (str src ":" (abs repo "src")) "compile" "cohost.app-main"
              "--runtime" "static" "--name" name "--target-dir" target)
        (when-not (.exists (io/file exe))
          (throw (ex-info (str "cohost.build-jank: jank compile wrote no " exe) {})))
        (spit stamp (str k "\n"))
        (log "JANK-EXE-BUILT" exe "in" (quot (- (System/nanoTime) t0) 1000000) "ms")
        exe))))

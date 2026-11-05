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
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
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

(defn- jank-bin
  "The jank binary every step runs: JANK_BIN when set (as the AOT objects of
   step 1 are built with it, dev/perf/jank_aot_ensure.clj), else jank on PATH.
   One binary for every step: objects from one jank do not load in another."
  []
  (let [b (System/getenv "JANK_BIN")] (if (str/blank? b) "jank" b)))

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

(defn- project-cpp
  "The project's :cpp key in its deps.edn -- the build key for an app whose
   C++ half needs more than the cpp/raw blocks in its sources:
     :cpp {:include-dirs [\"cpp/vendor/uWebSockets/src\" ...]
           :sources      [\"cpp/vendor/uSockets/src/bsd.c\" ...]
           :defines      [\"LIBUS_USE_OPENSSL\" ...]
           :libs         [\"z\" ...]}
   Paths are relative to the project. -> {:dirs [abs ...] :sources [abs ...]
   :defines [name ...] :libs [name ...]}, or nil when the project has no
   :cpp key."
  [project]
  (let [f (io/file project "deps.edn")]
    (when (.exists f)
      (when-let [cpp (:cpp (edn/read-string {:default tagged-literal} (slurp f)))]
        {:dirs (mapv #(abs project %) (:include-dirs cpp))
         :sources (mapv #(abs project %) (:sources cpp))
         :defines (vec (:defines cpp))
         :libs (vec (:libs cpp))}))))

(defn input-key
  "One sha256 over every input of the executable."
  [{:keys [repo roots main name project]}]
  (let [repo-files (concat (files-under (abs repo "resources/cohost/clojure-1.12.0"))
                           (files-under (abs repo "src/cohost"))
                           (map #(abs repo %) snapshot-scripts))
        root-files (mapcat files-under roots)
        cpp (when project (project-cpp project))
        cpp-files (when cpp (concat (:sources cpp) (mapcat files-under (:dirs cpp))))
        version (str/trim (sh! repo {} (jank-bin) "print-binary-version"))]
    (sha256 (str/join "\n" (concat (map #(str % " " (file-sha %)) (concat repo-files root-files cpp-files))
                                   [(str "jank " version) (str "main " main) (str "name " name)
                                    (when cpp (str "cpp " cpp))])))))

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
            src (abs work "src")
            cpp (project-cpp project)
            ;; the record step is a JIT session: the app's cpp/raw blocks
            ;; compile there, so their headers must be findable -- clang
            ;; honors CPLUS_INCLUDE_PATH for the C++ translation units
            record-env (if cpp
                         (assoc gc "CPLUS_INCLUDE_PATH"
                                (str/join ":" (concat (:dirs cpp)
                                                      (when-let [e (System/getenv "CPLUS_INCLUDE_PATH")] [e]))))
                         gc)]
        (log "JANK-EXE-BUILD" exe (if (.exists stamp) "(stale)" "(new)"))
        (.mkdirs (io/file work))
        (io/delete-file stamp true)
        ;; 0.5 the project's :cpp sources, compiled once (-fPIC) BEFORE the
        ;; record step: the static archive serves the final link (step 4);
        ;; a shared twin in record-lib/ serves the record session (step 2),
        ;; whose JIT materializes a cpp/raw block's translation unit when
        ;; the form evaluates -- the block's external symbols must resolve
        ;; inside that process (jank -L/-l), or the raw records as an error
        ;; and is dropped. record-lib/ is a subdirectory so step 4's
        ;; -L work -l cpp still picks the archive, never the shared lib.
        (when cpp
          (let [objs (mapv (fn [i s]
                             (let [o (abs work (str "cpp" i ".o"))
                                   cc (if (str/ends-with? s ".c")
                                        (or (System/getenv "CC") "cc")
                                        (or (System/getenv "CXX") "c++"))]
                               (apply sh! work {} cc "-O3" "-fPIC"
                                      (concat (mapcat (fn [d] ["-I" d]) (:dirs cpp))
                                              (mapcat (fn [d] [(str "-D" d)]) (:defines cpp))
                                              (when-not (str/ends-with? s ".c") ["-std=c++17"])
                                              ["-c" s "-o" o]))
                               o))
                           (range) (:sources cpp))
                libdir (abs work "record-lib")]
            (.mkdirs (io/file libdir))
            (apply sh! work {} "ar" "rcs" (abs work "libcpp.a") objs)
            (apply sh! work {} (or (System/getenv "CXX") "c++") "-shared" "-o" (abs libdir "libcpp.so") objs)
            (log "JANK-EXE-CPP" (count objs) "objects (archive + record shared lib)")))
        ;; 1. cohost's namespaces, -O3 AOT objects (recompiled when stale)
        (let [aot-out (sh! repo {} "bb" "-e" "(println (str \"AOT \" (load-file \"dev/perf/jank_aot_ensure.clj\")))")
              aot (abs repo (second (re-find #"(?m)^AOT (.*)$" aot-out)))]
          ;; 2. record the boot, the libraries and the app
          (let [out (apply sh! repo record-env (jank-bin)
                           (concat (when cpp ["-L" (abs work "record-lib") "-l" "cpp"])
                                   ["--module-path" (str aot ":" (abs repo "src")) "run"
                                    (abs repo "dev/perf/jank_snapshot_record.jank") "--" "--out" events "--lib" (str main)]
                                   (mapcat (fn [r] ["--root" r]) roots)))]
            (log (last (filter #(str/starts-with? % "SNAPSHOT-EVENTS") (str/split-lines out))))
            (doseq [l (filter #(str/starts-with? % "RECORD-LIB-ERROR") (str/split-lines out))] (log l))))
        ;; 3. the modules and the entry
        (log (last (str/split-lines (sh! repo {} "bb" (abs repo "dev/perf/jank_snapshot_emit.clj")
                                          "--events" events "--out" src "--main" (str main)))))
        ;; 3.5 the project's :cpp link flags (the sources were compiled at
        ;; step 0.5, before the record step that needs their symbols)
        (let [cpp-flags (when cpp
                          (concat (mapcat (fn [d] ["-I" d]) (:dirs cpp))
                                  ;; the archive (work) first for the static
                                  ;; link; record-lib's shared twin also on
                                  ;; the search path because step 4's compile
                                  ;; JIT-evaluates the app module and must
                                  ;; materialize the cpp/raw symbols in its
                                  ;; own process, where only a shared lib
                                  ;; can be loaded
                                  ["-L" work "-L" (abs work "record-lib") "-l" "cpp"]
                                  (mapcat (fn [l] ["-l" l]) (:libs cpp))))]
          ;; 4. one executable
          (apply sh! repo gc (jank-bin) "-O3" "--module-path" (str src ":" (abs repo "src")) "compile" "cohost.app-main"
                 "--runtime" "static" "--name" name "--target-dir" target
                 (or cpp-flags [])))
        (when-not (.exists (io/file exe))
          (throw (ex-info (str "cohost.build-jank: jank compile wrote no " exe) {})))
        (spit stamp (str k "\n"))
        (log "JANK-EXE-BUILT" exe "in" (quot (- (System/nanoTime) t0) 1000000) "ms")
        exe))))

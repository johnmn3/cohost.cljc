;; D-10 on Basilisp: how bin/cohost starts cohost.cli on basilisp with compiled
;; namespaces. Loading this file defines lpy-cli-launch, lpy-project-launch
;; and lpy-prepare!, the Basilisp twins of dev/perf/jank_snapshot_cli.clj's
;; (whose key and namespace finder they share).
;;
;; A compiled namespace on Basilisp is a generated .lpy module (the emitter,
;; dev/perf/jank_snapshot_emit.clj --host lpy) that Python compiles once and
;; caches as bytecode (__pycache__/*.lpyc beside it); loading it replays the
;; namespace's recorded load. The core and the twelve libraries are D-6's
;; cached boot (target/lpy-snapshot/src, dev/perf/lpy_snapshot_ensure.clj);
;; a project's own namespaces go under <project>/target/cohost-lpy/src, keyed
;; on the core's stamp and every file under the project's roots.
;;
;;   (lpy-cli-launch repo)        -> {:paths [<core snapshot src>] :snapshot? true}
;;                                   or {:paths [] :snapshot? false} (stale: boot!,
;;                                   and the core snapshot rebuilds in the background)
;;   (lpy-project-launch ...)     -> {:paths [<project src>] :project-snapshot "cohost.project"}
;;                                   or {:record-libs <events> :after-run <fn>}
;;   (lpy-prepare! repo project roots) -> exit code (cohost :lpy -P)
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])
(load-file (str (babashka.fs/parent (babashka.fs/absolutize *file*)) "/jank_snapshot_cli.clj"))

(defn- lpy-core-src
  "The core snapshot's module directory when it is fresh, else nil (one stderr
   line). Decided in the repo, in a child bb: the CLI's stdout stays the
   program's."
  [repo]
  (let [r (p/shell {:dir repo :out :string :err :string :continue true}
                   "bb" "-e" "(load-file \"dev/perf/lpy_snapshot_ensure.clj\") (println \"LPY-SRC\" (or ((resolve 'lpy-fresh-src)) \"\"))")
        line (last (filter #(str/starts-with? % "LPY-SRC") (str/split-lines (:out r))))
        src (when line (str/trim (subs line 7)))]
    (when (seq src) (str (fs/absolutize (fs/path repo src))))))

(defn- start-background!
  "Run bb code in repo, nice'd, one at a time (lock holds the pid), output to
   log. -> true when started."
  [repo lock log code]
  (let [held (when (fs/exists? lock) (str/trim (slurp lock)))]
    (when-not (and (seq held) (pid-alive? held))
      (fs/create-dirs (fs/parent lock))
      (let [proc (p/process {:dir repo :out (java.io.File. log) :err :out :in nil}
                            "nice" "-n" "10" "bb" "-e"
                            (str code "(babashka.fs/delete-if-exists " (pr-str lock) ")"))]
        (spit lock (str (.pid ^java.lang.Process (:proc proc)) "\n"))
        true))))

(defn lpy-cli-launch [repo]
  (if-let [src (lpy-core-src repo)]
    {:paths [src] :snapshot? true}
    (do (binding [*out* *err*]
          (if (= "0" (System/getenv "COHOST_JANK_REBUILD"))
            (println (str "cohost: lpy: this run boots without the cached core; to build it: (cd " repo " && bb boot:lpy-snapshot)"))
            (when (start-background! repo (str repo "/target/lpy-snapshot-rebuild.lock") (str repo "/target/lpy-snapshot-rebuild.log")
                                     "(load-file \"dev/perf/lpy_snapshot_ensure.clj\") ((resolve 'lpy-ensure!))")
              (println (str "cohost: lpy: building the cached core in the background (" repo "/target/lpy-snapshot-rebuild.log); this run boots without it")))))
        {:paths [] :snapshot? false})))

(defn- lpy-core-stamp [repo] (str repo "/target/lpy-snapshot/stamp"))

(defn lpy-project-build!
  "Compile what a run recorded (dir/events.edn): the .lpy modules (an unchanged
   file keeps its mtime, so Python keeps its bytecode), then one basilisp
   process that boots from the core snapshot and loads them all, so Python
   writes their bytecode; then the stamp, last. -> true when built."
  [repo dir core-src k]
  (let [stamp (str dir "/stamp") src (str dir "/src") events (str dir "/events.edn")
        core (when (fs/exists? (lpy-core-stamp repo)) (slurp (lpy-core-stamp repo)))
        run (fn [& cmd] (apply p/shell {:dir repo :out :string :err :string :continue true} cmd))]
    (fs/delete-if-exists stamp)
    (when (not= core (when (fs/exists? (str dir "/core-stamp")) (slurp (str dir "/core-stamp"))))
      (fs/delete-tree src))
    (let [r1 (run "bb" (str repo "/dev/perf/jank_snapshot_emit.clj") "--project" "--host" "lpy" "--events" events "--out" src)
          r2 (when (zero? (:exit r1))
               (run "basilisp" "run" "-p" core-src "-p" src "-p" (str repo "/src") "-c"
                    (str "(require 'cohost.cli) (cohost.boot/boot-from-snapshot! {}) "
                         "(require 'cohost.project) (require 'cohost.project-build) (println :lpy-project-loaded)")))
          ok (and r2 (zero? (:exit r2)) (str/includes? (:out r2) ":lpy-project-loaded"))]
      (if ok
        (do (when core (spit (str dir "/core-stamp") core))
            (spit stamp (str k "\n"))
            (println "PROJECT-BUILT" k))
        (println "PROJECT-BUILD-FAILED" (:out (or r2 r1)) (:err (or r2 r1))))
      (boolean ok))))

(defn lpy-project-launch
  "As jank-project-launch, on Basilisp (core-src: the fresh core snapshot's
   modules)."
  [repo project roots core-src]
  (when (and core-src (not= "0" (System/getenv "COHOST_COMPILE")))
    (let [dir (str (fs/absolutize (fs/path project "target/cohost-lpy")))
          stamp (str dir "/stamp") lock (str dir "/build.lock")
          k (project-key repo roots nil (lpy-core-stamp repo))
          held (when (fs/exists? lock) (str/trim (slurp lock)))]
      (cond
        (and (fs/exists? stamp) (= k (str/trim (slurp stamp))))
        {:paths [(str dir "/src")] :project-snapshot "cohost.project"}

        (and (seq held) (pid-alive? held))
        (binding [*out* *err*] (println (str "cohost: lpy: the project's namespaces are being compiled (" dir "/build.log); this run loads them from source")) nil)

        :else
        (let [events (str dir "/events.edn")]
          (fs/create-dirs dir)
          (fs/delete-if-exists events)
          {:record-libs events
           :after-run
           (fn []
             (when (and (fs/exists? events)
                        (start-background! repo lock (str dir "/build.log")
                                           (str "(load-file " (pr-str (str repo "/dev/perf/lpy_snapshot_cli.clj")) ")"
                                                "(lpy-project-build! " (pr-str repo) " " (pr-str dir) " " (pr-str core-src) " " (pr-str k) ")")))
               (binding [*out* *err*]
                 (println (str "cohost: lpy: compiling the project's namespaces in the background for the next run (" dir "/build.log)")))))})))))

(defn lpy-prepare!
  "cohost :lpy -P: the cached core when stale (foreground), then the project's
   own namespaces recorded (loaded, never run) and compiled. -> exit code."
  [repo project roots]
  (binding [*out* *err*]
    (let [ens (p/shell {:dir repo :continue true} "bb" "-e" "(load-file \"dev/perf/lpy_snapshot_ensure.clj\") ((resolve 'lpy-ensure!))")
          core-src (when (zero? (:exit ens)) (lpy-core-src repo))]
      (if-not core-src
        (do (println "cohost: lpy: the cached core did not build; -P stops here") 1)
        (let [libs (project-namespaces project roots)
              dir (str (fs/absolutize (fs/path project "target/cohost-lpy")))
              stamp (str dir "/stamp")
              k (project-key repo roots nil (lpy-core-stamp repo))]
          (cond
            (empty? libs) (do (println "cohost: lpy: the cached core is fresh; no project namespaces to compile") 0)
            (and (fs/exists? stamp) (= k (str/trim (slurp stamp))))
            (do (println (str "cohost: lpy: the cached core and the project's " (count libs) " namespaces are compiled")) 0)
            :else
            (let [events (str dir "/events.edn")
                  _ (fs/create-dirs dir)
                  code (str "(require 'cohost.cli) (cohost.boot/boot-from-snapshot! {:root " (pr-str (str repo "/resources/cohost/clojure-1.12.0")) "}) "
                            (apply str (map #(str "(cohost.boot/add-source-root! " (pr-str %) ") ") roots))
                            "(cohost.boot/snapshot-start!) "
                            (apply str (map #(str "(cohost.adapter/try-all (fn [] (cohost.boot/load-lib! '" % ")) (fn [e] (println \"RECORD-LIB-ERROR " % "\" (cohost.adapter/error-message e)))) ") libs))
                            "(cohost.adapter/spit-file " (pr-str events) " (pr-str (cohost.boot/snapshot-stop!)))")
                  r (p/shell {:dir repo :out :string :err :string :continue true}
                             "basilisp" "run" "-p" core-src "-p" (str repo "/src") "-c" code)]
              (doseq [line (str/split-lines (:out r)) :when (str/starts-with? line "RECORD-LIB-ERROR")] (println line))
              (if (and (zero? (:exit r)) (fs/exists? events) (lpy-project-build! repo dir core-src k))
                (do (println (str "cohost: lpy: compiled the project's " (count libs) " namespaces (" dir ")")) 0)
                (do (println (str "cohost: lpy: compiling the project's namespaces failed: " (:err r))) 1)))))))))

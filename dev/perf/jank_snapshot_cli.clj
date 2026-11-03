;; jank-FAST Step 6 stage 2, for the CLI (D-5): how bin/cohost starts cohost.cli
;; on jank. Loading this file defines jank-cli-launch:
;;
;;   (load-file "<repo>/dev/perf/jank_snapshot_cli.clj")
;;   (jank-cli-launch "<repo>")
;;   ;; -> {:module-path "<abs snapshot objects>:<abs snapshot src>:<abs repo>/src"
;;   ;;     :snapshot? true                        ; the snapshot is fresh
;;   ;;     :env {"GC_INITIAL_HEAP_SIZE" "512M"}}  ; the collector's start heap (D-4)
;;   ;;    {:module-path "<abs AOT objects>:<abs repo>/src" :snapshot? false}
;;   ;;    when it is stale or missing (the AOT boot, as bb boot:jank falls back)
;;
;; :env is the jank tasks' GC setting (doc/perf/jank.md "GC"): set for the jank
;; process unless the caller's environment sets that variable, or none at all
;; with COHOST_JANK_GC=none. bin/cohost then runs, in the project directory,
;; with :env added to its environment,
;;   jank -O3 --module-path <module-path> run-main cohost.cli <config>
;; with :snapshot? copied into the config (cohost.cli/-main then calls
;; cohost.boot/boot-from-snapshot! instead of boot!). Neither builds a
;; snapshot (bb boot:jank-snapshot does); the AOT objects are recompiled when
;; stale, as for bb boot:jank. The decision runs in the repo (the key's paths
;; are the repo's), in a child bb whose output is captured: the CLI's own
;; stdout stays the program's. When stale, one line on stderr says why.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])
(load-file (str (babashka.fs/parent (babashka.fs/absolutize *file*)) "/jank_inputs.clj"))

(defn- abs-path [repo path]
  (str/join ":" (map (fn [x] (str (fs/absolutize (fs/path repo x)))) (str/split path #":"))))

;; D-9 step 3: a stale snapshot is rebuilt in the background while this run
;; boots without it (the AOT path); the next run after the rebuild boots from
;; it. The rebuild is incremental (dev/perf/jank_snapshot_key.clj rebuild!),
;; measured 62-155 s for a one-line change (doc/CLI.md), against ~20 s more on
;; every run that takes the AOT path: printing the command instead would leave
;; every run slow until someone runs it. One rebuild at a time: a lock holds
;; its pid (a dead pid's lock is taken over); its output goes to
;; target/jank-snapshot-rebuild.log. A rebuild never makes a half-built
;; snapshot loadable: the stamp is written last. COHOST_JANK_REBUILD=0: no
;; background rebuild, the command is printed instead. The rebuild runs nice'd
;; (the run beside it keeps the CPU it needs).
(defn- pid-alive? [pid] (and pid (fs/exists? (str "/proc/" pid))))

(defn rebuild-in-background!
  "Start the snapshot's rebuild in repo unless one is running; one stderr line
   says which. -> the rebuild's pid, or nil."
  [repo]
  (let [lock (str repo "/target/jank-snapshot-rebuild.lock")
        log (str repo "/target/jank-snapshot-rebuild.log")
        held (when (fs/exists? lock) (str/trim (slurp lock)))]
    (cond
      (= "0" (System/getenv "COHOST_JANK_REBUILD"))
      (do (println (str "cohost: jank: this run boots without the snapshot; to rebuild it: (cd " repo " && bb boot:jank-snapshot)")) nil)

      (and (seq held) (pid-alive? held))
      (do (println (str "cohost: jank: the snapshot is being rebuilt (pid " held ", " log "); this run boots without it")) nil)

      :else
      (let [_ (fs/create-dirs (str repo "/target"))
            proc (p/process {:dir repo :out (java.io.File. log) :err :out :in nil}
                            "nice" "-n" "10" "bb" "-e"
                            (str "(load-file \"dev/perf/jank_snapshot_ensure.clj\")"
                                 "(babashka.fs/delete-if-exists \"target/jank-snapshot-rebuild.lock\")"))
            pid (str (.pid ^java.lang.Process (:proc proc)))]
        (spit lock (str pid "\n"))
        (println (str "cohost: jank: rebuilding the snapshot in the background (pid " pid ", " log
                      "); this run boots without it"))
        pid))))

(defn jank-cli-launch [repo]
  (let [r (p/shell {:dir repo :out :string :err :string :continue true}
                   "bb" "-e"
                   (str "(load-file \"dev/perf/jank_snapshot_key.clj\")"
                        "(if-let [m ((resolve 'fresh-path))]"
                        "  (println \"CLI-PATH snapshot\" m)"
                        "  (println \"CLI-PATH aot\" (str (load-file \"dev/perf/jank_aot_ensure.clj\") \":src\")))"))
        lines (str/split-lines (:out r))
        line (last (filter #(str/starts-with? % "CLI-PATH ") lines))
        stale (first (filter #(str/starts-with? % "SNAPSHOT-STALE") lines))
        [_ kind path] (when line (str/split line #" " 3))]
    (when-not line
      (throw (ex-info (str "jank: no module path from dev/perf/jank_snapshot_key.clj: " (:err r)) {})))
    (when stale (binding [*out* *err*] (println (str "cohost: jank: " stale)) (rebuild-in-background! repo)))
    {:module-path (abs-path repo path) :snapshot? (= "snapshot" kind)
     :env (if (= "none" (System/getenv "COHOST_JANK_GC"))
            {}
            (into {} (remove (fn [[k _]] (System/getenv k)) {"GC_INITIAL_HEAP_SIZE" "512M"})))}))

;; D-8: a project's snapshot. The core's snapshot covers the stock core and
;; the twelve libraries; the project's own namespaces (its main ns and what it
;; requires, through cohost's loader) would be JIT-compiled at -O3 at their
;; first call on every run. jank-project-launch compiles them ahead, once,
;; into <project>/target/cohost-jank/: recorded on top of a snapshot boot
;; (dev/perf/jank_snapshot_project_record.jank), emitted as modules
;; (jank_snapshot_emit.clj --project), compile-module'd -O3 against the core
;; snapshot's objects. Its key: the core snapshot's stamp, every file under
;; the project's roots, the main ns, and this file and the record script; a
;; stale one is rebuilt (one stderr line) before the run, a fresh one is used.
;; COHOST_JANK_PROJECT=none: never. -> {:module-path <project objects>:<its
;; src>:<the core's path> :project-snapshot "cohost.project"}, or nil (no main
;; ns, opted out, or the build failed: the run goes on without it).
(defn- sha256-of [^String s]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")]
    (.update d (.getBytes s "UTF-8"))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d)))))

(defn- project-key [repo roots main]
  (let [files (sort (mapcat source-files roots))
        sums (if (seq files)
               (:out (p/shell {:out :string :in (str/join "\n" files)} "xargs" "-d" "\n" "sha256sum"))
               "")]
    (sha256-of (str (slurp (str repo "/target/jank-snapshot/stamp"))
                    sums
                    (:out (p/shell {:out :string} "sha256sum" (str repo "/dev/perf/jank_snapshot_cli.clj")
                                   (str repo "/dev/perf/jank_snapshot_project_record.jank")
                                   (str repo "/dev/perf/jank_snapshot_emit.clj")))
                    "main " main "\n"))))

(defn jank-project-build!
  "D-10: compile what a run recorded (dir/events.edn) into the project's
   modules: emit (jank_snapshot_emit.clj --project; an unchanged module file
   keeps its mtime, so jank recompiles only what changed, D-9), compile-module
   -O3, then the stamp, last. A different core snapshot (dir/core-stamp)
   deletes the old objects first: their macros came from it. -> true when built."
  [repo dir core-path k env]
  (let [stamp (str dir "/stamp") src (str dir "/src") aot (str dir "/aot") events (str dir "/events.edn")
        core-stamp (str repo "/target/jank-snapshot/stamp")
        core (when (fs/exists? core-stamp) (slurp core-stamp))
        run (fn [& cmd] (apply p/shell {:dir repo :out :string :err :string :continue true :extra-env env} cmd))]
    (fs/delete-if-exists stamp)
    (when (not= core (when (fs/exists? (str dir "/core-stamp")) (slurp (str dir "/core-stamp"))))
      (fs/delete-tree src) (fs/delete-tree aot))
    (fs/create-dirs aot)
    (let [r2 (run "bb" (str repo "/dev/perf/jank_snapshot_emit.clj") "--project" "--events" events "--out" src)
          r3 (when (zero? (:exit r2))
               (run "jank" "-O3" "--module-path" (str src ":" core-path) "compile-module" "cohost.project-build"
                    "--target-dir" aot))
          ok (and r3 (zero? (:exit r3)) (seq (fs/glob aot "_cache/*")))]
      (if ok
        (do (when core (spit (str dir "/core-stamp") core))
            (spit stamp (str k "\n"))
            (println "PROJECT-BUILT" k))
        (println "PROJECT-BUILD-FAILED" (:err (or r3 r2))))
      (boolean ok))))

(defn jank-project-launch
  "D-8, D-10: a project's namespaces, compiled. Fresh (the stamp is this
   key) -> its module path and entry: every project namespace loads from its
   module. Stale -> {:record-libs <events file>}: this run records the
   libraries it loads from source (cohost.cli, D-10), and :after-run starts
   jank-project-build! in the background when the run has ended (one at a
   time, nice'd; <dir>/build.log), so the next run loads them compiled. No
   separate recording pass before the run any more (D-8 took 37 s). While a
   build is running, a run neither records nor waits. COHOST_COMPILE=0 or
   COHOST_JANK_PROJECT=none: never."
  [repo project roots main core-path env]
  (when (and main (not= "none" (System/getenv "COHOST_JANK_PROJECT")) (not= "0" (System/getenv "COHOST_COMPILE")))
    (let [dir (str (fs/absolutize (fs/path project "target/cohost-jank")))
          stamp (str dir "/stamp") src (str dir "/src") aot (str dir "/aot")
          lock (str dir "/build.lock")
          k (project-key repo roots main)
          cache (fn [] (first (map str (fs/glob aot "_cache/*"))))
          held (when (fs/exists? lock) (str/trim (slurp lock)))]
      (cond
        (and (fs/exists? stamp) (= k (str/trim (slurp stamp))) (cache))
        {:module-path (str (cache) ":" src ":" core-path) :project-snapshot "cohost.project"}

        (and (seq held) (pid-alive? held))
        (binding [*out* *err*] (println (str "cohost: jank: the project's namespaces are being compiled (" dir "/build.log); this run loads them from source")) nil)

        :else
        (let [events (str dir "/events.edn")]
          (fs/create-dirs dir)
          (fs/delete-if-exists events)
          {:record-libs events
           :after-run
           (fn []
             (when (fs/exists? events)
               (let [proc (p/process {:dir repo :out (java.io.File. (str dir "/build.log")) :err :out :in nil
                                      :extra-env env}
                                     "nice" "-n" "10" "bb" "-e"
                                     (str "(load-file " (pr-str (str repo "/dev/perf/jank_snapshot_cli.clj")) ")"
                                          "(jank-project-build! " (pr-str repo) " " (pr-str dir) " " (pr-str core-path) " "
                                          (pr-str k) " " (pr-str env) ")"
                                          "(babashka.fs/delete-if-exists " (pr-str lock) ")"))]
                 (spit lock (str (.pid ^java.lang.Process (:proc proc)) "\n"))
                 (binding [*out* *err*]
                   (println (str "cohost: jank: compiling the project's namespaces in the background for the next run ("
                                 dir "/build.log)"))))))})))))
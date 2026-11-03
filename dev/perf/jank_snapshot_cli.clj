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
(load-file (str (babashka.fs/parent (babashka.fs/absolutize *file*)) "/sha256.clj"))
;; jank-MAX A1: the collector environment, one definition for every jank launch
(load-file (str (babashka.fs/parent (babashka.fs/absolutize *file*)) "/jank_gc_env.clj"))

(defn- abs-path [repo path]
  (str/join ":" (map (fn [x] (str (fs/absolutize (fs/path repo x)))) (str/split path #":"))))

(defn- jank-check [repo]
  (let [r (p/shell {:dir repo :out :string :err :string :continue true}
                   "bb" "-e"
                   (str "(load-file \"dev/perf/jank_snapshot_key.clj\")"
                        "(if-let [m ((resolve 'fresh-path))]"
                        "  (println \"CLI-PATH snapshot\" m)"
                        "  (println \"CLI-PATH aot\" (str (load-file \"dev/perf/jank_aot_ensure.clj\") \":src\")))"))
        lines (str/split-lines (:out r))
        line (last (filter #(str/starts-with? % "CLI-PATH ") lines))]
    (when-not line
      (throw (ex-info (str "jank: no module path from dev/perf/jank_snapshot_key.clj: " (:err r)) {})))
    (str/split line #" " 3)))

(defn- jank-build-snapshot!
  "Builds the core snapshot once, on first use (or after a cohost update made it
   stale), with one plain line; its output goes to target/jank-snapshot.log. One
   build at a time: a second cohost command meanwhile runs on the slower path.
   COHOST_JANK_SNAPSHOT=off: never. -> true when it built."
  [repo]
  (let [lock (fs/file repo "target" "jank-snapshot.lock")
        log (fs/file repo "target" "jank-snapshot.log")]
    (fs/create-dirs (fs/parent lock))
    ;; a lock a killed build left behind does not block forever
    (when (and (fs/exists? lock)
               (> (- (System/currentTimeMillis) (.toMillis (fs/last-modified-time lock))) (* 30 60 1000)))
      (fs/delete-tree lock))
    (binding [*out* *err*]
      (cond
        (= "off" (System/getenv "COHOST_JANK_SNAPSHOT")) false
        (not (.mkdir lock))
        (do (println "cohost: jank's core snapshot is being built by another cohost command; this one runs on the slower path") false)
        :else
        (try
          (println "cohost: building jank's core snapshot (once per cohost update: 2 to 11 minutes measured, longer on a busy machine; log: target/jank-snapshot.log) ...")
          (let [t0 (System/nanoTime)
                r (p/shell {:dir repo :out (str log) :err (str log ".err") :continue true}
                           "bb" "-e" "(load-file \"dev/perf/jank_snapshot_key.clj\") ((resolve 'ensure!))")]
            (if (zero? (:exit r))
              (do (println (str "cohost: jank's core snapshot is built (" (quot (- (System/nanoTime) t0) 1000000000) " s); next commands start in about 2 s"))
                  true)
              (do (println "cohost: the snapshot build failed (target/jank-snapshot.log); this run uses the slower path")
                  false)))
          (finally (fs/delete-if-exists lock)))))))

(defn jank-cli-launch [repo]
  (let [[_ kind path] (let [c (jank-check repo)]
                        (if (and (= "aot" (second c)) (jank-build-snapshot! repo))
                          (jank-check repo)
                          c))]
    {:module-path (abs-path repo path) :snapshot? (= "snapshot" kind)
     :env (jank-gc-env)}))

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
        sums (cohost.dev.sha256/sha256sum-listing files)]
    (sha256-of (str (slurp (str repo "/target/jank-snapshot/stamp"))
                    sums
                    (cohost.dev.sha256/sha256sum-listing
                     [(str repo "/dev/perf/jank_snapshot_cli.clj")
                      (str repo "/dev/perf/jank_snapshot_project_record.jank")
                      (str repo "/dev/perf/jank_snapshot_emit.clj")])
                    "main " main "\n"))))

(defn jank-project-launch [repo project roots main core-path env]
  (when (and main (not= "none" (System/getenv "COHOST_JANK_PROJECT")))
    (let [dir (str (fs/absolutize (fs/path project "target/cohost-jank")))
          stamp (str dir "/stamp") src (str dir "/src") aot (str dir "/aot")
          k (project-key repo roots main)
          cache (fn [] (first (map str (fs/glob aot "_cache/*"))))
          fresh? (and (fs/exists? stamp) (= k (str/trim (slurp stamp))) (cache))]
      (when-not fresh?
        (binding [*out* *err*] (println "cohost: jank: compiling the project's namespaces ahead (once per change)"))
        (fs/delete-tree dir)
        (fs/create-dirs dir)
        (let [run (fn [& cmd] (apply p/shell {:dir repo :out :string :err :string :continue true :extra-env env} cmd))
              events (str dir "/events.edn")
              r1 (apply run "jank" "--module-path" core-path "run" (str repo "/dev/perf/jank_snapshot_project_record.jank")
                        "--" "--stock" (str repo "/resources/cohost/clojure-1.12.0") "--out" events "--lib" (str main)
                        (mapcat (fn [r] ["--root" r]) roots))
              r2 (when (zero? (:exit r1))
                   (run "bb" (str repo "/dev/perf/jank_snapshot_emit.clj") "--project" "--events" events "--out" src))
              r3 (when (and r2 (zero? (:exit r2)))
                   (run "jank" "-O3" "--module-path" (str src ":" core-path) "compile-module" "cohost.project-build"
                        "--target-dir" aot))]
          (if (and r3 (zero? (:exit r3)) (cache))
            (spit stamp (str k "\n"))
            (binding [*out* *err*]
              (println "cohost: jank: the project's ahead-of-time build failed; running without it")
              (println (:err (or r3 r2 r1)))))))
      (when (and (fs/exists? stamp) (cache))
        {:module-path (str (cache) ":" src ":" core-path) :project-snapshot "cohost.project"}))))

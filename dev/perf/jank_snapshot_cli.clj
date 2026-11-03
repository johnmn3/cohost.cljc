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

(defn- abs-path [repo path]
  (str/join ":" (map (fn [x] (str (fs/absolutize (fs/path repo x)))) (str/split path #":"))))

(defn- in-repo
  "Run bb code in the repo, in a child whose output is captured (the CLI's
   stdout stays the program's). -> its stdout lines."
  [repo code]
  (let [r (p/shell {:dir repo :out :string :err :string :continue true} "bb" "-e" code)]
    (str/split-lines (str (:out r) "\n" (:err r)))))

(defn- snapshot-fresh
  "{:path <module path>} when the core snapshot is fresh, else {:stale why}."
  [repo]
  (let [lines (in-repo repo (str "(load-file \"dev/perf/jank_snapshot_key.clj\")"
                                 "(when-let [m ((resolve 'fresh-path))] (println \"CLI-PATH snapshot\" m))"))
        line (last (filter #(str/starts-with? % "CLI-PATH ") lines))
        stale (first (filter #(str/starts-with? % "SNAPSHOT-STALE") lines))]
    (if line {:path (nth (str/split line #" " 3) 2)} {:stale (or stale "SNAPSHOT-STALE")})))

(defn- pid-alive?
  "Is process pid (a string) running? ProcessHandle: Linux and macOS alike."
  [pid]
  (boolean (when-let [n (some-> pid parse-long)]
             (.orElse (.map (java.lang.ProcessHandle/of n) (reify java.util.function.Function (apply [_ h] (.isAlive ^java.lang.ProcessHandle h)))) false))))

(defn build-snapshot!
  "OOTB #16/#17: build jank's core snapshot now, in the foreground, with one
   plain progress line on stderr (its own output goes to
   target/jank-snapshot-build.log). Never two at once: a lock holds the
   builder's pid; while another command builds, this one does not wait.
   -> true when this call built it."
  [repo why]
  (let [lock (str repo "/target/jank-snapshot-build.lock")
        log (str repo "/target/jank-snapshot-build.log")
        held (when (fs/exists? lock) (str/trim (slurp lock)))]
    (binding [*out* *err*]
      (cond
        (= "0" (System/getenv "COHOST_JANK_REBUILD"))
        (do (println (str "cohost: jank's core snapshot is " (if (str/includes? why "no stamp") "not built" "out of date")
                          "; this run boots without it (slower). To build it: (cd " repo " && bb boot:jank-snapshot)"))
            false)

        (pid-alive? held)
        (do (println (str "cohost: jank's core snapshot is being built by another command (pid " held "); this run boots without it"))
            false)

        :else
        (do (fs/create-dirs (str repo "/target"))
            (spit lock (str (.pid (java.lang.ProcessHandle/current)) "\n"))
            (println (str "cohost: building jank's core snapshot, a few minutes (2-6 measured), once per cohost update"
                          " (log: " log ")"))
            (try
              (let [t0 (System/nanoTime)
                    r (p/shell {:dir repo :out (java.io.File. log) :err :out :continue true}
                               "bb" "-e" "(load-file \"dev/perf/jank_snapshot_ensure.clj\")")]
                (if (zero? (:exit r))
                  (do (println (str "cohost: jank's core snapshot is built (" (quot (- (System/nanoTime) t0) 1000000000) " s)")) true)
                  (do (println (str "cohost: building jank's core snapshot failed (see " log "); this run boots without it")) false)))
              (finally (fs/delete-if-exists lock))))))))

(defn jank-cli-launch [repo]
  (let [d (snapshot-fresh repo)
        d (if (:path d) d (if (build-snapshot! repo (:stale d)) (snapshot-fresh repo) d))
        path (or (:path d)
                 (let [lines (in-repo repo "(println \"CLI-PATH aot\" (str (load-file \"dev/perf/jank_aot_ensure.clj\") \":src\"))")
                       line (last (filter #(str/starts-with? % "CLI-PATH ") lines))]
                   (when-not line
                     (throw (ex-info (str "jank: no module path from dev/perf/jank_aot_ensure.clj: " (str/join "\n" lines)) {})))
                   (nth (str/split line #" " 3) 2)))]
    {:module-path (abs-path repo path) :snapshot? (boolean (:path d))
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

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

(defn- abs-path [repo path]
  (str/join ":" (map (fn [x] (str (fs/absolutize (fs/path repo x)))) (str/split path #":"))))

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
    (when stale (binding [*out* *err*] (println (str "cohost: jank: " stale))))
    {:module-path (abs-path repo path) :snapshot? (= "snapshot" kind)
     :env (if (= "none" (System/getenv "COHOST_JANK_GC"))
            {}
            (into {} (remove (fn [[k _]] (System/getenv k)) {"GC_INITIAL_HEAP_SIZE" "512M"})))}))

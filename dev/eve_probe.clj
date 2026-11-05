;; Eve-in-cohost load probe — runner (bb, JVM side).
;; Usage: bb dev/eve_probe.clj <host> [<host> ...]
;;   host = a bin/cohost target: jvm jank cljs jolt lpy cljw lg cljrs phel
;; Boots the host ONCE and feeds it, as separate top-level -e forms (in
;; one process, in order):
;;   1. one try/require form per stock library (prints its PROBE line),
;;   2. dev/eve_probe_program.cljc (the threading-surface cases).
;; Separate top-level forms because ClojureScript's analyzer rejects a
;; require nested inside a fn body or any non-top-level position.
;; Hosts run one at a time (serial): several hosts compile at boot and
;; this box has 8 GB.
(require '[babashka.process :as p]
         '[clojure.string :as str])

(def libs '[clojure.walk clojure.set clojure.string clojure.zip
            clojure.data clojure.edn clojure.template clojure.test
            clojure.math clojure.stacktrace clojure.repl
            clojure.spec.alpha])

(defn load-form [lib]
  (str "(try (require '" lib ") (println \"PROBE|load-" lib "|OK :loaded\")"
       " (catch Throwable t (println (str \"PROBE|load-" lib "|FAIL \""
       " (.getName (class t)) \" :: \" (ex-message t)))))"))

(def program (slurp "dev/eve_probe_program.cljc"))
(def timeout-ms 600000)

(defn classify [line]
  (let [[_ nm res] (str/split line #"\|" 3)]
    (cond
      (str/starts-with? res "OK") [nm :ok res]
      (str/includes? res "not available on this host")
      [nm :unsupported-shim res]
      :else [nm :fail-other res])))

(doseq [host *command-line-args*]
  (println (str "=== host: " host " ==="))
  (let [t0 (System/currentTimeMillis)
        args (vec (concat ["bin/cohost" (str ":" host)]
                          (mapcat (fn [f] ["-e" f]) (map load-form libs))
                          ["-e" program]))
        r (try (apply p/shell {:out :string :err :string :timeout timeout-ms} args)
               (catch Throwable t {:out "" :err (str "RUNNER " (ex-message t))}))
        lines (filter #(str/starts-with? % "PROBE|") (str/split-lines (str (:out r))))
        ms (- (System/currentTimeMillis) t0)]
    (doseq [l lines] (println l))
    (when (empty? lines)
      (println "PROBE|boot|FAIL no probe output")
      (println (str "stderr tail: " (str/join "\n" (take-last 5 (str/split-lines (str (:err r))))))))
    (let [cs (map classify lines)
          n (fn [k] (count (filter #(= k (second %)) cs)))]
      (println (str "SUMMARY|" host "|ok=" (n :ok)
                    " unsupported-shim=" (n :unsupported-shim)
                    " fail-other=" (n :fail-other)
                    " wall-ms=" ms)))))

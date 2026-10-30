;; The corpus gap read against JVM Clojure 1.12.0 (`bb corpus:jvm`, after
;; `bb corpus:jolt`): each row jolt's own core passes and the stock core fails
;; is evaluated here, in a fresh namespace, and compared, printed, with the
;; row's expected answer as cohost.corpus compares it. Rows the JVM does not
;; answer as the corpus expects are jolt's behavior or a jolt / ClojureScript
;; extension, not a gap in the stock core.
;;
;; args: corpus-report.cljc  corpus-stock.edn  corpus-host.edn  [out.edn]
(require '[clojure.edn :as edn])
(load-file (nth *command-line-args* 0))

(defn- outcome [src expected]
  (let [n (create-ns (gensym "corpus.jvm.row"))
        f (future (binding [*ns* n *print-length* 10000]
                    (refer-clojure)
                    (try (let [printed (pr-str (eval (read-string src)))]
                           (cond (= expected :throws) [:fail printed]
                                 (= expected printed) [:pass printed]
                                 :else [:fail printed]))
                         (catch Throwable e
                           (if (= expected :throws)
                             [:pass :threw]
                             [:error (str (.getName (class e)) ": " (.getMessage e))])))))]
    (deref f 10000 [:error "timeout"])))

(let [rd (fn [i] (edn/read-string {:default (fn [_ v] v)} (slurp (nth *command-line-args* i))))
      rows (cohost.corpus-report/merge-sides (rd 1) (rd 2))
      gap (filter #(and (not= :pass (:stock %)) (= :pass (:host %))) rows)
      out (vec (for [r gap]
                 (let [[o v] (outcome (:actual r) (:expected r))]
                   (assoc (select-keys r [:suite :label :actual :expected :stock-value]) :jvm o :jvm-value v))))
      confirmed (filter #(= :pass (:jvm %)) out)]
  (println (str "corpus gap: " (count out) " rows jolt's core passes and the stock core fails"))
  (println (str "  JVM Clojure gives the expected answer: " (count confirmed) " (the stock core's gaps)"))
  (println (str "  JVM Clojure does not: " (- (count out) (count confirmed)) " (jolt's behavior or extensions):"))
  (doseq [r (remove #(= :pass (:jvm %)) out)]
    (println (str "   - " (:suite r) " / " (:label r) "\n       " (:actual r)
                  "\n       expected " (:expected r) "  JVM " (pr-str (:jvm-value r)) "  stock " (pr-str (:stock-value r)))))
  (when-let [path (get (vec *command-line-args*) 3)]
    (spit path (with-out-str (doseq [r out] (prn r))))))

(shutdown-agents)
(System/exit 0)

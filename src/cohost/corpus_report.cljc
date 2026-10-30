(ns cohost.corpus-report
  "The corpus scoreboard's report, with no dependencies: babashka loads it to
   merge the stock side's rows and the host side's rows, which run in
   separate processes (both sides share the host's namespace registry, so a
   row that makes a namespace on one side would meet it on the other).")

(defn summarize
  "Print the scoreboard from rows of {:suite :label :actual :expected :stock
   :stock-value :host :host-value}."
  [results show host-label]
  (let [n (count results)
        tally (fn [k] (frequencies (keep k results)))
        st (tally :stock) ht (tally :host)]
    (println (str "corpus (" n " :common rows, expected values from JVM Clojure):"))
    (println (str "  stock core on " host-label ":  pass " (get st :pass 0) "  fail " (get st :fail 0) "  error " (get st :error 0)))
    (println (str "  " host-label "'s own core:     pass " (get ht :pass 0) "  fail " (get ht :fail 0) "  error " (get ht :error 0)))
    (println (str "  rows stock passes and host fails: " (count (filter #(and (= :pass (:stock %)) (not= :pass (:host %))) results))
                  "   host passes and stock fails: " (count (filter #(and (not= :pass (:stock %)) (= :pass (:host %))) results))))
    (when (pos? show)
      (println "  stock fails where the host passes (first" show "):")
      (doseq [r (take show (filter #(and (not= :pass (:stock %)) (= :pass (:host %))) results))]
        (println (str "   - " (:suite r) " / " (:label r) "\n       " (:actual r) "\n       expected " (:expected r) "  stock " (pr-str (:stock-value r)))))
      (println "  stock passes where the host fails (first" show "):")
      (doseq [r (take show (filter #(and (= :pass (:stock %)) (not= :pass (:host %))) results))]
        (println (str "   - " (:suite r) " / " (:label r) "\n       " (:actual r) "\n       expected " (:expected r) "  host " (pr-str (:host-value r))))))
    {:n n :stock st :host ht}))

(defn merge-sides
  "Rows of a :stock run and a :host run (each from its own process: a row
   that defines a namespace must not meet the other side's) -> one row each."
  [stock-rows host-rows]
  (let [h (into {} (map (juxt (juxt :suite :label :actual) identity)) host-rows)]
    (map (fn [r] (merge r (select-keys (get h [(:suite r) (:label r) (:actual r)]) [:host :host-value]))) stock-rows)))


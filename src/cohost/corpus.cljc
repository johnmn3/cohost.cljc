;; A scoreboard: the jolt-conformance corpus (jolt's test/chez/corpus.edn,
;; expected values meant as JVM Clojure's; doc/REPORT.md counts the rows where
;; JVM Clojure 1.12.0 answers otherwise) evaluated twice per row —
;; through the booted stock core (cohost.user) and through the host's own
;; core — and compared, as printed, to the JVM's answer. Two numbers per host:
;; how stock the booted core is, and how it compares to the host's hand-written
;; core on the same rows. Printing differences count against both equally.
(ns cohost.corpus
  (:require [cohost.adapter :as host]
            [cohost.boot :as boot]
            [cohost.corpus-report :refer [summarize]]))

(def host-ns 'cohost.corpus-host)

(defn- ensure-host-ns!
  "A namespace that sees the host's own core (the comparison side)."
  []
  (host/with-ns (host/core-ns-sym) (fn [] (host/eval-form (list 'ns host-ns))))
  host-ns)

(defn- eval-host-str [src]
  (host/with-ns host-ns (fn [] (host/eval-form (host/read-string* src)))))

(defn- outcome [f expected]
  (host/try-all (fn [] (let [v (f)
                             printed (pr-str v)]
                         (cond (= expected :throws) [:fail printed]
                               (= expected printed) [:pass printed]
                               :else [:fail printed])))
                (fn [e]
                  (if (= expected :throws) [:pass :threw] [:error (host/error-message e)]))))

(defn run-corpus!
  "opts: :path (the corpus.edn), :limit (rows), :skip, :verbose, :show (print
   the first n rows where stock fails but the host passes, and vice versa),
   :side (:both, :stock or :host; default :both), :out (write the rows as EDN
   to this file). Run :stock and :host in separate processes and merge them
   with merge-sides: both sides share the host's namespace registry, so a row
   that makes a namespace on one side would meet it on the other."
  [{:keys [path limit skip show verbose exclude-labels side out]
    :or {show 12 skip 0 side :both
         ;; `(let [p (promise)] @p)` blocks forever on this jolt (v0.8.23) and on
         ;; the JVM, on the stock side and the host side alike; no row may hang
         ;; the scoreboard
         exclude-labels #{"promise undelivered"}}}]
  (when (not= side :stock) (ensure-host-ns!))
  (let [rows (host/read-string* (host/slurp-file path))
        rows (filter #(= :common (:portability %)) rows)
        rows (remove #(contains? exclude-labels (:label %)) rows)
        rows (drop skip rows)
        rows (if limit (take limit rows) rows)
        n-rows (count rows)
        results (doall
                 (map-indexed
                  (fn [i {:keys [suite label expected actual]}]
                    (when (or verbose (zero? (mod i 250)))
                      (println (str "  row " (+ skip i) "/" (+ skip n-rows) (when verbose (str "  " suite " / " label))))
                      (flush))
                    ;; a divergence that yields an unbounded seq must not hang the printer
                    (let [[s sv] (when (not= side :host) (binding [*print-length* 10000] (outcome #(boot/eval-str actual {:session? false}) expected)))
                          [h hv] (when (not= side :stock) (binding [*print-length* 10000] (outcome #(eval-host-str actual) expected)))]
                      (cond-> {:suite suite :label label :expected expected :actual actual}
                        s (assoc :stock s :stock-value sv)
                        h (assoc :host h :host-value hv))))
                  rows))]
    (when out (host/spit-file out (binding [*print-length* nil *print-level* nil] (pr-str (vec results)))))
    (if (= side :both)
      (summarize results show (name (host/host-name)))
      (println (str "corpus " (name side) " side: " (count results) " rows"
                    (when out (str " written to " out)))))))

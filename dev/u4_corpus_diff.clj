;; compare two corpus stock-side EDN outputs (off vs on):
;; outcome tallies per side + every row whose :stock outcome
;; changed, with both printed values.
(require '[clojure.edn :as edn] '[clojure.java.io :as io])
(def a (edn/read-string (slurp (first *command-line-args*))))
(def b (edn/read-string (slurp (second *command-line-args*))))
(defn tally [rows k] (frequencies (map k rows)))
(println "rows:" (count a) (count b))
(println "off outcomes:" (tally a :stock))
(println "on  outcomes:" (tally b :stock))
(def changed (filter (fn [[x y]] (not= (:stock x) (:stock y))) (map vector a b)))
(println "changed rows:" (count changed))
(doseq [[x y] changed]
  (println "----" (:suite x) "/" (:label x))
  (println "  src:" (:actual x))
  (println "  expected:" (:expected x))
  (println "  off:" (:stock x) (pr-str (:stock-value x)))
  (println "  on :" (:stock y) (pr-str (:stock-value y))))

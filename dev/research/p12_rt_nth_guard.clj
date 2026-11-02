;; P12 (plan item 5): the cohost-instance guard on RT-nth, under cohost on jank.
;; nth-stock: (nth v i) through the stock core (the shim's RT-nth: h-cohost-inst? guard,
;;   a var call into the adapter namespace, then jank's nth).
;; nth-host: jank's own clojure.core/nth.
;; nth-guard-inline: the guard as the cpp/raw call in the emitted form, then jank's nth.
(def v (vec (range 1000)))
(def n 5000000)
(defn nth-stock [] (loop [i 0 s 0] (if (< i n) (recur (inc i) (+ s (nth v (mod i 1000)))) s)))
(defn nth-host [] (loop [i 0 s 0] (if (< i n) (recur (inc i) (+ s (clojure.core/nth v (mod i 1000)))) s)))
(defn nth-guard-inline [] (loop [i 0 s 0] (if (< i n) (recur (inc i) (+ s (if (cpp/cohost_obj.is_cohost v) 0 (clojure.core/nth v (mod i 1000))))) s)))
(defn count-stock [] (loop [i 0 s 0] (if (< i n) (recur (inc i) (+ s (count v))) s)))
(defn count-host [] (loop [i 0 s 0] (if (< i n) (recur (inc i) (+ s (clojure.core/count v))) s)))
(defn run []
  (bench "nth-stock-5e6" nth-stock)
  (bench "nth-host-5e6" nth-host)
  (bench "nth-guard-inline-5e6" nth-guard-inline)
  (bench "count-stock-5e6" count-stock)
  (bench "count-host-5e6" count-host))

;; A5: cohost's integer arithmetic past 2^31 (run: bash dev/research/run-cohost-jank.sh dev/research/a5/int_arith.clj;
;; the same file under JVM Clojure prints the reference values). jank-12's shapes plus the order's.
(defn shapes []
  [(+ 2147483647 1) (inc 2147483647) (+ 2147483647 2147483647) (- -2147483648 1) (dec -2147483648)
   (* 65536 65536) (* 46341 46341) (- 0 -2147483648) (+ 2147483648 2147483648)
   (reduce + (range 1000000)) (apply + (range 100000)) (let [x 3000000000] (+ x 1))
   (try (+ 9223372036854775807 1) (catch Exception e :overflow)) (+ 1.5 2) (+ 1 2) (- 5) (* 3 4)])
(def n 1000000)
(def v (vec (range n)))
(defn sum-loop [] (loop [i 0 s 0] (if (< i n) (recur (inc i) (+ s i)) s)))
(defn small-loop [] (loop [i 0 s 0] (if (< i n) (recur (inc i) (+ s 1)) s)))
(defn mul-loop [] (loop [i 0 s 0] (if (< i n) (recur (inc i) (+ s (* i 3))) s)))
(defn run []
  (println "shapes" (pr-str (shapes)))
  (bench "sum-loop past 2^31" sum-loop)
  (bench "small-loop inside i32" small-loop)
  (bench "mul-loop" mul-loop)
  (bench "reduce + v" (fn [] (reduce + v))))

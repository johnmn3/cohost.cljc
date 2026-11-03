;; A7-5 (u3): user [x & ys] calls under cohost (jank-29's shapes), 1e6 calls each.
(defn v-unused [x & ys] x)
(defn v-first [x & ys] (first ys))
(defn v-count [x & ys] (count ys))
(defn f2 [x y] x)
(def N 1000000)
(defn run []
  (bench "call-2" #(loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (f2 i 1))) a)))
  (bench "call-variadic" #(loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (v-unused i 1))) a)))
  (bench "call-variadic-first" #(loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (v-first i 1))) a)))
  (bench "call-variadic-count" #(loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (v-count i 1 2))) a))))

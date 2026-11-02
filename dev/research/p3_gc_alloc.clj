;; P3, the JVM twin of p3_gc_alloc.jank (same code; portable Clojure).
  (if (zero? d) nil (let [d (dec d)] [(build d) (build d)])))
(defn check [t]
  (if (nil? t) 1 (+ 1 (check (t 0)) (check (t 1)))))
(defn bt [max-d]
  (loop [d 4 acc (check (build (inc max-d)))]
    (if (> d max-d)
      acc
      (let [n (bit-shift-left 1 (+ (- max-d d) 2))]
        (recur (+ d 2)
               (+ acc (loop [i 0 s 0]
                        (if (= i n) s (recur (inc i) (+ s (check (build d)))))))))))))
(defn pair-alloc [] (loop [i 0 last nil] (if (< i 2000000) (recur (inc i) [i i]) (count last))))
(defn box-double [] (loop [i 0 acc 0.5] (if (< i 5000000) (recur (inc i) (+ acc 0.5)) acc)))
(defn box-long [] (loop [i 0 acc 0] (if (< i 5000000) (recur (inc i) (+ acc 3)) acc)))
(defn run []
  (bench "bt-14" #(bt 14))
  (bench "pair-alloc-2e6" pair-alloc)
  (bench "box-double-5e6" box-double)
  (bench "box-long-5e6" box-long))

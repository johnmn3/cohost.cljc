(defn run []
  (bench "vec2-build-2e6" #(loop [i 0 n 0] (if (< i 2000000) (recur (inc i) (+ n (count [i i]))) n)))
  (bench "sort-rest-count-1e5" #(count (rest (sort (vec (range 100000 0 -1))))))
  (bench "str-4-args-1e5" #(loop [i 0 n 0] (if (< i 100000) (recur (inc i) (+ n (count (str "k" i ":" \v)))) n))))

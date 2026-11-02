;; Sort n pseudo-random numbers (an LCG kept under 2^23, so no host wraps), then
;; nth of the middle element of the sorted seq (a walk to n/2).
(defn gen [n]
  (loop [i 0 x 1 v []]
    (if (= i n)
      v
      (let [x (mod (+ (* x 75) 74) 65537)]
        (recur (inc i) x (conj v x))))))

(defn -run [s]
  (let [n (parse-long s)
        v (sort (gen n))]
    (nth v (quot n 2))))

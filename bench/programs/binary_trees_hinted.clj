;; Same trees, depth and counters hinted ^long.
(defn build [^long d]
  (if (zero? d)
    nil
    (let [d (- d 1)]
      [(build d) (build d)])))

(defn check [t]
  (if (nil? t)
    1
    (+ 1 (check (t 0)) (check (t 1)))))

(defn -run [s]
  (let [^long max-d (__long s)]
    (loop [d 4 acc (check (build (+ max-d 1)))]
      (if (> d max-d)
        acc
        (let [^long n (bit-shift-left 1 (+ (- max-d d) 2))]
          (recur (+ d 2)
                 (+ acc
                    (loop [i 0 s 0]
                      (if (= i n)
                        s
                        (recur (inc i) (+ s (check (build d)))))))))))))

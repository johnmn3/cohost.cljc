;; Sieve, indexes hinted ^long.
(defn -run [s]
  (let [^long m (__long s)
        ^long n (bit-shift-left 1 m)
        flags (transient (vec (repeat n true)))]
    (loop [i 2 cnt 0 flags flags]
      (if (>= i n)
        cnt
        (if-not (nth flags i)
          (recur (inc i) cnt flags)
          (recur (inc i)
                 (inc cnt)
                 (loop [j (+ i i) flags flags]
                   (if (>= j n)
                     flags
                     (recur (+ j i) (assoc! flags j false))))))))))

;; Count of primes below 2^m, the sieve of Eratosthenes over a vector of flags.
(defn -run [s]
  (let [m (__long s)
        n (bit-shift-left 1 m)
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

;; Generate a DNA string and return the count of the 2-mer "at".
(defn gen [n]
  (loop [i 0 a 42 out []]
    (if (= i n)
      out
      (let [a (mod (+ (* a 3877) 29573) 139968)]
        (recur (inc i) a (conj out (nth "acgt" (mod a 4))))))))

(defn -run [s]
  (let [n (__long s)
        dna (gen n)]
    (loop [i 0 cnt 0]
      (if (>= (inc i) n)
        cnt
        (recur (inc i)
               (if (and (= (nth dna i) \a) (= (nth dna (inc i)) \t))
                 (inc cnt)
                 cnt))))))

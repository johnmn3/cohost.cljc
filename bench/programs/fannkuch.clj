;; Checksum of pancake flips over the lexicographic permutations of 0..n-1.
;; The sign alternates in that order. n=7 is enough work to time; n=8 is the
;; default the driver passes.
(defn flips [p]
  (loop [p (vec p) f 0]
    (let [k (p 0)]
      (if (zero? k)
        f
        (recur (loop [p p i 0 j k]
                 (if (< i j)
                   (recur (assoc p i (p j) j (p i)) (inc i) (dec j))
                   p))
               (inc f))))))

(defn perms [xs]
  (if (empty? xs)
    [[]]
    (mapcat (fn [x]
              (map (fn [ys] (cons x ys))
                   (perms (remove (fn [y] (= y x)) xs))))
            xs)))

(defn -run [s]
  (let [n (__long s)]
    (loop [ps (perms (range n)) sign 1 acc 0]
      (if (nil? ps)
        acc
        (recur (next ps) (- sign) (+ acc (* sign (flips (first ps)))))))))

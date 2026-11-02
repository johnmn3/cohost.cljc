;; (tak 18 12 6) is 7, parameters hinted ^long.
(defn tak [^long x ^long y ^long z]
  (if (not (< y x))
    z
    (tak (tak (- x 1) y z)
         (tak (- y 1) z x)
         (tak (- z 1) x y))))
(defn -run [s]
  (let [r (read-string (str "[" s "]"))]
    (tak (nth r 0) (nth r 1) (nth r 2))))

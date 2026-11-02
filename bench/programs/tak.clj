;; (tak 18 12 6) is 7.
(defn tak [x y z]
  (if (not (< y x))
    z
    (tak (tak (dec x) y z)
         (tak (dec y) z x)
         (tak (dec z) x y))))
(defn -run [s]
  (let [r (read-string (str "[" s "]"))]
    (tak (nth r 0) (nth r 1) (nth r 2))))

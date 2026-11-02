;; Protocol call on a record plus a multimethod, summed n times.
(defprotocol Area
  (area [s]))

(defrecord Sq [w]
  Area
  (area [_] (* w w)))

(defmulti kind (fn [x] (mod x 3)))
(defmethod kind 0 [x] x)
(defmethod kind 1 [x] (* 2 x))
(defmethod kind :default [x] 0)

(defn -run [s]
  (let [n (parse-long s)
        sq (->Sq 3)]
    (loop [i 0 acc 0]
      (if (= i n)
        acc
        (recur (inc i) (+ acc (area sq) (kind i)))))))

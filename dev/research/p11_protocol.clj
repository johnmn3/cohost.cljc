;; P11 (session 3): dispatch under cohost on jank (and the JVM twin).
;; proto-call: 1e6 calls of a protocol method on a record (cohost's type layer
;;   on jank: a variadic method fn + protocol-dispatch + apply per call).
;; fn-call: the same arithmetic through a plain defn on the record (:w lookup).
;; multi-call: 1e6 calls of a one-method defmulti (the stock MultiFn in the shim).
;; kw-on-record: 1e6 (:w sq) lookups.
(defprotocol Area (area [s]))
(defrecord Sq [w] Area (area [_] (* w w)))
(defn area2 [s] (let [w (:w s)] (* w w)))
(defmulti kind (fn [x] (mod x 3)))
(defmethod kind 0 [x] x)
(defmethod kind :default [x] 0)
(def sq (->Sq 3))
(def n 1000000)
(defn proto-call [] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc (area sq))) acc)))
(defn fn-call [] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc (area2 sq))) acc)))
(defn multi-call [] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc (kind i))) acc)))
(defn kw-on-record [] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc (:w sq))) acc)))
(defn run []
  (bench "proto-call-1e6" proto-call)
  (bench "fn-call-1e6" fn-call)
  (bench "multi-call-1e6" multi-call)
  (bench "kw-on-record-1e6" kw-on-record))

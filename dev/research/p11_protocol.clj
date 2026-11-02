;; P11 (session 3): dispatch under cohost on jank (and the JVM twin).
;; proto-call: 1e6 calls of a protocol method on a record (cohost's type layer
;;   on jank: a variadic method fn + protocol-dispatch + apply per call; X2-1:
;;   a fixed-arity fn and an inline cache).
;; fn-call: the same arithmetic through a plain defn on the record (:w lookup).
;; multi-call: 1e6 calls of a one-method defmulti (the stock MultiFn in the shim).
;; kw-on-record: 1e6 (:w sq) lookups.
;; X2-1 adds: proto-call-2 (a two-argument method), proto-wide (a method on a
;;   five-field record reading one field), proto-long (a protocol extended to
;;   Long: the uncached path), multi-mod3 (the dispatch program's three-way
;;   defmulti, dispatch values rotating 0 1 2).
(defprotocol Area (area [s]) (scaled [s k]))
(defrecord Sq [w] Area (area [_] (* w w)) (scaled [_ k] (* k w)))
(defrecord Wide [a b c d e] Area (area [_] (* c c)) (scaled [_ k] (* k c)))
(extend-protocol Area Long (area [x] (* x x)) (scaled [x k] (* k x)))
(defn area2 [s] (let [w (:w s)] (* w w)))
(defmulti kind (fn [x] (mod x 3)))
(defmethod kind 0 [x] x)
(defmethod kind :default [x] 0)
(defmulti kind3 (fn [x] (mod x 3)))
(defmethod kind3 0 [x] x)
(defmethod kind3 1 [x] (* 2 x))
(defmethod kind3 :default [x] 0)
(def sq (->Sq 3))
(def wide (->Wide 1 2 3 4 5))
(def n 1000000)
(defn proto-call [] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc (area sq))) acc)))
(defn fn-call [] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc (area2 sq))) acc)))
(defn multi-call [] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc (kind i))) acc)))
(defn kw-on-record [] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc (:w sq))) acc)))
(defn proto-call-2 [] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc (scaled sq 2))) acc)))
(defn proto-wide [] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc (area wide))) acc)))
(defn proto-long [] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc (area 3))) acc)))
(defn multi-mod3 [] (loop [i 0 acc 0] (if (< i n) (recur (inc i) (+ acc (kind3 i))) acc)))
(defn run []
  (bench "proto-call-1e6" proto-call)
  (bench "fn-call-1e6" fn-call)
  (bench "multi-call-1e6" multi-call)
  (bench "kw-on-record-1e6" kw-on-record)
  (bench "proto-call-2-1e6" proto-call-2)
  (bench "proto-wide-1e6" proto-wide)
  (bench "proto-long-1e6" proto-long)
  (bench "multi-mod3-1e6" multi-mod3))

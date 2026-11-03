;; A6-4: the multimethod call's parts under cohost, 1e6 steps each
(defmulti pkind (fn [x] (mod x 3)))
(defmethod pkind 0 [x] x)
(defmethod pkind 1 [x] (* 2 x))
(defmethod pkind :default [x] 0)
(defmulti pk1 (fn [x] 0))
(defmethod pk1 0 [x] x)
(defmulti pkid identity)
(defmethod pkid :default [x] 0)
(def dfn (fn [x] (mod x 3)))
(def meth (fn [x] (* 2 x)))
(def tbl {0 1 1 2 2 3})
(def N 1000000)
(defn e00-loop [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (bit-and i 15))) a)))
(defn e01-multi3 [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (pkind i))) a)))
(defn e02-multi-one-dv [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (pk1 i))) a)))
(defn e03-multi-identity-default [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (pkid 5))) a)))
(defn e04-dispatch-fn [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (dfn i))) a)))
(defn e05-method-fn [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (meth i))) a)))
(defn e06-map-get-int [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (get tbl (bit-and i 1)))) a)))
(defn run []
  (doseq [[l f] [["e00-loop" e00-loop] ["e01-multi3" e01-multi3] ["e02-multi-one-dv" e02-multi-one-dv] ["e03-multi-identity-default" e03-multi-identity-default]
                 ["e04-dispatch-fn" e04-dispatch-fn] ["e05-method-fn" e05-method-fn] ["e06-map-get-int" e06-map-get-int]]]
    (bench l f)))

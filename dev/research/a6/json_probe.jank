;; A6-2: the json program's per-operation candidates, 1e6 steps each (cohost: bash dev/research/run-cohost-jank.sh
;; dev/research/a6/json_probe.clj; plain jank: cp to .jank, bash dev/research/run-jank.sh <it> aot). Labels pNN.
(def N 1000000)
(def s16 "0123456789abcdef")
(def v4 [nil 1 "a" true])
(def m2 {"c" 4 "d" 8})
(defn p00-loop [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (bit-and i 15))) a)))
(defn p01-int-nth-str [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (int (nth s16 (bit-and i 15))))) a)))
(defn p02-nth-str [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (if (identical? (nth s16 (bit-and i 15)) \a) (inc a) a)) a)))
(defn p03-count-str [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (count s16))) a)))
(defn p04-nil? [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (if (nil? (nth v4 (bit-and i 3))) (inc a) a)) a)))
(defn p05-preds [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (let [x (nth v4 (bit-and i 3))] (cond (true? x) 3 (false? x) 4 (string? x) 1 (number? x) 2 :else 5)))) a)))
(defn p06-str-long [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (count (str (long (bit-and i 1023)))))) a)))
(defn p07-apply-str-5 [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (count (apply str ["\"" "ab" "\":" "x" ","])))) a)))
(defn p08-conj-into [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (count (into (conj [] ",") ["a" "b"])))) a)))
(defn p09-map-assoc [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (count (assoc (assoc {} "c" i) "d" i)))) a)))
(defn p10-entry-destr [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (let [[k v] (first m2)] v))) a)))
(defn p11-vec-destr [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (let [[v j] [i 1]] j))) a)))
(defn p12-num-arith [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (let [c (int (nth s16 (bit-and i 7)))] (+ (* (bit-and a 1023) 10) (- c 48)))) a)))
(defn p13-apply-str-chars [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (count (apply str [\a \b \c \d])))) a)))
(defn p14-seq-next-map [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (loop [xs (seq m2) n 0] (if (nil? xs) n (recur (next xs) (inc n)))))) a)))
(defn run []
  (doseq [[l f] [["p00-loop" p00-loop] ["p01-int-nth-str" p01-int-nth-str] ["p02-nth-str" p02-nth-str] ["p03-count-str" p03-count-str]
                 ["p04-nil?" p04-nil?] ["p05-preds" p05-preds] ["p06-str-long" p06-str-long] ["p07-apply-str-5" p07-apply-str-5]
                 ["p08-conj-into" p08-conj-into] ["p09-map-assoc" p09-map-assoc] ["p10-entry-destr" p10-entry-destr]
                 ["p11-vec-destr" p11-vec-destr] ["p12-num-arith" p12-num-arith] ["p13-apply-str-chars" p13-apply-str-chars]
                 ["p14-seq-next-map" p14-seq-next-map]]]
    (bench l f)))

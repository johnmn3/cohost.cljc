(defn chunks [x]
  (cond
    (nil? x) ["null"]
    (true? x) ["true"]
    (false? x) ["false"]
    (string? x) ["\"" x "\""]
    (number? x) [(str (long x))]
    (vector? x)
    (into ["["]
          (conj (loop [xs (seq x) acc [] first? true]
                  (if (nil? xs)
                    acc
                    (recur (next xs)
                           (into (if first? acc (conj acc ",")) (chunks (first xs)))
                           false)))
                "]"))
    (map? x)
    (into ["{"]
          (conj (loop [xs (seq x) acc [] first? true]
                  (if (nil? xs)
                    acc
                    (let [[k v] (first xs)]
                      (recur (next xs)
                             (into (if first? acc (conj acc ","))
                                   (into ["\"" (str k) "\":"] (chunks v)))
                             false))))
                "}"))))

(defn print-json [x]
  (apply str (chunks x)))

(declare parse)

(defn skip [s i]
  (loop [i i]
    (if (and (< i (count s)) (= (int (nth s i)) 32))
      (recur (inc i))
      i)))

(defn parse-string [s i]
  (loop [j (inc i) out []]
    (if (= (int (nth s j)) 34)
      [(apply str out) (inc j)]
      (recur (inc j) (conj out (nth s j))))))

(defn parse-num [s i]
  (loop [j i n 0]
    (let [c (if (< j (count s)) (int (nth s j)) 0)]
      (if (or (< c 48) (> c 57))
        [n j]
        (recur (inc j) (+ (* n 10) (- c 48)))))))

(defn parse [s i]
  (let [i (skip s i)
        c (int (nth s i))]
    (cond
      (= c 110) [nil (+ i 4)]
      (= c 116) [true (+ i 4)]
      (= c 102) [false (+ i 5)]
      (= c 34) (parse-string s i)
      (= c 91)
      (loop [i (inc i) acc []]
        (let [i (skip s i)]
          (cond
            (= (int (nth s i)) 93) [acc (inc i)]
            (= (int (nth s i)) 44) (let [[v i] (parse s (inc i))] (recur i (conj acc v)))
            :else (let [[v i] (parse s i)] (recur i (conj acc v))))))
      (= c 123)
      (loop [i (inc i) acc {}]
        (let [i (skip s i)]
          (cond
            (= (int (nth s i)) 125) [acc (inc i)]
            (= (int (nth s i)) 44)
            (let [i (skip s (inc i))
                  [k i] (parse s i)
                  i (skip s i)
                  i (inc i)
                  [v i] (parse s i)]
              (recur i (assoc acc k v)))
            :else
            (let [[k i] (parse s i)
                  i (skip s i)
                  i (inc i)
                  [v i] (parse s i)]
              (recur i (assoc acc k v))))))
      :else (parse-num s i))))

(defn checksum [x]
  (cond
    (nil? x) 1
    (true? x) 2
    (false? x) 3
    (number? x) x
    (string? x) (count x)
    (vector? x) (reduce + 1 (map checksum x))
    (map? x) (reduce + 1 (map (fn [[k v]] (+ (count k) (checksum v))) x))
    :else 0))

(def sample
  {"a" [1 true nil "xy"]
   "b" {"c" 4 "d" [false 8]}})
(def printed (print-json sample))
(def parsed (first (parse printed 0)))
(def K 30000)
(defn s1-print [] (loop [i 0 a 0] (if (< i K) (recur (inc i) (+ a (count (print-json sample)))) a)))
(defn s2-parse [] (loop [i 0 a 0] (if (< i K) (recur (inc i) (+ a (count (first (parse printed 0))))) a)))
(defn s3-checksum [] (loop [i 0 a 0] (if (< i K) (recur (inc i) (+ a (checksum parsed))) a)))
(defn s4-chunks [] (loop [i 0 a 0] (if (< i K) (recur (inc i) (+ a (count (chunks sample)))) a)))
(defn s5-skip-nums [] (loop [i 0 a 0] (if (< i K) (recur (inc i) (+ a (second (parse-num "12345678 " 0)) (skip "     x" 0))) a)))
(defn run []
  (doseq [[l f] [["s1-print" s1-print] ["s2-parse" s2-parse] ["s3-checksum" s3-checksum] ["s4-chunks" s4-chunks] ["s5-skip-nums" s5-skip-nums]]]
    (bench l f)))

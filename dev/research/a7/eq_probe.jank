;; A7-3: = against == in a loop, and = on other kinds, 1e7 / 1e6 steps (cohost: run-cohost-jank.sh; plain jank: the .jank twin, run-jank.sh aot)
(def M 10000000)
(def N 1000000)
(def s "abcdefgh")
(defn r00-loop [] (loop [i 0 c 0] (if (< i M) (recur (inc i) (+ c (bit-and i 1))) c)))
(defn r01-loop-eq [] (loop [i 0 c 0] (if (< i M) (recur (inc i) (if (= (bit-and i 7) 3) (inc c) c)) c)))
(defn r02-loop-numeq [] (loop [i 0 c 0] (if (< i M) (recur (inc i) (if (== (bit-and i 7) 3) (inc c) c)) c)))
(defn r03-eq-char [] (loop [i 0 c 0] (if (< i N) (recur (inc i) (if (= (nth s (bit-and i 7)) \c) (inc c) c)) c)))
(defn r04-eq-kw [] (loop [i 0 c 0] (if (< i N) (recur (inc i) (if (= (if (odd? i) :a :b) :a) (inc c) c)) c)))
(defn r05-eq-str [] (loop [i 0 c 0] (if (< i N) (recur (inc i) (if (= (if (odd? i) "ab" "ac") "ab") (inc c) c)) c)))
(defn r06-eq-vec [] (loop [i 0 c 0] (if (< i N) (recur (inc i) (if (= [1 (bit-and i 1)] [1 1]) (inc c) c)) c)))
(defn run []
  (doseq [[l f] [["r00-loop" r00-loop] ["r01-loop-eq" r01-loop-eq] ["r02-loop-numeq" r02-loop-numeq] ["r03-eq-char" r03-eq-char]
                 ["r04-eq-kw" r04-eq-kw] ["r05-eq-str" r05-eq-str] ["r06-eq-vec" r06-eq-vec]]]
    (bench l f)))

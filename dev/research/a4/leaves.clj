;; A4 (c)(d)(e) (u3): the leaves under cohost. Runs on the JVM (clojure -M dev/research/run-jvm.clj
;; dev/research/a4/leaves.clj) and under cohost on jank (bash dev/research/run-cohost-jank.sh
;; dev/research/a4/leaves.clj; JANK_BIN picks the binary). The checks print first; each must read
;; the JVM's value.
(defn nums [n] (vec (map (fn [i] (mod (* i 7919) n)) (range n))))
(def xs (nums 100000))
(def s1e6 (apply str (repeat 1000000 \a)))
(def strs (vec (map str (range 10000))))
(defn checks []
  (println "check rest-sort" (pr-str (rest (sort [3 1 2]))))
  (println "check next-sort-by" (pr-str (next (sort-by - [3 1 2]))))
  (println "check sort-empty" (pr-str (sort [])) (pr-str (seq (sort []))))
  (println "check sort-meta" (pr-str (meta (sort (with-meta [2 1] {:m 1})))))
  (println "check sort-eq" (= (sort [3 1 2]) '(1 2 3)) (= (hash (sort [3 1 2])) (hash '(1 2 3))))
  (println "check sort-cmp" (pr-str (sort > [1 3 2])) (pr-str (sort-by count ["ccc" "a" "bb"])))
  (println "check nth-str" (pr-str (nth "abc" 1)) (pr-str (nth "abc" 5 :nf)) (pr-str (nth [1 2] 1)))
  (println "check nth-oob" (pr-str (try (nth "abc" 5) (catch IndexOutOfBoundsException _ :ioobe))))
  (println "check count" (count "abc") (count [1 2 3]) (count nil))
  (println "check str" (pr-str (str)) (pr-str (str nil)) (pr-str (str :k \c 1 nil "s")) (pr-str (apply str ["a" nil 'b 2]))))
(defn run []
  (checks)
  (bench "sort-count-1e5" #(count (sort xs)))
  (bench "sort-by-count-1e5" #(count (sort-by - xs)))
  (bench "sort-reduce-1e5" #(reduce + (sort xs)))
  (bench "sort-nth-50k-of-1e5" #(nth (sort xs) 50000))
  (bench "sort-rest-count-1e5" #(count (rest (sort xs))))
  (bench "string-nth-loop-1e6" #(loop [i 0 n 0] (if (< i 1000000) (recur (inc i) (if (= \a (nth s1e6 i)) (inc n) n)) n)))
  (bench "loop-only-1e6" #(loop [i 0 n 0] (if (< i 1000000) (recur (inc i) (inc n)) n)))
  (bench "apply-str-1e4" #(count (apply str strs)))
  (bench "str-4-args-1e5" #(loop [i 0 n 0] (if (< i 100000) (recur (inc i) (+ n (count (str "k" i ":" \v)))) n)))
  (bench "vec2-build-2e6" #(loop [i 0 n 0] (if (< i 2000000) (recur (inc i) (+ n (count [i i]))) n)))
  (bench "vec2-nth-count-1e6" #(let [v [7 1]] (loop [i 0 n 0] (if (< i 1000000) (recur (inc i) (+ n (nth v 1) (count v))) n)))))

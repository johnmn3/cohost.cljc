;; A6-4: the pipeline program's per-operation candidates, 1e6 steps each (cohost: run-cohost-jank.sh; plain jank: the .jank twin via run-jank.sh aot)
(def N 1000000)
(def m0 {:n 0 :a {:b 5} :xs {1 {:v 1} 2 {:v 4}}})
(defn q00-loop [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (bit-and i 15))) a)))
(defn q01-assoc-kw [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (count (assoc m0 :n i)))) a)))
(defn q02-update-in-fnil [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (count (update-in m0 [:a :b] (fnil + 0) i)))) a)))
(defn q03-assoc-in [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (count (assoc-in m0 [:xs (bit-and i 31)] {:v i})))) a)))
(defn q04-get-in [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (get-in m0 [:a :b]))) a)))
(defn q05-mod [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (mod i 32))) a)))
(defn q06-fnil-call [] (let [f (fnil + 0)] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (f nil i))) a))))
(defn q07-reduce-range [] (reduce (fn [a i] (+ a (bit-and i 15))) 0 (range N)))
(defn q08-map-literal [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (count {:v (* i i)}))) a)))
(defn q09-thread-step [] (reduce (fn [m i] (-> m (assoc :n i) (update-in [:a :b] (fnil + 0) i) (assoc-in [:xs (mod i 32)] {:v (* i i)}))) {} (range N)))
(defn q10-get-kw [] (loop [i 0 a 0] (if (< i N) (recur (inc i) (+ a (get m0 :n))) a)))
(defn run []
  (doseq [[l f] [["q00-loop" q00-loop] ["q01-assoc-kw" q01-assoc-kw] ["q02-update-in-fnil" q02-update-in-fnil] ["q03-assoc-in" q03-assoc-in]
                 ["q04-get-in" q04-get-in] ["q05-mod" q05-mod] ["q06-fnil-call" q06-fnil-call] ["q07-reduce-range" q07-reduce-range]
                 ["q08-map-literal" q08-map-literal] ["q09-thread-step" q09-thread-step] ["q10-get-kw" q10-get-kw]]]
    (bench l f)))

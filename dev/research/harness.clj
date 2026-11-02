;; dev/research: the JVM twin of harness.jank. Same protocol: warm until 2 s
;; have passed (at least once), then 5 timed calls, median-ns the middle one;
;; results folded into a volatile sink. Run: clojure -M dev/research/run-jvm.clj <probe.clj>
(def __sink (volatile! 0))
(defn __eat [r] (vreset! __sink (+ @__sink (if (number? r) r (count (str r))))) r)
(defn __time-ns [f]
  (let [t0 (System/nanoTime)
        r (f)
        t1 (System/nanoTime)]
    (__eat r)
    [(- t1 t0) r]))
(defn bench [label f]
  (let [t0 (System/nanoTime)]
    (loop [n 1]
      (f)
      (when (< (- (System/nanoTime) t0) 2000000000)
        (recur (inc n)))))
  (let [rs (vec (repeatedly 5 #(__time-ns f)))
        ns (sort (map first rs))]
    (println label "runs-ns" (apply str (interpose " " (map first rs)))
             "median-ns" (nth (vec ns) 2) "result" (pr-str (second (last rs))))))

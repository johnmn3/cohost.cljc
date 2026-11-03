;; A7-2 (u3): bench:jank's walk-bound cases (cohost.bench, same N and expressions) plus
;; bench:programs' lazy-pipeline at 1e6, under cohost. Run: bash dev/research/run-cohost-jank.sh
;; dev/research/a7/walks.clj (JANK_BIN picks the binary); JVM: clojure -M dev/research/run-jvm.clj.
(defn run []
  (bench "transduce-1e5" #(transduce (comp (map inc) (filter odd?)) + (range 100000)))
  (bench "partition-3-1e5" #(count (partition 3 (range 100000))))
  (bench "distinct-mapcat-5e4" #(count (distinct (mapcat (fn [i] [i (inc i)]) (range 50000)))))
  (bench "frequencies-1e5" #(count (frequencies (map (fn [i] (mod i 97)) (range 100000)))))
  (bench "group-by-1e5" #(count (group-by (fn [i] (mod i 31)) (range 100000))))
  (bench "into-map-1e5" #(count (into [] (map inc) (range 100000))))
  (bench "lazy-pipeline-1e6" #(count (filter odd? (map inc (range 1000000))))))

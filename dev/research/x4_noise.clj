;; X4-1: the bench:jank cases whose stock side read slower on max/x4 than on
;; max/main in one 3-round A/B (str-build, distinct, partition, frequencies),
;; verbatim from src/cohost/bench.cljc at the bench's sizes, timed alone.
(defn str-build [] (count (apply str (map str (range 20000)))))
(defn distinct-case [] (count (distinct (mapcat (fn [i] [i (inc i)]) (range 50000)))))
(defn partition-case [] (count (partition 3 (range 100000))))
(defn frequencies-case [] (count (frequencies (map (fn [i] (mod i 97)) (range 100000)))))
(defn run []
  (bench "str-build-2e4" str-build)
  (bench "distinct-5e4" distinct-case)
  (bench "partition-1e5" partition-case)
  (bench "frequencies-1e5" frequencies-case))

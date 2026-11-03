;; A7-2: lazy-pipeline alone (bench:programs' expression at 1e6), for a focused A/B.
(defn run [] (bench "lazy-pipeline-1e6" #(count (filter odd? (map inc (range 1000000))))))

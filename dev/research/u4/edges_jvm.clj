;; the JVM reference for dev/research/u4/edges.sh: clojure -M dev/research/u4/edges_jvm.clj
(load-file "dev/research/u4/num_edges_cohost.clj")
(doseq [r (run)] (println (pr-str r)))

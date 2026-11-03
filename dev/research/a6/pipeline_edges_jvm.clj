(def rows (read-string (str "[" (slurp "dev/research/a6/pipeline_edges.clj") "]")))
(eval (first rows))
(doseq [r (rest rows)] (println (pr-str (eval r))))

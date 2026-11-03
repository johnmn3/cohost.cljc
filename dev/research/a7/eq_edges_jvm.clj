(def rows (read-string (str "[" (slurp "dev/research/a7/eq_edges.clj") "]")))
(eval (first rows))
(doseq [r (rest rows)] (println (pr-str (eval r))))

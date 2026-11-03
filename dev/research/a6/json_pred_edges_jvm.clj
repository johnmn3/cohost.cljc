(def rows (read-string (str "[" (slurp "dev/research/a6/json_pred_edges.clj") "]")))
(eval (first rows))
(doseq [r (rest rows)] (println (pr-str (eval r))))

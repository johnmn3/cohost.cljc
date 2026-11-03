(defmacro T [form] `(try (pr-str ~form) (catch Throwable e# (str "EX " (.getSimpleName (class e#))))))
(load-file "dev/research/u4/num_edges_body.clj")

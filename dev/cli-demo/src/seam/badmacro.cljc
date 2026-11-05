(ns seam.badmacro)

(defmacro bad []
  '(this-var-does-not-exist))

(defn use-it []
  (bad))

(defn -main []
  (prn :main-done))

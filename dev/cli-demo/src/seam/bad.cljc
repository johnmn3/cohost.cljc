(ns seam.bad)

(defn boom []
  (this-var-does-not-exist))

(defn -main []
  (try (boom)
       (catch Throwable e (prn :caught (ex-message e))))
  (prn :main-done))

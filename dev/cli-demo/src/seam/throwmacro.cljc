(ns seam.throwmacro)

;; a macro whose BODY throws: the expansion itself fails, and the
;; report owes the call the macroexpansion attribution (check #13)
(defmacro m [x] (throw (ex-info "bad" {})))

(defn use-it [] (m 1))

(defn -main [& args] (prn :main-done))

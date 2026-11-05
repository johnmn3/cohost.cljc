(require 'cohost.memory 'cohost.memory-eve)
(alias 'memory 'cohost.memory)
(def em (memory/make-map []))
(def evec (memory/make-vector [1 2]))
(def elist (memory/make-list (list 1 2)))
(println "eval fn with Eve map body:"
         (try ((eval (list 'fn [] em)) ) (catch Throwable e (str "ERR " (.getMessage e)))))
(println "eval fn with Eve vector body:"
         (try ((eval (list 'fn [] evec))) (catch Throwable e (str "ERR " (.getMessage e)))))
(println "eval fn with Eve list call:"
         (try (eval (memory/make-list (list 'inc 1))) (catch Throwable e (str "ERR " (.getMessage e)))))
(println "eval host fn with Eve map in vector form:"
         (try (eval (list 'fn [] [em])) (catch Throwable e (str "ERR " (.getMessage e)))))
(println :done)

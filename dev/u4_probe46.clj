;; nth-of out-of-range exception type after the portable-throw fix
(require 'cohost.memory 'cohost.memory-eve)
(alias 'memory 'cohost.memory)
(def v (memory/make-vector [10 20 30]))
(println "nth-of ok:" (memory/nth-of v 1))
(println "oob:" (try (memory/nth-of v 9) :no-throw
                     (catch Throwable e (str (type e) " " (.getMessage e)))))
(println :done)

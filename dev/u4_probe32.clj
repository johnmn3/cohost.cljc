(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter 'cohost.memory 'cohost.memory-eve)
(alias 'boot 'cohost.boot)
(def report (boot/boot! {}))
(boot/make-user-ns!)
(try (boot/eval-str "(persistent! (conj! (transient (vector 1 2 3 4 5 6 7)) 8))")
     (catch Throwable e
       (println "ERR" (type e) (.getMessage e))
       (doseq [el (take 10 (.getStackTrace e))] (println "  " el))))
(println :done)

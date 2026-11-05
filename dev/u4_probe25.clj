(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter 'cohost.memory 'cohost.memory-eve)
(alias 'boot 'cohost.boot)
(def report (boot/boot! {:eve-collections false}))
(boot/make-user-ns!)
(try (boot/eval-str "(reduce + (eduction (filter odd?) [1 2 3 4 5]))")
     (catch Throwable e
       (println "ERR" (.getMessage e))
       (doseq [el (take 12 (.getStackTrace e))] (println "  " el))))
(println :done)

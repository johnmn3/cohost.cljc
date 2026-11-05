(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter 'cohost.memory 'cohost.memory-eve)
(alias 'boot 'cohost.boot)
(def report (boot/boot! {}))
(boot/make-user-ns!)
(try (boot/eval-str "(nil? (re-matcher #\"a\" \"abc\"))")
     (catch Throwable e
       (let [c (.getCause e)]
         (println "ERR" (.getMessage e))
         (println "cause:" (type c) (.getMessage c))
         (doseq [el (take 22 (.getStackTrace c))] (println "  " el)))))
(println :done)

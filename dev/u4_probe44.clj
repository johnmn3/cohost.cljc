(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter 'cohost.memory 'cohost.memory-eve)
(alias 'boot 'cohost.boot)
(def report (boot/boot! {}))
(boot/make-user-ns!)
(println "require:" (try (boot/eval-str "(require (quote [clojure.string :as s]))") "ok"
                         (catch Throwable e (str "ERR " (.getMessage e)))))
(try (boot/eval-str "(s/upper-case \"a\")")
     (catch Throwable e (println "upper-case ERR" (.getMessage e))))
(try (boot/eval-str "(nil? (s/index-of \"abc\" \\z))")
     (catch Throwable e
       (let [c (.getCause e)]
         (println "index-of ERR" (.getMessage e))
         (println "cause:" (.getMessage c))
         (doseq [el (take 14 (.getStackTrace c))] (println "  " el)))))
(println :done)

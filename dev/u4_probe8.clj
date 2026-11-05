(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter 'cohost.memory 'cohost.memory-eve)
(alias 'boot 'cohost.boot)
(alias 'memory 'cohost.memory)

(def report (boot/boot! {:eve-collections false}))
(boot/make-user-ns!)
(def defn-fn @(resolve 'cohost.core/defn))
(defn expand [label]
  (println label
           (try (pr-str (apply defn-fn '(defn f1 [x] x) nil 'f1 '([x] x)))
                (catch Throwable e (str "ERR " (.getMessage e))))))
(expand "fill off:")
(memory/set-fill! true)
(expand "fill on:")
(println :done)

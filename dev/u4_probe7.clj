(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter 'cohost.memory 'cohost.memory-eve)
(alias 'boot 'cohost.boot)
(alias 'memory 'cohost.memory)

(defn try-defn [label src]
  (println label
           (try (boot/eval-str src) :ok
                (catch Throwable e (str "ERR " (.getMessage e))))))

(def report (boot/boot! {:eve-collections false}))
(boot/make-user-ns!)
(println "boot errors:" (clojure.core/count (:errors report)))
(try-defn "baseline defn:" "(defn f1 [x] x)")
(memory/set-fill! true)
(try-defn "fill flag only:" "(defn f2 [x] x)")
(memory/set-fill! false)
(memory/install-fill!)
(try-defn "EMPTY swap only:" "(defn f3 [x] x)")
(memory/set-fill! true)
(try-defn "both:" "(defn f4 [x] x)")
(println :done)

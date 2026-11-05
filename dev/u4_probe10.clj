(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter 'cohost.memory 'cohost.memory-eve)
(alias 'boot 'cohost.boot)
(alias 'memory 'cohost.memory)

(def report (boot/boot! {:eve-collections false}))
(boot/make-user-ns!)
(defn ev [label src]
  (println label
           (try (let [v (boot/eval-str src)] (str (pr-str v) " | " (type v)))
                (catch Throwable e (str "ERR " (.getMessage e))))))
(memory/set-fill! true)
(println "host-side fill:" (memory/fill-on?))
(ev "booted fill?:" "(cohost.memory/fill-on?)")
(ev "RT-vector direct:" "(cohost.shim/RT-vector 1 2)")
(ev "LazilyPV-createOwning:" "(cohost.shim/LazilyPersistentVector-createOwning (object-array [1 2]))")
(ev "PersistentVector-create:" "(cohost.shim/PersistentVector-create (list 1 2))")
(ev "vector 1 2:" "(vector 1 2)")
(ev "literal in code:" "(let [a 1 b 2] [a b])")
(println :done)

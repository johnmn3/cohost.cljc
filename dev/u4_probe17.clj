(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter 'cohost.memory 'cohost.memory-eve)
(alias 'boot 'cohost.boot)
(alias 'memory 'cohost.memory)

(def report (boot/boot! {:eve-collections false}))
(boot/make-user-ns!)
(def defn-fn @(resolve 'cohost.core/defn))
(defn expand [label]
  (let [ex (apply defn-fn '(defn hm ([] {})) nil 'hm '(([] {})))]
    (println label (pr-str ex))
    (println "  maps inside:" (pr-str (map type (filter map? (tree-seq coll? seq ex)))))))
(expand "fill off:")
(memory/set-fill! true)
(expand "fill on:")
(println :done)

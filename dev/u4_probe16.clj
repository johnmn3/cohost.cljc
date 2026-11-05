(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter 'cohost.memory 'cohost.memory-eve 'cohost.rewrite)
(alias 'boot 'cohost.boot)
(alias 'memory 'cohost.memory)
(alias 'rw 'cohost.rewrite)

(def report (boot/boot! {:eve-collections false}))
(boot/make-user-ns!)
(def ctx (boot/make-ctx))
(doseq [src ["(defn hm ([] {}))" "(defn sq ([] ()))" "(defn vv ([] [1 2]))" "[] {} () [1] {:a 1} #{1}"]]
  (println src "=>")
  (println "  " (pr-str (rw/rewrite ctx (read-string src)))))
(println :done)

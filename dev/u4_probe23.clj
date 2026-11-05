(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter 'cohost.memory 'cohost.memory-eve 'cohost.rewrite)
(alias 'boot 'cohost.boot)
(alias 'rw 'cohost.rewrite)
(def report (boot/boot! {:eve-collections false}))
(boot/make-user-ns!)
(def ctx (boot/make-ctx))
(doseq [src ["(fn [x m] (. x (withMeta m)))"
             "(fn [^clojure.lang.IObj x m] (. x (withMeta m)))"
             "(fn [x] (. x (meta)))"
             "(fn [x] (. x (count)))"
             "(fn [x k] (. x (entryAt k)))"]]
  (println src "=>")
  (println "  " (pr-str (rw/rewrite ctx (read-string src)))))
(println :done)

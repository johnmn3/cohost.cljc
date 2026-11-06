(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter 'cohost.memory 'cohost.memory-eve 'cohost.rewrite)
(alias 'boot 'cohost.boot)
(alias 'rw 'cohost.rewrite)
(def report (boot/boot! {:eve-collections false}))
(boot/make-user-ns!)
(def ctx (boot/make-ctx))
(doseq [src ["(nil? (re-matcher #\"a\" \"abc\"))"
             "(nil? (s/index-of \"abc\" \\z))"]]
  (println src "=>")
  (println "  " (pr-str (rw/rewrite ctx (read-string src)))))
(println :done)

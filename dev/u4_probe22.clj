(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter 'cohost.memory 'cohost.memory-eve)
(alias 'boot 'cohost.boot)
(alias 'memory 'cohost.memory)

(def report (boot/boot! {}))
(boot/make-user-ns!)
(defn ev [label src]
  (println label
           (try (let [v (boot/eval-str src)] (str (pr-str v) " | " (type v)))
                (catch Throwable e (str "ERR " (.getMessage e))))))
(ev "shim -withMeta direct:" "(type (cohost.shim/-withMeta (hash-map :a 1) {:m 2}))")
(ev "shim -withMeta meta:" "(meta (cohost.shim/-withMeta (hash-map :a 1) {:m 2}))")
(ev "core with-meta type:" "(type (with-meta (hash-map :a 1) {:m 2}))")
(ev "RT-withMeta?:" "(type (clojure.lang.RT/withMeta (hash-map :a 1) {:m 2}))")
(def w (boot/eval-str "(with-meta (hash-map :a 1) {:m 2})"))
(println "host-side type of booted with-meta result:" (type w) "meta:" (meta w))
(println :done)

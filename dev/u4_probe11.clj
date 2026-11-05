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
(ev "list fdecl:" "(list (quote ([x] x)))")
(ev "cons fn:" "(cons (quote fn) (list (quote ([x] x))))")
(ev "with-meta cons:" "(with-meta (cons (quote fn) (list (quote ([x] x)))) {:rettag nil})")
(ev "sigs:" "(sigs (quote ([x] x)))")
(ev "butlast:" "(butlast (quote (a b c)))")
(ev "full expansion:" "(list (quote def) (quote f1) (with-meta (cons (quote fn) (list (quote ([x] x)))) {:rettag nil}))")
(println :done)

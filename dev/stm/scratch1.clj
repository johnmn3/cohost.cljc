;; scratch1: which transaction machinery runs on the JVM seat?
(require 'cohost.hosts.jvm 'cohost.boot 'cohost.policy 'cohost.adapter)
(alias 'boot 'cohost.boot)
(alias 'host 'cohost.adapter)

(def report (boot/boot! {}))
(boot/make-user-ns!)

(defn ev [label src]
  (let [[ok v] (host/try-all (fn [] [true (boot/eval-str src {:session? false})])
                             (fn [e] [false (str "ERR " (host/error-message e))]))]
    (println label "=>" (pr-str v))))

(ev "setup" "(do (def r1 (ref 10)) (def r2 (ref 100)) (def a1 (agent [])) :ok)")
(ev "abort rolls back both refs?"
    "(do (try (dosync (ref-set r1 11) (ref-set r2 99) (throw (Exception. \"boom\"))) (catch Exception e :caught)) [@r1 @r2])")
(ev "send in aborting tx fires?"
    "(do (try (dosync (send a1 conj :should-not-fire) (throw (Exception. \"boom\"))) (catch Exception e :caught)) (Thread/sleep 100) @a1)")
(ev "send in committing tx fires once?"
    "(do (dosync (send a1 conj :fired)) (Thread/sleep 100) @a1)")
(ev "isRunning inside tx" "(dosync (clojure.lang.LockingTransaction/isRunning))")
(ev "snapshot: read r1, other thread commits, read again"
    "(do (def seen (atom nil)) (def t (Thread. (fn [] (dosync (let [x @r1] (Thread/sleep 60) (reset! seen [x @r1])))))) (.start t) (Thread/sleep 20) (dosync (ref-set r1 555)) (.join t) [@seen @r1])")
(println "SCRATCH1 DONE")

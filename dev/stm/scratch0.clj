;; scratch: what serves ref/dosync on this seat today?
(require 'cohost.hosts.jvm 'cohost.boot 'cohost.policy 'cohost.adapter)
(alias 'boot 'cohost.boot)
(alias 'host 'cohost.adapter)

(def report (boot/boot! {}))
(boot/print-report report)
(boot/make-user-ns!)

(defn ev [label src]
  (let [[ok v] (host/try-all (fn [] [true (boot/eval-str src {:session? false})])
                             (fn [e] [false (str "ERR " (host/error-message e))]))]
    (println label "=>" (pr-str v))))

(ev "boot errors" (str (:error report)))
(ev "ref type" "(do (def r1 (ref 41)) (str (type r1)))")
(ev "ref deref" "@r1")
(ev "dosync alter" "(do (dosync (alter r1 inc)) @r1)")
(ev "alter outside tx" "(try (alter r1 inc) :no-throw (catch IllegalStateException e :threw))")
(ev "ref is host ref?" "(instance? clojure.lang.Ref r1)")
(ev "commute" "(do (dosync (commute r1 + 5)) @r1)")
(ev "agent type" "(do (def a1 (agent 0)) (str (type a1)))")
(ev "send in dosync fires when?"
    "(do (def a2 (agent [])) (dosync (send a2 conj :in-tx) (ref-set r1 99)) (Thread/sleep 50) [@a2 @r1])")
(ev "sync runs" "(do (sync nil (ref-set r1 7)) @r1)")
(ev "history count" "(ref-history-count r1)")
(println "SCRATCH0 DONE")

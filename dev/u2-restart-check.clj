(require 'cohost.hosts.jvm 'cohost.thread)
(alias 't 'cohost.thread)
(let [ag (t/new-agent 0 {:error-mode :fail})]
  (t/dispatch! ag (fn [s] (throw (ex-info "boom" {}))) [] (t/send-executor))
  (t/await-latch (t/await1 ag) 5000)
  (println "after fail: error?" (some? (t/agent-error ag)) "state" @(:state ag))
  (t/restart-agent ag 10 true)
  (println "restarted, failed now:" (t/agent-error ag))
  (t/dispatch! ag (fn [s] (inc s)) [] (t/send-executor))
  (let [l (t/await1 ag)
        opened (t/await-latch l 5000)]
    (println "latch opened:" opened "state:" @(:state ag) "errors:" (count (t/agent-errors ag))))
  (println "RESTART-CHECK-DONE"))
(System/exit 0)

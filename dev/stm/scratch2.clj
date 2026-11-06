(require 'cohost.hosts.auto)
(require '[cohost.thread :as thread])

(def outcome (atom nil))
(def t (thread/spawn
        (fn [] (reset! outcome
                       (try (thread/await-latch (thread/new-latch 1)) :returned
                            (catch Exception e
                              (if (thread/interrupted-ex? e) :interrupted (str "other: " (type e) " " e))))))))
(Thread/sleep 100)
(println "interrupt-thread! returned:" (thread/interrupt-thread! t))
(println "cancel flag on t:" @(:cancel t))
(println "join:" (thread/join-thread t 2000))
(println "outcome:" @outcome)
(System/exit 0)

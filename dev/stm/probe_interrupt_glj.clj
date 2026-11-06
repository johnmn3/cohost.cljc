;; Go chase lane: the interrupt contract's two compute cases on
;; glojure, beyond probe_tx's latch cases (which it passes: a
;; blocked latch wait wakes with the interrupt exception; a tx
;; body blocked mid-transaction abandons and writes nothing).
;;
;; The STM design is cooperative on every seat: an interrupt is
;; a flag, observed at blocking points and explicit polls. This
;; probe measures the two pure-compute shapes on goroutines:
;;  (a) a compute loop that POLLS (thread/interrupted?) exits
;;      early when another goroutine interrupts it;
;;  (b) a compute loop that NEVER polls runs to completion
;;      despite the interrupt -- the flag is set, never observed.
;;      That is the design's standing distance, identical to the
;;      JVM's (Thread.interrupt does not stop compute either);
;;      Go has no goroutine kill, and the layer does not fake one.
(require 'cohost.hosts.auto)
(require '[cohost.hosts.glj :as glj-host])
(glj-host/install!)
(require '[cohost.thread :as thread])

(def checks (atom 0))
(def failures (atom []))
(defn check [label expected actual]
  (swap! checks inc)
  (if (= expected actual)
    (println "PASS" label "=>" (pr-str actual))
    (do (swap! failures conj label)
        (println "FAIL" label "expected" (pr-str expected) "got" (pr-str actual)))))

;; (a) polling compute loop: exits at its next poll after interrupt
(def polls (atom 0))
(def poller-outcome (atom nil))
(def t-poll
  (thread/spawn
   (fn [] (reset! poller-outcome
                  (try (loop [i 0]
                         (if (thread/interrupted?)
                           :interrupted
                           (do (swap! polls inc)
                               (when (< i 100000000) (recur (inc i))))))
                       (catch Exception e (str "other: " e)))))))
(Thread/sleep 100)
(thread/interrupt-thread! t-poll)
(thread/join-thread t-poll)
(check "polling compute goroutine stops at its next poll" :interrupted @poller-outcome)
(check "polling loop stopped far short of its bound" true (< @polls 100000000))

;; (b) non-polling compute loop: the interrupt is never observed
(def compute-done (atom 0))
(def t-comp
  (thread/spawn
   (fn [] (loop [i 0 acc 0]
            (if (< i 3000000)
              (recur (inc i) (+ acc i))
              (reset! compute-done acc))))))
(Thread/sleep 50)
(def flag-set (thread/interrupt-thread! t-comp))
(thread/join-thread t-comp)
(check "interrupt-thread! accepted the layer thread" true flag-set)
(check "non-polling compute goroutine ran to completion (flag unobserved)"
       4499998500000 @compute-done)

(println (if (empty? @failures)
           (str "PROBE-INTERRUPT-GLJ: ALL " @checks " CHECKS PASS")
           (str "PROBE-INTERRUPT-GLJ: " (count @failures) " FAILURES: " @failures)))
(System/exit (if (empty? @failures) 0 1))

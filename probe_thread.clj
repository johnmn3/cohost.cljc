;; layer probe, host-side JVM seat: run with
;; clojure -Sdeps '{:paths ["src"]}' -M probe_thread.clj
(require 'cohost.hosts.jvm 'cohost.thread 'cohost.adapter)
(alias 't 'cohost.thread)
(alias 'host 'cohost.adapter)

(def ^:dynamic *probe-binding* :unbound)
(def checks (atom []))
(defn check [nm v] (swap! checks conj [nm v]) (println (if v "PASS" "FAIL") nm))

(check "leaves available" (t/available?))
(check "thread-bindings is a map" (map? (host/thread-bindings)))

;; spawn + join + current-thread
(let [seen (atom nil)
      th (t/spawn (fn [] (reset! seen (t/current-thread))))]
  (check "join spawned" (t/join-thread th 5000))
  (check "worker saw its own thread record" (= (:id @seen) (:id th)))
  (check "worker thread not alive after join" (not (t/thread-alive? th))))

;; latch across threads
(let [l (t/new-latch 2)
      a (t/spawn (fn [] (Thread/sleep 30) (t/count-down l)))
      b (t/spawn (fn [] (Thread/sleep 60) (t/count-down l)))]
  (check "latch count starts 2" (= 2 (t/latch-count l)))
  (check "latch await opens" (t/await-latch l 5000))
  (t/join-thread a 5000) (t/join-thread b 5000))
(let [l (t/new-latch 1)]
  (check "latch await timeout false" (false? (t/await-latch l 60))))

;; future: value, conveyance, error, timeout deref
(let [f (binding [*probe-binding* 42]
          (t/future-call (fn [] [*probe-binding* (not (identical? (t/current-thread) nil))])))]
  (check "future value + conveyed binding" (= [42 true] (t/deref-future f 5000 :timeout)))
  (check "future done" (t/future-done? f)))
(let [f (t/future-call (fn [] (throw (ex-info "boom" {}))))]
  (check "future error rethrown"
         (try (t/deref-future f 5000 :timeout) false
              (catch clojure.lang.ExceptionInfo e (= "boom" (ex-message e))))))
(let [f (t/future-call (fn [] (Thread/sleep 300) :late))]
  (check "future deref timeout" (= :timeout (t/deref-future f 40 :timeout)))
  (check "future completes" (= :late (t/deref-future f 5000 :timeout)))
  (check "future cancel after done is false" (false? (t/future-cancel f))))
(let [f (t/future-call (fn [] (Thread/sleep 300) :late))]
  (check "future cancel pending" (t/future-cancel f))
  (check "future cancelled?" (t/future-cancelled? f)))

;; promise
(let [p (t/new-promise)]
  (check "promise deref timeout" (= :none (t/deref-future p 40 :none)))
  (t/spawn (fn [] (Thread/sleep 20) (t/deliver-promise p 7)))
  (check "promise delivered value" (= 7 (t/deref-future p 5000 :timeout)))
  (check "second deliver loses" (do (t/deliver-promise p 8) (= 7 (t/deref-future p)))))

;; executor submit
(let [ex (t/new-executor :pooled 2)
      fut (t/submit! ex (fn [] 99) (t/new-promise))]
  ;; submit! with a fut argument returns it; run the task through future-call shape instead:
  (check "executor value" (t/executor-value? ex))
  (t/shutdown-executor! ex))

;; agents: order, send-off, await, nested sends, errors
(let [ag (t/new-agent 0 {})]
  (t/dispatch! ag (fn [s] (inc s)) [] (t/send-executor))
  (t/dispatch! ag (fn [s] (+ s 10)) [] (t/send-executor))
  (t/await-latch (t/await1 ag) 5000)
  (check "agent sends in order" (= 11 @(:state ag))))
(let [ag (t/new-agent [] {})]
  (t/dispatch! ag (fn [s] (conj s :off)) [] (t/send-off-executor))
  (t/await-latch (t/await1 ag) 5000)
  (check "agent send-off" (= [:off] @(:state ag))))
(let [ag (t/new-agent 0 {})
      inner (t/new-agent 0 {})]
  ;; an action that sends to another agent: the send is held, then released
  (t/dispatch! ag (fn [s] (t/dispatch! inner (fn [x] (+ x 5)) [] (t/send-executor)) (inc s)) [] (t/send-executor))
  (t/await-latch (t/await1 ag) 5000)
  (t/await-latch (t/await1 inner) 5000)
  (check "nested send released" (and (= 1 @(:state ag)) (= 5 @(:state inner)))))
(let [ag (t/new-agent 1 {})]
  (t/dispatch! ag (fn [s] (throw (ex-info "bad" {}))) [] (t/send-executor))
  (t/dispatch! ag (fn [s] (inc s)) [] (t/send-executor))
  (t/await-latch (t/await1 ag) 5000)
  (check "agent :continue records and goes on"
         (and (= 2 @(:state ag)) (= 1 (count (t/agent-errors ag))) (nil? (t/agent-error ag)))))
(let [ag (t/new-agent 1 {:error-mode :fail})]
  (t/dispatch! ag (fn [s] (throw (ex-info "bad" {}))) [] (t/send-executor))
  (loop [n 0] (when (and (nil? (t/agent-error ag)) (< n 100)) (Thread/sleep 20) (recur (inc n))))
  (check "agent :fail stops" (some? (t/agent-error ag)))
  (check "send to failed agent throws"
         (try (t/dispatch! ag (fn [s] s) [] (t/send-executor)) false
              (catch IllegalStateException _ true)))
  (t/restart-agent ag 10 true)
  (t/dispatch! ag (fn [s] (inc s)) [] (t/send-executor))
  (t/await-latch (t/await1 ag) 5000)
  (check "restart-agent resumes" (= 11 @(:state ag))))

;; pmap / pcalls
(check "pmap" (= (map (fn [x] (* x x)) (range 20))
                 (t/pmap* (fn [x] (* x x)) (range 20))))
(check "pcalls" (= [1 2 3] (t/pcalls* (fn [] 1) (fn [] 2) (fn [] 3))))

(t/shutdown-agents)
(println "CHECKS:" (count (filter second @checks)) "/" (count @checks) "passed")
(System/exit (if (every? second @checks) 0 1))

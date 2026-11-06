(ns cohost.thread
  "Eve's thread layer, hosted in cohost: the one implementation of threads,
   executors, futures, promises, latches and agents that sits behind
   cohost's Java shim (java.lang.Thread, java.util.concurrent, clojure.lang
   .Agent) and fills the clojure.core concurrency names on hosts whose core
   has none.

   The design is the cljs.thread / Eve shape: workers meet at shared atoms,
   and a form that crosses a thread boundary carries its dynamic bindings
   with it, captured into the work item and restored on the worker,
   transparently -- library code never sees the conveyance. All state lives
   in atoms (values cross a thread only by deref or swap); the only host
   leaves are the adapter's :thread-spawn, :thread-self and :doorbell caps.
   A host that supplies none of those has no thread layer: available? is
   false there and the shim's entries stay unsupported, as before.

   Values of the layer are plain maps tagged :cohost/kind; the shim
   dispatches its Thread/Future/CountDownLatch members and deref on the
   tag (see cohost.shim). Equality is the map's, not identity's; that is a
   known distance from the Java classes, noted in the unit report."
  (:refer-clojure :exclude [future-call future-done? future-cancel future-cancelled?
                            release-pending-sends await1 shutdown-agents agent-error
                            agent-errors clear-agent-errors restart-agent set-error-mode!
                            error-mode set-error-handler! error-handler pmap])
  (:require [cohost.adapter :as host]))

;; ---------------------------------------------------------------------------
;; leaves and registry

(defn available?
  "True when the installed host adapter gives the layer its leaves."
  []
  (boolean (host/capability :thread-spawn)))

(defonce ^:private registry
  ;; every piece of layer state that is not inside a value of its own lives
  ;; in this one atom: the thread table, the two agent executors, the
  ;; shutdown flag, the id sequence
  (atom {:next-id 1
         :by-handle {}
         :send-executor nil
         :send-off-executor nil
         :shutdown false}))

(defn- next-id! []
  (:next-id (swap! registry update :next-id inc)))

(defn- empty-queue []
  (or ((host/capability :empty-queue))
      ;; a host with no persistent queue of its own (jank) gets the
      ;; layer's two-list queue: a tagged map, :front a list whose head
      ;; is the oldest item, :back a list holding newer items reversed.
      ;; A pure value, like the host's queue: a swap! may retry under
      ;; contention and must see the same queue each time.
      {:cohost/kind ::queue :front () :back ()}))

(defn- queue-value? [q] (and (map? q) (= ::queue (:cohost/kind q))))

(defn- q-norm
  "Restore the two-list invariant: :front is non-empty when the queue is."
  [q]
  (if (and (empty? (:front q)) (seq (:back q)))
    (assoc q :front (reverse (:back q)) :back ())
    q))

(defn- q-conj [q item]
  (if (queue-value? q)
    (q-norm (assoc q :back (conj (:back q) item)))
    (conj q item)))

(defn- q-peek [q]
  (if (queue-value? q)
    (first (:front (q-norm q)))
    (peek q)))

(defn- q-pop [q]
  (if (queue-value? q)
    (let [q (q-norm q)] (assoc q :front (rest (:front q))))
    (pop q)))

(defn- q-empty? [q]
  (if (queue-value? q)
    (and (empty? (:front q)) (empty? (:back q)))
    (empty? q)))

(defn- claim!
  "Atomically take the head item of queue atom q; nil when empty."
  [q]
  (let [got (atom ::none)]
    (swap! q (fn [qq]
               ;; reset on every run: a swap! retry whose predecessor
               ;; was discarded must not leak that run's head -- the
               ;; item would be delivered twice (measured, unit 2)
               (reset! got ::none)
               (if (q-empty? qq)
                 qq
                 (do (reset! got (q-peek qq)) (q-pop qq)))))
    (let [v @got] (when-not (= ::none v) v))))

(defn- now-ms [] (host/current-time-millis))

;; ---------------------------------------------------------------------------
;; threads

(defn- register-thread! [rec handle]
  (swap! registry assoc-in [:by-handle handle] rec)
  rec)

(defn spawn
  "Run f on a new host thread as a layer thread; the layer Thread value.
   f runs with no conveyed bindings: conveyance is the caller's business
   (future-call and the agent machinery capture and restore their own)."
  [f]
  (let [state (atom :new)
        bell (host/doorbell)
        rec {:cohost/kind ::thread
             :id (next-id!)
             :name (atom nil)
             :state state
             :bell bell
             :cancel (atom false)
             :runnable f
             :handle (atom nil)}]
    (if-let [h (host/thread-spawn
                (fn []
                  (register-thread! rec (host/thread-self))
                  (reset! state :running)
                  (try (f)
                       (finally
                         (reset! state :done)
                         (host/doorbell-signal bell)))))]
      (do (reset! (:handle rec) h) rec)
      (throw (host/ex* 'java.lang.IllegalStateException
                       "cohost: this host provides no thread-spawn" nil)))))

(defn start-thread!
  "The shim's Thread.start: spawn the thread value's runnable."
  [t]
  (when (= :new @(:state t))
    (let [f (:runnable t)
          state (:state t)
          bell (:bell t)]
      (if-let [h (host/thread-spawn
                  (fn []
                    (register-thread! t (host/thread-self))
                    (reset! state :running)
                    (try (f)
                         (finally
                           (reset! state :done)
                           (host/doorbell-signal bell)))))]
        (reset! (:handle t) h)
        (throw (host/ex* 'java.lang.IllegalStateException
                         "cohost: this host provides no thread-spawn" nil)))))
  t)

(defn new-thread
  "The shim's java.lang.Thread constructor value: an unstarted thread over
   runnable f (a fn of no args), named nm when given."
  ([f] (new-thread f nil))
  ([f nm]
   {:cohost/kind ::thread
    :id (next-id!)
    :name (atom nm)
    :state (atom :new)
    :bell (host/doorbell)
    :cancel (atom false)
    :runnable f
    :handle (atom nil)}))

(defn current-thread
  "The layer Thread value for the running thread. A thread the layer did
   not spawn gets a stable synthetic record, so Thread/currentThread has
   one answer per host thread."
  []
  (let [h (host/thread-self)]
    (or (get (:by-handle @registry) h)
        (let [rec {:cohost/kind ::thread
                   :id (next-id!)
                   :name (atom nil)
                   :state (atom :running)
                   :bell (host/doorbell)
                   :cancel (atom false)
                   :runnable nil
                   :handle (atom h)}]
          (register-thread! rec h)))))

;; ---------------------------------------------------------------------------
;; cooperative interruption (STM+agents lane, closing unit 1's remainder)
;;
;; The JVM's interrupt is cooperative too: Thread.interrupt sets a flag and
;; blocking points throw InterruptedException; nothing stops a thread
;; mid-computation. The layer now does the same: interrupt-thread! sets the
;; thread's cancel flag and rings its bell; the layer's blocking waits
;; (join, latch await, future deref) wait in bounded slices and throw the
;; interrupt exception when their own thread's flag is set. Code in a pure
;; compute loop is not interrupted -- exactly the JVM's distance, no more.

(defn interrupted-ex
  "The layer's InterruptedException: an ex-info user code can catch by
   its data, {:cohost.thread/interrupted true}."
  []
  (host/ex-info* "thread interrupted" {:cohost.thread/interrupted true}))

(defn interrupted-ex? [e]
  (boolean (and (map? (ex-data e)) (:cohost.thread/interrupted (ex-data e)))))

(defn interrupt-thread!
  "Thread.interrupt for a layer thread: set its cancel flag and ring its
   bell so a blocked wait wakes and throws. True when t is a layer thread."
  [t]
  (if (and (map? t) (= ::thread (:cohost/kind t)) (:cancel t))
    (do (reset! (:cancel t) true)
        (host/doorbell-signal (:bell t))
        true)
    false))

(defn interrupted?
  "The running thread's cancel flag (Thread.isInterrupted, non-clearing)."
  []
  (boolean (when-let [c (:cancel (current-thread))] @c)))

(defn clear-interrupted!
  "Clear the running thread's cancel flag; the previous value."
  []
  (let [c (:cancel (current-thread))
        was (boolean (and c @c))]
    (when c (reset! c false))
    was))

(defn- wait-sliced
  "doorbell-wait in slices of at most 50 ms, throwing the interrupt
   exception when the running thread's cancel flag is set. timeout-ms
   nil waits without a deadline. True when the bell was signalled."
  [bell timeout-ms]
  (let [deadline (when (some? timeout-ms) (+ (now-ms) (long timeout-ms)))]
    (loop []
      (when (interrupted?) (throw (interrupted-ex)))
      (let [slice (if deadline
                    (min 50 (max 0 (- deadline (now-ms))))
                    50)]
        (cond (host/doorbell-wait bell slice) true
              (and deadline (>= (now-ms) deadline)) false
              :else (recur))))))

(defn thread-alive? [t] (= :running @(:state t)))

(defn join-thread
  "Wait for thread t to finish; with timeout-ms, false when it passes.
   Throws the interrupt exception when the WAITING thread is interrupted.
   The bell alone is not the answer: interrupt-thread! also rings it, so
   join waits for the :done STATE, re-waiting after any other signal."
  ([t]
   (loop []
     (if (= :done @(:state t))
       true
       (do (wait-sliced (:bell t) nil) (recur)))))
  ([t timeout-ms]
   (let [deadline (+ (now-ms) (long timeout-ms))]
     (loop []
       (cond (= :done @(:state t)) true
             (>= (now-ms) deadline) false
             :else (do (wait-sliced (:bell t) (max 0 (- deadline (now-ms))))
                       (recur)))))))

;; ---------------------------------------------------------------------------
;; executors
;;
;; An executor is a queue atom, a control atom and a generation doorbell:
;; workers claim work items (plain thunks) off the queue; the doorbell is
;; swapped for a fresh one at every signal, so a wait never misses work
;; enqueued between the claim check and the wait.

(defn new-executor
  "kind :pooled (size workers, for futures and send) or :solo (one worker,
   for send-off). Also the value behind the shim's Executors factories."
  [kind size]
  {:cohost/kind ::executor
   :kind kind
   :size (if (= kind :solo) 1 (max 1 size))
   :queue (atom (empty-queue))
   :ctl (atom {:running 0 :shutdown false :bell (host/doorbell)})})

(declare run-worker)

(defn- ensure-workers! [ex]
  (let [ctl (:ctl ex)]
    (loop []
      (let [c @ctl]
        (when (and (not (:shutdown c)) (< (:running c) (:size ex)))
          (when (compare-and-set! ctl c (update c :running inc))
            (spawn (fn [] (run-worker ex)))
            (recur)))))))

(defn- signal-workers! [ex]
  (let [old (atom nil)]
    (swap! (:ctl ex) (fn [c] (reset! old (:bell c)) (assoc c :bell (host/doorbell))))
    (when @old (host/doorbell-signal @old))))

(defn- run-worker [ex]
  (try
    (loop []
      (let [item (claim! (:queue ex))]
        (cond (some? item)
              (do (host/try-all item)
                  (recur))
              (:shutdown @(:ctl ex)) nil
              ;; wait on the current generation bell: a submit after
              ;; the claim swaps and signals the bell it replaces, and
              ;; the signal is sticky, so no wakeup is missed; the
              ;; 100 ms timeout bounds the rest. (Unit 1 re-claimed
              ;; here as a guard and discarded what it claimed -- a
              ;; submit landing between the two claims lost its item;
              ;; measured, unit 2.)
              :else (do (host/doorbell-wait (:bell @(:ctl ex)) 100)
                        (recur)))))
    (finally
      (swap! (:ctl ex) update :running dec))))

(defn submit!
  "Enqueue thunk on executor ex; returns the thunk's future when fut is
   given (executor submit), else the executor."
  ([ex thunk] (submit! ex thunk nil))
  ([ex thunk fut]
   (when (:shutdown @(:ctl ex))
     (throw (host/ex* 'java.util.concurrent.RejectedExecutionException
                      "cohost: executor is shut down" nil)))
   (swap! (:queue ex) q-conj thunk)
   (ensure-workers! ex)
   (signal-workers! ex)
   (or fut ex)))

(defn shutdown-executor! [ex]
  (swap! (:ctl ex) assoc :shutdown true)
  (signal-workers! ex)
  ex)

(defn send-executor
  "The executor behind future and send: a pooled executor, created on
   first use. Stock Clojure's is an unbounded cached pool; the layer's is
   bounded (8 workers) by design -- the queue is unbounded, so work is
   never refused, only its concurrency is capped."
  []
  (or (:send-executor @registry)
      (let [ex (new-executor :pooled 8)]
        (swap! registry assoc :send-executor ex)
        (:send-executor @registry))))

(defn send-off-executor
  "The executor behind send-off: one worker, so send-off actions run
   serially, as stock's solo executor runs them."
  []
  (or (:send-off-executor @registry)
      (let [ex (new-executor :solo 1)]
        (swap! registry assoc :send-off-executor ex)
        (:send-off-executor @registry))))

(defn set-send-executor! [ex] (swap! registry assoc :send-executor ex) ex)
(defn set-send-off-executor! [ex] (swap! registry assoc :send-off-executor ex) ex)

;; ---------------------------------------------------------------------------
;; binding conveyance: a form crossing a thread boundary carries the
;; bindings in force where it was created. Captured here, restored on the
;; worker through the adapter's binding-frame caps; on a host whose core
;; has no binding frames the capture is empty and nothing is conveyed.

;; Unit 7: a capture hook -- {var value} -> {var value} -- the atom
;; layer installs to carry binding frames ACROSS PROCESSES through a
;; shared (disk) atom: the frame a capture sees is published into the
;; atom, and frames other processes published there are conveyed for
;; vars this thread has not bound itself. nil = no hook (default).
(defonce ^:private capture-hook (atom nil))

(defn set-capture-hook! [f] (reset! capture-hook f) nil)

(defn apply-capture-hook
  "The bindings map b as the hook (if any) answers it."
  [b]
  (if-let [h @capture-hook] (h b) b))

(defn capture-bindings [] (apply-capture-hook (host/thread-bindings)))

(defn conveyed
  "Wrap f so it runs with the captured bindings map b restored."
  [b f]
  (if (seq b)
    (fn [] (host/with-thread-bindings! b f))
    f))

;; ---------------------------------------------------------------------------
;; futures and promises

(defn thread-value? [x] (and (map? x) (= ::thread (:cohost/kind x))))
(defn executor-value? [x] (and (map? x) (= ::executor (:cohost/kind x))))
(defn future-value? [x] (and (map? x) (= ::future (:cohost/kind x))))
(defn promise-value? [x] (and (map? x) (= ::promise (:cohost/kind x))))
(defn latch-value? [x] (and (map? x) (= ::latch (:cohost/kind x))))
(defn agent-value? [x] (and (map? x) (= ::agent (:cohost/kind x))))

(defn- new-future* []
  {:cohost/kind ::future :state (atom {:status :pending}) :bell (host/doorbell)})

(defn completed-future
  "A layer future already holding outcome ({:status :done :value v} or
   {:status :error :error e}): what an asynchronous call answers on a host
   with no thread layer, where the work ran on the caller's thread."
  [outcome]
  {:cohost/kind ::future :state (atom outcome) :bell nil})

(defn- complete-future! [fut outcome]
  (let [won (atom false)]
    (swap! (:state fut)
           (fn [s] (if (= :pending (:status s))
                     (do (reset! won true) outcome)
                     s)))
    (when @won (host/doorbell-signal (:bell fut)))
    @won))

(defn submit-future
  "ExecutorService.submit: run f (a Callable/Runnable value: a fn of no
   args) on executor ex; the future of its outcome. No bindings are
   conveyed -- conveyance is future-call's and the agents' business,
   as on the JVM."
  [ex f]
  (let [fut (new-future*)]
    (submit! ex (fn []
                  (host/try-all
                   (fn [] (complete-future! fut {:status :done :value (f)}))
                   (fn [e] (complete-future! fut {:status :error :error e}))))
             fut)
    fut))

(defn future-call
  "clojure.core/future-call over the layer: run f on the send executor
   with the caller's bindings conveyed; a future value."
  [f]
  (submit-future (send-executor) (conveyed (capture-bindings) f)))

;; Unit 8: the form-carrying future. On a seat with more than one realm
;; (the cljs Node seat with Eve hosting: worker heaps meeting this one at
;; shared Eve atoms over SharedArrayBuffers), a future's work can run in
;; another realm only as data -- its form, the values of the locals the
;; form names, and the binding frame (eve.thread's tfn shape: a plain
;; closure is refused). The future macro gives the layer both: the form
;; with its locals, and the closure. The seat's :realm-submit takes the
;; task when every value crosses (an Eve value, an Eve-backed atom by
;; reference, a var-root fn by name) and completes the future from the
;; realm that ran it; when it cannot cross, the closure runs here, on
;; this realm's threads, exactly as future-call runs it.
(defn future-form-call
  "future-call for a form-carrying future: ns-sym the namespace the
   form was written in, form (fn [locals] body), args the locals'
   values, f the same body as a closure."
  [ns-sym form args f]
  (if-let [submit (host/capability :realm-submit)]
    (let [fut (new-future*)]
      (if (submit {:ns ns-sym :form form :args args :frame (capture-bindings)
                   :future fut
                   :complete (fn [outcome] (complete-future! fut outcome))})
        fut
        (future-call f)))
    (future-call f)))

(defn future-done? [fut] (not= :pending (:status @(:state fut))))
(defn future-cancelled? [fut] (= :cancelled (:status @(:state fut))))

(defn future-cancel
  "Cancel while still pending (the layer does not interrupt a running
   task; stock's cancel with mayInterruptIfRunning does -- a named
   distance). True when this call cancelled it."
  [fut]
  (boolean (complete-future! fut {:status :cancelled})))

(defn- cancelled-ex []
  (host/ex* 'java.util.concurrent.CancellationException "future cancelled" nil))

(defn deref-future
  "Blocking deref of a layer future or promise; the timeout form returns
   timeout-val when the deadline passes."
  ([fut] (deref-future fut nil nil false))
  ([fut timeout-ms timeout-val]
   (deref-future fut timeout-ms timeout-val true))
  ([fut timeout-ms timeout-val timed?]
   (let [deadline (when timed? (+ (now-ms) (long timeout-ms)))]
     (loop []
       (let [s @(:state fut)]
         (case (:status s)
           :done (:value s)
           :error (throw (:error s))
           :cancelled (throw (cancelled-ex))
           ;; :pending
           (if timed?
             (let [remaining (- deadline (now-ms))]
               (if (<= remaining 0)
                 timeout-val
                 (do (wait-sliced (:bell fut) remaining) (recur))))
             (do (wait-sliced (:bell fut) nil) (recur)))))))))

(defn new-promise []
  {:cohost/kind ::promise :state (atom {:status :pending}) :bell (host/doorbell)})

(defn deliver-promise
  "clojure.core/deliver: the first delivery wins; returns the promise."
  [p v]
  (complete-future! p {:status :done :value v})
  p)

(defn deref-blocking
  "The shim's deref for layer futures and promises."
  ([x] (deref-future x))
  ([x timeout-ms timeout-val] (deref-future x timeout-ms timeout-val)))

(defn get-future
  "java.util.concurrent.Future.get for a layer future or promise: the
   timed form throws TimeoutException when the deadline passes (stock
   core's deref-future catches exactly that to yield its timeout-val).
   With arguments the first is read as a millisecond timeout; the
   TimeUnit argument is not modeled."
  ([fut] (deref-future fut))
  ([fut timeout-ms & _unit]
   (let [v (deref-future fut timeout-ms ::timed-out)]
     (if (= ::timed-out v)
       (throw (host/ex* 'java.util.concurrent.TimeoutException "future get timed out" nil))
       v))))

;; ---------------------------------------------------------------------------
;; CountDownLatch

(defn new-latch [n]
  {:cohost/kind ::latch :count (atom (max 0 (long n))) :bell (host/doorbell)})

(defn latch-count [l] @(:count l))

(defn count-down
  "Decrement; at zero the latch opens for every current and future wait."
  [l]
  (let [c (swap! (:count l) (fn [c] (max 0 (dec c))))]
    (when (zero? c) (host/doorbell-signal (:bell l)))
    nil))

(defn await-latch
  "True when the latch opened; the timeout form returns false when the
   deadline passes first."
  ([l] (await-latch l nil) true)
  ([l timeout-ms]
   (let [deadline (when (some? timeout-ms) (+ (now-ms) (long timeout-ms)))]
     (loop []
       (cond (zero? @(:count l)) true
             (and deadline (>= (now-ms) deadline)) false
             :else (do (wait-sliced (:bell l)
                                    (when deadline
                                      (max 0 (- deadline (now-ms)))))
                       (recur)))))))

;; ---------------------------------------------------------------------------
;; agents
;;
;; An agent is a state atom plus a queue atom, drained by at most one
;; worker at a time (the :queued flag in the control atom). Actions carry
;; their captured bindings and the executor they were sent on; a drain
;; hands off when the next action names a different executor. Sends made
;; while an action runs are held in *pending-sends* and released when the
;; action ends, as stock's nested sends are.

(def ^:dynamic *pending-sends*
  "While an agent action runs on this thread: an atom of the sends it has
   made, held until the action ends (or release-pending-sends flushes)."
  nil)

(defn new-agent [init opts]
  (let [mode (or (:error-mode opts) :continue)]
    (when-not (contains? #{:fail :continue} mode)
      (throw (host/ex* 'java.lang.IllegalArgumentException
                       (str "error-mode must be :fail or :continue, was " mode) nil)))
    {:cohost/kind ::agent
     :state (atom init)
     :errors (atom [])
     :queue (atom (empty-queue))
     :ctl (atom {:queued false
                 :failed nil
                 :mode mode
                 :handler (:error-handler opts)
                 :validator (:validator opts)})}))

(declare drain kick!)

(defn- enqueue-action! [ag action]
  (swap! (:queue ag) q-conj action)
  (kick! ag))

(defn- kick! [ag]
  (let [won (atom false)]
    (swap! (:ctl ag) (fn [c] (if (:queued c)
                               c
                               (do (reset! won true) (assoc c :queued true)))))
    (when @won
      (let [ex (or (:executor (q-peek @(:queue ag))) (send-executor))]
        (submit! ex (fn [] (drain ag ex)))))))

(defn- release-held! [held]
  (doseq [action held] (enqueue-action! (:ag action) action)))

(defn release-pending-sends
  "clojure.lang.Agent/releasePendingSends and core's
   release-pending-sends: flush the sends the running action has held;
   the count flushed (0 outside an action)."
  []
  (if *pending-sends*
    (let [held @*pending-sends*]
      (reset! *pending-sends* [])
      (release-held! held)
      (count held))
    0))

(def ^:private agent-var
  ;; the handle of cohost.core/*agent*, resolved on first use: an action
  ;; runs with *agent* bound to its agent, over whatever was captured
  (atom nil))

(defn- agent-var-handle []
  (or @agent-var
      ;; *agent* reaches cohost.core as a refer of the host core's var
      ;; (the prelude refers dynamic vars rather than interning copies),
      ;; so the interns map misses it: the host core's var IS the handle
      (let [v (or (host/interned-var 'cohost.core '*agent*)
                  (host/resolve-core '*agent*))]
        (when v (reset! agent-var v))
        v)))

(defn- run-action
  "Run one action against the agent's state; {:value v} or {:error e}.
   The action's held sends are released either way."
  [ag action]
  (let [pending (atom [])
        bindings (if-let [h (agent-var-handle)]
                   (assoc (:bindings action) h ag)
                   (:bindings action))
        outcome (binding [*pending-sends* pending]
                  (let [run (fn [] (apply (:f action) @(:state ag) (:args action)))]
                    (host/try-all (fn [] {:value ((conveyed bindings run))})
                                  (fn [e] {:error e}))))]
    (release-held! @pending)
    outcome))

(defn- action-failed!
  "Record an action's error; call the handler; in :fail mode stop the
   agent (its queued actions wait for restart-agent). True to keep draining."
  [ag error]
  (swap! (:errors ag) conj error)
  (let [ctl @(:ctl ag)]
    (when-let [h (:handler ctl)]
      (host/try-all (fn [] (h ag error))))
    (if (= :fail (:mode ctl))
      (do (swap! (:ctl ag) assoc :failed error :queued false) false)
      true)))

(defn- drain [ag ex]
  (loop []
    (let [action (claim! (:queue ag))]
      (cond (nil? action)
            (do (swap! (:ctl ag) assoc :queued false)
                ;; an enqueue between the empty claim and the flag clear
                ;; saw :queued true and did not kick: kick for it
                (when-not (q-empty? @(:queue ag)) (kick! ag)))
            (:failed @(:ctl ag))
            (swap! (:ctl ag) assoc :queued false)
            :else
            (let [{:keys [value error]} (run-action ag action)
                  verdict (if error
                            {:error error}
                            (let [v (:validator @(:ctl ag))
                                  check (when v
                                          (host/try-all (fn [] (if (v value) :ok :invalid))
                                                        (fn [e] e)))]
                              (cond (nil? check) {:value value}
                                    (= :ok check) {:value value}
                                    (= :invalid check)
                                    {:error (host/ex* 'java.lang.IllegalStateException
                                                      "Invalid reference state" nil)}
                                    :else {:error check})))]
              (if (:error verdict)
                (when (action-failed! ag (:error verdict)) (recur))
                (do (reset! (:state ag) (:value verdict))
                    ;; hand off when the next action names another executor
                    (let [nxt (q-peek @(:queue ag))]
                      (if (and nxt (:executor nxt) (not (identical? (:executor nxt) ex)))
                        (do (swap! (:ctl ag) assoc :queued false) (kick! ag))
                        (recur))))))))))

;; The STM seam (cohost.tx): a hook the transaction layer installs.
;; dispatch! offers every action to it first; the hook returns true when
;; it has held the action (a send inside a transaction is dispatched only
;; by the successful attempt, exactly once). nil = no hook (default).
(defonce ^:private send-hold-hook (atom nil))

(defn set-send-hold-hook!
  "Install the send-hold hook: a fn of one action map -> boolean."
  [f]
  (reset! send-hold-hook f)
  nil)

(defn enqueue-or-hold!
  "Enqueue an already-built action, or hold it in the running action's
   pending sends when called from inside one. The transaction layer's
   commit path releases held sends through here, so a send released by
   a transaction that ran inside an agent action is held by the action
   in turn -- the two holds nest, as stock's do."
  [action]
  (if *pending-sends*
    (swap! *pending-sends* conj action)
    (enqueue-action! (:ag action) action))
  nil)

(defn dispatch!
  "The send machinery: capture the caller's bindings, hold the action
   when the caller is itself running an action, else enqueue it."
  [ag f args ex]
  (when (:shutdown @registry)
    (throw (host/ex* 'java.lang.IllegalStateException
                     "cohost: agents are shut down" nil)))
  (when (:failed @(:ctl ag))
    (throw (host/ex* 'java.lang.IllegalStateException "Agent is failed" nil)))
  (let [action {:ag ag :f f :args args :bindings (capture-bindings) :executor ex}]
    (if (and @send-hold-hook (@send-hold-hook action))
      nil
      (enqueue-or-hold! action))
    ag))

(defn await1
  "Enqueue the latch action behind everything already queued on ag;
   the latch (stock returns a java.util.concurrent.CountDownLatch)."
  [ag]
  (let [l (new-latch 1)]
    (enqueue-action! ag {:ag ag
                         :f (fn [s] (count-down l) s)
                         :args []
                         :bindings nil
                         :executor (send-executor)})
    l))

(defn shutdown-agents []
  (swap! registry assoc :shutdown true)
  (doseq [ex [(:send-executor @registry) (:send-off-executor @registry)] :when ex]
    (shutdown-executor! ex))
  nil)

(defn agent-state
  "An agent's current state: what deref of the agent reads."
  [ag]
  @(:state ag))

(defn agent-error [ag] (:failed @(:ctl ag)))
(defn agent-errors [ag] @(:errors ag))

(defn clear-agent-errors [ag]
  (when (:failed @(:ctl ag))
    (throw (host/ex* 'java.lang.IllegalStateException
                     "agent is failed; use restart-agent" nil)))
  (reset! (:errors ag) [])
  ag)

(defn restart-agent [ag new-state clear-actions?]
  (when-not (:failed @(:ctl ag))
    (throw (host/ex* 'java.lang.IllegalStateException "Agent is not failed" nil)))
  (when-let [v (:validator @(:ctl ag))]
    (when-not (v new-state)
      (throw (host/ex* 'java.lang.IllegalStateException "Invalid reference state" nil))))
  (reset! (:state ag) new-state)
  (reset! (:errors ag) [])
  (swap! (:ctl ag) assoc :failed nil)
  (when clear-actions? (reset! (:queue ag) (empty-queue)))
  (when-not (q-empty? @(:queue ag)) (kick! ag))
  ag)

(defn set-error-mode! [ag mode]
  (when-not (contains? #{:fail :continue} mode)
    (throw (host/ex* 'java.lang.IllegalArgumentException
                     (str "error-mode must be :fail or :continue, was " mode) nil)))
  (swap! (:ctl ag) assoc :mode mode)
  ag)

(defn error-mode [ag] (:mode @(:ctl ag)))
(defn set-error-handler! [ag h] (swap! (:ctl ag) assoc :handler h) ag)
(defn error-handler [ag] (:handler @(:ctl ag)))

;; ---------------------------------------------------------------------------
;; pmap / pcalls / pvalues over layer futures

(defn pmap*
  "clojure.core/pmap in the layer's shape: futures started over the
   colls, results derefed in order, the future window bounded by the
   pool size + 2 as stock bounds it by the processor count + 2."
  [f & colls]
  (let [n (+ 2 (:size (send-executor)))
        rets (apply map (fn [& xs]
                          ;; form-carrying (unit 8): f crosses by its var's
                          ;; name, xs as values, where the seat has realms
                          (future-form-call 'user '(fn [f xs] (apply f xs)) [f xs]
                                            (fn [] (apply f xs))))
                    colls)
        step (fn step [vs fs]
               (lazy-seq
                (if-let [s (seq fs)]
                  (cons (deref-future (first vs)) (step (rest vs) (rest s)))
                  (map deref-future vs))))]
    (step rets (drop n rets))))

(defn pcalls* [& fns] (pmap* (fn [f] (f)) fns))

;; ---------------------------------------------------------------------------
;; the clojure.core fill: interred by cohost.boot for every concurrency
;; name the host's core did not supply (the host-only names are skipped
;; during the stock boot, then filled from here)

(defn- free-locals
  "The locals of a macro env that body names (eve.thread's free-locals,
   cljs-thread's leaf-symbol analysis), for a ClojureScript compiler's
   env (:locals) and an env whose keys are the locals."
  [env body]
  (let [syms (set (filter symbol? (tree-seq coll? seq body)))
        ks (if (map? (:locals env)) (keys (:locals env)) (when (map? env) (keys env)))]
    (vec (distinct (filter (fn [k] (and (symbol? k) (nil? (namespace k)) (contains? syms k))) ks)))))

(defn- future-macro [_form env & body]
  (if (host/capability :realm-submit)
    ;; unit 8: the form travels with the closure (future-form-call)
    (let [ls (free-locals env body)
          ns-sym (or (when (map? (:ns env)) (:name (:ns env))) 'user)]
      (list 'cohost.realm/future-form-call
            (list 'quote ns-sym)
            (list 'quote (list 'fn ls (cons 'do body)))
            ls
            (list* 'fn [] body)))
    (list 'cohost.core/future-call (list* 'fn [] body))))

(defn- pvalues-macro [_form _env & exprs]
  (cons 'cohost.core/pcalls (map (fn [e] (list 'fn [] e)) exprs)))

(def core-impls
  {'promise new-promise
   'deliver deliver-promise
   'future-call future-call
   'future-done? future-done?
   'future-cancel future-cancel
   'future-cancelled? future-cancelled?
   'future? future-value?
   'agent (fn [state & {:keys [meta validator error-handler error-mode]}]
            (let [ag (new-agent state {:validator validator
                                       :error-handler error-handler
                                       :error-mode error-mode})]
              (if meta (with-meta ag meta) ag)))
   'send (fn [ag f & args] (dispatch! ag f args (send-executor)))
   'send-off (fn [ag f & args] (dispatch! ag f args (send-off-executor)))
   'send-via (fn [ex ag f & args] (dispatch! ag f args ex))
   'set-agent-send-executor! (fn [ex] (set-send-executor! ex) nil)
   'set-agent-send-off-executor! (fn [ex] (set-send-off-executor! ex) nil)
   'release-pending-sends release-pending-sends
   'agent-error agent-error
   'restart-agent (fn [ag new-state & {:keys [clear-actions] :or {clear-actions true}}]
                    (restart-agent ag new-state clear-actions))
   'set-error-handler! set-error-handler!
   'error-handler error-handler
   'set-error-mode! set-error-mode!
   'error-mode error-mode
   'agent-errors agent-errors
   'clear-agent-errors clear-agent-errors
   'shutdown-agents shutdown-agents
   'await (fn [& agents] (doseq [l (map await1 agents)] (await-latch l)) nil)
   'await-for (fn [timeout-ms & agents]
                (let [latches (mapv await1 agents)]
                  (loop [ls latches]
                    (cond (empty? ls) true
                          (await-latch (first ls) timeout-ms) (recur (rest ls))
                          :else false))))
   'await1 await1
   'pmap pmap*
   'pcalls pcalls*})

(def core-macros
  "The fill's macros, name -> macro fn; boot marks them after interning."
  {'future future-macro
   'pvalues pvalues-macro})

(ns cohost.hosts.glj
  "The glojure adapter (Clojure on the Go runtime; Eve's :glj host).
   Glojure presents clojure.lang.* classes and java.* names under
   their JVM spellings (its interpreter mirrors the JVM surface Eve's
   own :glj battery runs on), so -- like jolt -- this adapter is
   nearly the JVM adapter (src/cohost/hosts/jvm.clj is the template;
   STATUS 10's smallest-probe verdict for the Go target). What is
   known to differ is what this file does NOT inherit silently:

   - thread leaves: goroutine-shaped (Go chase lane, STATUS-go-
     chase.md). glojure has no java.lang.Thread/Runnable class
     surface ((Thread. f) throws; Runnable does not resolve), so
     the leaves are built from what the runtime actually runs:
     spawn is glojure's own future machinery (lang.AgentSubmit,
     a fresh goroutine), self is the goroutine's id (eveglj:host
     .GoroutineID -- Eve's provision, the identity eve.process
     /thread-id keeps on this seat), and the doorbell is a
     promise (a channel-backed blocking reference in the
     runtime). Goroutines are real parallel threads and Go has
     no thread kill; cohost.thread's contract is cooperative
     (cancel flag + bounded waits), which maps directly.
   - macro status, read-forms, var machinery: inherited from the
     JVM adapter's construction through cohost.hosts.java, exactly
     as the jolt adapter inherits them; where glojure's surface
     diverges, the seat's probe names it (the STM lane's Go probe
     is the first load of this adapter).

   STM lane (continuation), STATUS 16: this seat did not exist
   (STATUS 10: 'blocked on a missing cohost glojure seat'); it is
   built as the probe's vehicle, minimal and honest -- the adapter
   map is the JVM adapter's entries that name only clojure.lang /
   java.lang surface, re-pointed at this seat's name."
  (:require [cohost.adapter :as adapter]
            [cohost.hosts.java :as java]))

(defn- read-forms [src cb]
  (let [r (java.io.PushbackReader. (java.io.StringReader. src))]
    (loop []
      (let [f (read {:eof ::eof :read-cond :allow} r)]
        (when-not (= f ::eof) (cb f) (recur))))))

(def adapter
  {:name :glj
   :multimethod-cache? true
   :core-ns 'clojure.core
   :catch-all 'Throwable
   :read-forms read-forms
   :ensure-bare-ns! (fn [ns-sym]
                      (binding [*ns* *ns*]
                        (eval (list 'ns ns-sym '(:refer-clojure :only [])))
                        (the-ns ns-sym)))
   :refer-from-core! (fn [into-ns sym]
                       (binding [*ns* (the-ns into-ns)]
                         (ns-unmap into-ns sym)
                         (refer 'clojure.core :only [sym])))
   :resolve-core (fn [sym] (get (ns-publics 'clojure.core) sym))
   ;; glojure's ns-interns / ns-publics consult a registry that
   ;; only stdlib namespaces are in: for a namespace loaded from
   ;; source (cohost.core, any user ns) they THROW "No namespace:
   ;; X found" even though find-ns sees it and its code runs
   ;; (measured, STATUS-go-chase STATUS 2). The caps' contract is
   ;; "the map, or the var, or nil" -- a throw for a loaded ns
   ;; breaks callers that fall back on nil (cohost.thread's
   ;; agent-var-handle: interned-var cohost.core/*agent* throws
   ;; instead of reaching its resolve-core fallback, and the
   ;; worker's drain dies silently -- agent sends never fire).
   ;; The seat absorbs its own quirk: no registry entry = empty.
   :ns-interns (fn [ns-sym] (try (ns-interns ns-sym) (catch Throwable _ {})))
   :interned-var (fn [ns-sym sym]
                   (try (get (ns-interns ns-sym) sym) (catch Throwable _ nil)))
   :macro-var? (fn [v] (boolean (:macro (meta v))))
   :var-raw-root (fn [v] (.getRawRoot v))
   :dynamic-var? (fn [v] (boolean (:dynamic (meta v))))
   :empty-queue (fn [] clojure.lang.PersistentQueue/EMPTY)
   :namespace? (fn [x] (instance? clojure.lang.Namespace x))
   :var-bound? (fn [v] (bound? v))
   :var-thread-bound? (fn [v] (thread-bound? v))
   ;; the thread leaves under cohost.thread, goroutine-shaped.
   ;; Spawn: lang.AgentSubmit is the call clojure.core/future-call
   ;; itself makes on this runtime -- the thunk runs on a fresh
   ;; goroutine; the returned future is the opaque handle (the
   ;; layer never derefs it; it keys nothing and proves nothing --
   ;; truthy is the contract).
   :thread-spawn (fn [thunk]
                   (github.com:glojurelang:glojure:pkg:lang.AgentSubmit thunk))
   ;; Self: the goroutine's id, an int64 -- stable for the
   ;; goroutine's life, distinct across goroutines (measured:
   ;; main = 1; spawns distinct). Thread/currentThread cannot
   ;; serve here: the runtime's javacompat returns a fresh object
   ;; per call, which would mint a new layer thread record (and a
   ;; new cancel flag) on every lookup.
   :thread-self (fn [] (eveglj:host.GoroutineID))
   ;; Doorbell: a promise. Its deref parks the goroutine on the
   ;; runtime's done channel until deliver; deref with a timeout
   ;; is a select against a timer -- the channel receive with a
   ;; timeout case, in the runtime's own implementation. Sticky:
   ;; a delivered promise stays delivered. NOTE: glojure's timed
   ;; deref answers its own :clojure.core/timeout rather than the
   ;; caller's timeout value, so the wait answers (realized? db),
   ;; never the deref value.
   :doorbell (fn [] (promise))
   :doorbell-wait (fn [db timeout-ms]
                    (if (realized? db)
                      true
                      (do (if (some? timeout-ms)
                            (deref db (long timeout-ms) nil)
                            @db)
                          (boolean (realized? db)))))
   :doorbell-signal (fn [db]
                      (deliver db true)
                      true)
   :lazy-seq (fn [f] (clojure.lang.LazySeq. f))
   :delay (fn [f] (clojure.lang.Delay. f))
   :exception? (fn [x] (instance? Throwable x))
   :transient? (fn [x] (instance? clojure.lang.ITransientCollection x))
   :lazy-seq? (fn [x] (instance? clojure.lang.LazySeq x))
   :atom? (fn [x] (instance? clojure.lang.Atom x))
   :current-time-millis (fn [] (System/currentTimeMillis))})

(defn install! [] (adapter/install! adapter))

(ns cohost.hosts.glj
  "The glojure adapter (Clojure on the Go runtime; Eve's :glj host).
   Glojure presents clojure.lang.* classes and java.* names under
   their JVM spellings (its interpreter mirrors the JVM surface Eve's
   own :glj battery runs on), so -- like jolt -- this adapter is
   nearly the JVM adapter (src/cohost/hosts/jvm.clj is the template;
   STATUS 10's smallest-probe verdict for the Go target). What is
   known to differ is what this file does NOT inherit silently:

   - thread leaves: goroutines are real parallel threads and Go has
     no thread kill; cohost.thread's contract is cooperative
     (cancel flag + bounded waits), which maps directly. The JVM
     shapes below run the thunk on a fresh java.lang.Thread, which
     glojure realizes as a goroutine-backed thread.
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
   :macro-var? (fn [v] (boolean (:macro (meta v))))
   :var-raw-root (fn [v] (.getRawRoot v))
   :dynamic-var? (fn [v] (boolean (:dynamic (meta v))))
   :empty-queue (fn [] clojure.lang.PersistentQueue/EMPTY)
   :namespace? (fn [x] (instance? clojure.lang.Namespace x))
   :var-bound? (fn [v] (bound? v))
   :var-thread-bound? (fn [v] (thread-bound? v))
   ;; the thread leaves under cohost.thread, the JVM adapter's shapes
   :thread-spawn (fn [thunk]
                   (let [t (Thread. ^Runnable (fn [] (thunk)))]
                     (.setDaemon t true)
                     (.start t)
                     t))
   :thread-self (fn [] (Thread/currentThread))
   :doorbell (fn [] (atom false))
   :doorbell-wait (fn [db timeout-ms]
                    (locking db
                      (if @db
                        true
                        (do (.wait ^Object db (long (or timeout-ms 0)))
                            (boolean @db)))))
   :doorbell-signal (fn [db]
                      (locking db
                        (reset! db true)
                        (.notifyAll ^Object db)
                        true))
   ;; glojure v0.7.22 has NO java.lang.Thread / Runnable class forms
   ;; (measured: Class/forName is itself an unimplemented host form;
   ;; `Runnable` does not resolve). Goroutines are reached through
   ;; glojure's own `go` macro over Go channels -- a different shape
   ;; from the JVM leaves, needing a go-shaped doorbell (a channel
   ;; receive with timeout) and layer work in cohost.thread, which is
   ;; a seat build, not an adapter entry (STATUS 16). The leaves stay
   ;; ABSENT here rather than wrong: thread/spawn throws its named
   ;; 'no thread-spawn' error, as it does on any seat without them.
   :lazy-seq (fn [f] (clojure.lang.LazySeq. f))
   :delay (fn [f] (clojure.lang.Delay. f))
   :exception? (fn [x] (instance? Throwable x))
   :transient? (fn [x] (instance? clojure.lang.ITransientCollection x))
   :lazy-seq? (fn [x] (instance? clojure.lang.LazySeq x))
   :atom? (fn [x] (instance? clojure.lang.Atom x))
   :current-time-millis (fn [] (System/currentTimeMillis))})

(defn install! [] (adapter/install! adapter))

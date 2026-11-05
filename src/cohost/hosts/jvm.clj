(ns cohost.hosts.jvm
  "JVM Clojure as a host: the reference adapter. Booting the stock core.clj on
   the JVM through cohost maps clojure.lang onto clojure.lang, which
   exercises the codemod, the policy and the boot with no host unknowns."
  (:require [cohost.adapter :as adapter]
            [cohost.hosts.java :as java]))

(defn- read-forms [src cb]
  (let [r (java.io.PushbackReader. (java.io.StringReader. src))]
    (loop []
      (let [f (read {:eof ::eof :read-cond :allow} r)]
        (when-not (= f ::eof) (cb f) (recur))))))

(def adapter
  {:name :clj
   ;; X3-1: the shim's MultiFn with its one-entry cache (adapter.cljc); full bb
   ;; bench:jvm, 5 interleaved rounds on max/main aeac81b: multimethod stock 8.49
   ;; -> 7.24 ms (every true round below every nil round but one), no case
   ;; outside its spread, battery and libraries the same both ways
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
   ;; one lookup each, not the namespace's whole map
   :resolve-core (fn [sym]
                   (let [v (.findInternedVar ^clojure.lang.Namespace (the-ns 'clojure.core) ^clojure.lang.Symbol sym)]
                     (when (and v (.isPublic v)) v)))
   :interned-var (fn [ns-sym sym]
                   (when-let [n (find-ns ns-sym)] (.findInternedVar ^clojure.lang.Namespace n ^clojure.lang.Symbol sym)))
   :current-ns (fn [] (ns-name *ns*))
   :macro-var? (fn [v] (.isMacro ^clojure.lang.Var v))
   :mark-macro! (fn [v] (.setMacro ^clojure.lang.Var v) v)
   :var-raw-root (fn [v] (.getRawRoot ^clojure.lang.Var v))
   :set-dynamic! (fn [v] (.setDynamic ^clojure.lang.Var v) v)
   ;; the runtime's own vars (*ns*, *out*, *print-length* ...) are dynamic by
   ;; flag only; their metadata carries no :dynamic
   :dynamic-var? (fn [v] (.isDynamic ^clojure.lang.Var v))
   :empty-queue (fn [] clojure.lang.PersistentQueue/EMPTY)
   :namespace? (fn [x] (instance? clojure.lang.Namespace x))
   :var-bound? (fn [v] (bound? v))
   :var-thread-bound? (fn [v] (thread-bound? v))
   :var-set! (fn [v val] (.set ^clojure.lang.Var v val))
   ;; the JVM's natives are JVM Clojure's own fns, the stock ones compiled:
   ;; the shared set and what jolt answers otherwise
   :natives (merge java/natives
                   {'clojure.core/into into 'clojure.core/reverse reverse 'clojure.core/replace replace
                    'clojure.string/replace clojure.string/replace 'clojure.string/reverse clojure.string/reverse
                    'clojure.string/split clojure.string/split})
   :lazy-seq (fn [f] (clojure.lang.LazySeq. f))
   ;; an ArrayChunk is no coll reduce walks: IChunk.reduce does
   :chunks (fn [] {:chunked-seq? chunked-seq? :chunk-first chunk-first :chunk-rest chunk-rest :chunk-next chunk-next
                   :chunk-buffer chunk-buffer :chunk-append chunk-append :chunk chunk :chunk-cons chunk-cons
                   :chunk? (fn [x] (instance? clojure.lang.IChunk x))
                   :chunk-reduce (fn [c f init] (.reduce ^clojure.lang.IChunk c f init))})
   :delay (fn [f] (clojure.lang.Delay. f))
   :exception? (fn [x] (instance? Throwable x))
   ;; the trace as data; the head frames of a shim-constructed exception
   ;; are the construction machinery itself (the adapter's ex-info, the
   ;; shim's ex-ctor, the booted core's ex-info fn) -- elide them so the
   ;; first frame is the caller's, as for a directly constructed exception
   :ex-trace (fn [e]
               ;; not an exception (a shim Thread is a state atom, e.g.):
               ;; it has no trace, and must not be cast to one
               (if-not (instance? Throwable e)
                 []
                 (let [ctor-frame? (fn [^StackTraceElement el]
                                   (let [c (.getClassName el)]
                                     (or (.startsWith c "cohost.shim$")
                                         (.startsWith c "cohost.adapter$")
                                         (.startsWith c "cohost.core$ex_info")
                                         ;; the apply machinery the construction
                                         ;; was reached through (a directly
                                         ;; constructed exception's trace starts
                                         ;; at its caller instead)
                                         (= c "clojure.lang.AFn")
                                         (= c "clojure.lang.RestFn")
                                         (= c "clojure.lang.Var")
                                         (.startsWith c "clojure.core$apply"))))]
                 (mapv (fn [^StackTraceElement el]
                         [(symbol (.getClassName el)) (symbol (.getMethodName el))
                          (.getFileName el) (.getLineNumber el)])
                       (drop-while ctor-frame? (.getStackTrace ^Throwable e))))))
   ;; the thread leaves under cohost.thread (Eve's thread layer): a real
   ;; java.lang.Thread runs the thunk; workers are daemons so a process
   ;; whose work is done can exit (stock Clojure holds the process open
   ;; with non-daemon agent threads until shutdown-agents; the layer's
   ;; contract is the conveyed-binding shape, not the exit policy)
   :thread-spawn (fn [thunk]
                   (let [t (Thread. ^Runnable (fn [] (thunk)))]
                     (.setDaemon t true)
                     (.start t)
                     t))
   :thread-self (fn [] (Thread/currentThread))
   ;; a doorbell is an atom holding its signal, used as its own monitor
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
   ;; the running thread's binding frame as a plain {var value} map: the
   ;; layer's conveyance captures with this and restores through the
   ;; adapter's :with-thread-bindings!
   :thread-bindings (fn [] (into {} (clojure.lang.Var/getThreadBindings)))
   :nano-time (fn [] (System/nanoTime))
   :current-time-millis (fn [] (System/currentTimeMillis))
   :math-ceil (fn [x] (Math/ceil x))
   :math-floor (fn [x] (Math/floor x))
   :math-sqrt (fn [x] (Math/sqrt x))
   :parse-long (fn [s] (try (Long/parseLong s) (catch NumberFormatException _ nil)))
   :parse-double (fn [s] (Double/parseDouble s))
   :nan? (fn [x] (and (number? x) (Double/isNaN (double x))))
   :infinite? (fn [x] (and (number? x) (Double/isInfinite (double x))))
   :format (fn [fmt & args] (apply format fmt args))
   :sort-array! (fn [arr cmp] (java.util.Arrays/sort arr cmp) arr)
   :close! (fn [x] (.close x))
   ;; a JVM array (byte[] from .getBytes or a java.net.http body, Object[]
   ;; from to-array): Clojure 1.12 has no array? of its own, and without
   ;; this the shim read none as an array -- (String. bytes "UTF-8") threw
   ;; its ClassCastException on a byte[] (measured, unit 9)
   :array? (fn [x] (and (some? x) (.isArray (class x))))
   ;; cohost.http's bodies as java.io's own byte streams (unit 9): a body a
   ;; program reads through slurp, clojure.java.io or .read is the host's
   ;; InputStream, as the JDK's HttpURLConnection hands it out
   :byte-streams (fn [] {:input (fn [^bytes bs] (java.io.ByteArrayInputStream. bs))
                         :output (fn [] (java.io.ByteArrayOutputStream.))
                         :output-bytes (fn [^java.io.ByteArrayOutputStream o] (.toByteArray o))
                         :bytes? bytes?
                         ;; Ring's bodies (cohost.ring): text and base64 to bytes and back,
                         ;; and the whole of an InputStream or a File
                         :text-bytes (fn [^String s] (.getBytes s "UTF-8"))
                         :b64-bytes (fn [^String s] (.decode (java.util.Base64/getDecoder) s))
                         :bytes-b64 (fn [^bytes bs] (.encodeToString (java.util.Base64/getEncoder) bs))
                         :read-all (fn [x] (cond (instance? java.io.File x) (java.nio.file.Files/readAllBytes (.toPath ^java.io.File x))
                                                 (instance? java.io.InputStream x) (with-open [^java.io.InputStream in x] (.readAllBytes in))
                                                 :else nil))})
   ;; the host's own java.net classes, where cohost.http has no leaves
   :native-ctor (fn [c] (when-let [k (try (Class/forName (str c)) (catch Exception _ nil))]
                          (fn [& args] (clojure.lang.Reflector/invokeConstructor k (to-array args)))))
   :native-static (fn [c m] (when-let [k (try (Class/forName (str c)) (catch Exception _ nil))]
                              (fn [& args] (clojure.lang.Reflector/invokeStaticMethod k (str m) (to-array args)))))
   :index-of (fn [s sub from] (.indexOf ^String s ^String sub (int from)))
   :transient? (fn [x] (instance? clojure.lang.ITransientCollection x))
   :lazy-seq? (fn [x] (instance? clojure.lang.LazySeq x))
   :atom? (fn [x] (instance? clojure.lang.Atom x))
   :volatile? (fn [x] (volatile? x))
   :delay? (fn [x] (delay? x))
   :reduced? (fn [x] (reduced? x))
   :deref? (fn [x] (instance? clojure.lang.IDeref x))
   :pending? (fn [x] (instance? clojure.lang.IPending x))
   :char? (fn [x] (char? x))
   :regex? (fn [x] (instance? java.util.regex.Pattern x))
   :uuid? (fn [x] (uuid? x))
   :bigint? (fn [x] (or (instance? clojure.lang.BigInt x) (instance? java.math.BigInteger x)))
   :ratio? (fn [x] (ratio? x))
   :decimal? (fn [x] (decimal? x))
   :indexed? (fn [x] (indexed? x))
   :counted? (fn [x] (counted? x))
   :reversible? (fn [x] (reversible? x))
   :sorted? (fn [x] (sorted? x))
   ;; RT$DefaultComparator is a Comparator but not an IFn
   :sorted-comparator (fn [x] (let [c (.comparator ^clojure.lang.Sorted x)]
                                (if (ifn? c) c (fn [a b] (.compare ^java.util.Comparator c a b)))))
   :multifn? (fn [x] (instance? clojure.lang.MultiFn x))
   ;; a member by name: a no-arg call is a field or a no-arg method; the
   ;; existence check (cached per class) keeps a method's own
   ;; IllegalArgumentException apart from "no such member"
   ;; a member of that name at this arity (an IReduceInit's 3-arity reduce
   ;; is no answer to a 1-argument .reduce: the shim's then serves)
   ;; A class that is not public (Collections$ReverseComparator) is called
   ;; through the public interface or superclass declaring the method.
   :invoke-member (let [members (atom {})
                        public? (fn [^Class k] (java.lang.reflect.Modifier/isPublic (.getModifiers k)))]
                    (fn [x nm args absent]
                      (let [c (class x) n (count args) k [c nm n]
                            [method? field? via] (or (get @members k)
                                                     (let [of (fn [^Class k] (first (filter #(and (= nm (.getName ^java.lang.reflect.Method %))
                                                                                                  (= n (.getParameterCount ^java.lang.reflect.Method %)))
                                                                                            (.getMethods k))))
                                                           m (of c)
                                                           via (when (and m (not (public? c))) (some #(when (public? %) (of %)) (supers c)))
                                                           v [(some? m)
                                                              (and (zero? n) (boolean (some #(= nm (.getName ^java.lang.reflect.Field %)) (.getFields c))))
                                                              via]]
                                                       (swap! members assoc k v)
                                                       v))]
                        (cond via (.invoke ^java.lang.reflect.Method via x (object-array args))
                              (seq args) (if method? (clojure.lang.Reflector/invokeInstanceMethod x nm (object-array args)) absent)
                              field? (clojure.lang.Reflector/getInstanceField x nm)
                              method? (clojure.lang.Reflector/invokeNoArgInstanceMember x nm false)
                              :else absent))))})

(adapter/install! (merge java/capabilities adapter))

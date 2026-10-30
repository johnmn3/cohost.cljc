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
   :lazy-seq (fn [f] (clojure.lang.LazySeq. f))
   ;; an ArrayChunk is no coll reduce walks: IChunk.reduce does
   :chunks (fn [] {:chunked-seq? chunked-seq? :chunk-first chunk-first :chunk-rest chunk-rest :chunk-next chunk-next
                   :chunk-buffer chunk-buffer :chunk-append chunk-append :chunk chunk :chunk-cons chunk-cons
                   :chunk? (fn [x] (instance? clojure.lang.IChunk x))
                   :chunk-reduce (fn [c f init] (.reduce ^clojure.lang.IChunk c f init))})
   :delay (fn [f] (clojure.lang.Delay. f))
   :exception? (fn [x] (instance? Throwable x))
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

;; The clojure.lang / java.* surface that clojure/core.clj calls, mapped onto
;; the host clojure-like's own data structures. cohost.rewrite turns
;;
;;   (. clojure.lang.RT (cons x s))   into  (cohost.shim/RT-cons x s)
;;   (clojure.lang.Numbers/add x y)   into  (cohost.shim/Numbers-add x y)
;;   (new clojure.lang.LazySeq f)     into  (cohost.shim/new-LazySeq f)
;;   (.withMeta x m)                  into  (cohost.shim/-withMeta x m)
;;   Integer/MAX_VALUE                into  cohost.shim/Integer-MAX_VALUE
;;   clojure.lang.IFn                 into  'clojure.lang.IFn   (a class token)
;;
;; so every var here is one of: `<Class>-<member>` (a static method or field),
;; `new-<Class>` (a constructor), `-<method>` (an instance method, dispatching
;; on the receiver where the JVM would overload). Nearly all of them are the
;; host's core fn under its JVM name: the host's vector IS the
;; PersistentVector, its seq IS the ISeq. What the host lacks is built here
;; from what it has (iterators, chunk buffers, class tokens, JVM exception
;; classes). A member missing here is reported, not papered over: the rewriter
;; emits `missing-*`, which throws when reached.
(ns cohost.shim
  ;; the shim asks the adapter for its capabilities as it loads: a host whose
  ;; loader orders a namespace's requires itself (Phel) installs it first
  (:require [cohost.adapter :as host]
            [cohost.hosts.auto]
            #?(:cljs [goog.object :as gobj]))
  #?(:cljs (:require-macros [cohost.shim-names]))
  ;; the shim's -first, -seq ... are JVM method names, not cljs.core's protocol methods
  #?(:cljs (:refer-clojure :exclude [-assoc -comparator -compare -conj -count -deref -disjoin -drop -empty
                                     -equiv -find -first -flush -invoke -iterator -meta -name -next -nth -peek
                                     -pop -reduce -reset -rseq -seq])))

;; ---------------------------------------------------------------------------
;; the adapter's predicates, asked per element: looked up once, not through
;; cohost.adapter's accessor (an atom deref and a map lookup) on every call

(def ^:private h-char? (host/capability :char?))
(def ^:private h-transient? (host/capability :transient?))
(def ^:private h-namespace? (host/capability :namespace?))
(def ^:private h-atom? (host/capability :atom?))
(def ^:private h-sb? (host/capability :sb?))
(def ^:private h-var? (host/capability :var?))
(def ^:private h-sorted? (host/capability :sorted?))
(def ^:private h-infinite? (host/capability :infinite?))
(def ^:private h-nan? (host/capability :nan?))
(def ^:private h-exception? (host/capability :exception?))
(def ^:private h-regex? (host/capability :regex?))
(def ^:private h-deref? (host/capability :deref?))
(def ^:private h-bigint? (host/capability :bigint?))
(def ^:private h-volatile? (host/capability :volatile?))
(def ^:private h-reduced? (host/capability :reduced?))
(def ^:private h-pending? (host/capability :pending?))
(def ^:private h-multifn? (host/capability :multifn?))
(def ^:private h-delay? (host/capability :delay?))
(def ^:private h-decimal? (host/capability :decimal?))
(def ^:private h-uuid? (host/capability :uuid?))
(def ^:private h-reversible? (host/capability :reversible?))
(def ^:private h-ratio? (host/capability :ratio?))
(def ^:private h-lazy-seq? (host/capability :lazy-seq?))
(def ^:private h-indexed? (host/capability :indexed?))
(def ^:private h-counted? (host/capability :counted?))
(def ^:private h-sb-append! (host/capability :sb-append!))
(def ^:private h-sb-str (host/capability :sb-string))
(def ^:private h-string-builder (host/capability :string-builder))
(def ^:private h-char-code (host/capability :char-code))
(def ^:private h-index-of (host/capability :index-of))
(def ^:private h-upper-case (host/capability :upper-case))
(def ^:private h-lower-case (host/capability :lower-case))
(def ^:private h-re-find-at (host/capability :re-find-at))
(def ^:private h-re-match-full (host/capability :re-match-full))

;; ---------------------------------------------------------------------------
;; misses

(def ^:private runtime-misses (atom {}))

(defn runtime-misses* [] @runtime-misses)

(defn- miss! [k]
  (swap! runtime-misses update k (fnil inc 0))
  (throw (ex-info (str "cohost.shim: no shim for " (pr-str k)) {:cohost/missing k})))

(defn missing-static [c m] (fn [& _args] (miss! [:static c m])))
(defn missing-static-value [c m] (miss! [:static-field c m]))
(defn missing-method
  "A method the shim lacks: the target's own method of that name where the host
   can call one at run time (a JavaScript or Python method), else a miss."
  [m]
  (fn [target & args]
    (when (nil? target)
      (throw (host/ex* 'java.lang.NullPointerException (str "Cannot invoke \"" m "()\" because the target is null"))))
    (if-let [f (host/native-method target (name m))]
      (apply f target args)
      (miss! [:method m]))))
(defn missing-ctor [c] (fn [& _args] (miss! [:ctor c])))

(def ^:private absent (host/->SB :cohost.shim/absent))

(defn- host-fn
  "The host core's own fn for `sym` when it has one, else `fallback`."
  [sym fallback]
  (if-let [v (host/resolve-core sym)] (host/var-root v) fallback))

(def ^:private hc
  "The host's chunked seqs (adapter :chunks), or nil: the stock chunked
   branches run over the host's own chunks; without them, over the stand-ins
   below (ChunkBuffer, ChunkedCons)."
  (host/chunks))
(def ^:private hc-chunked? (or (:chunked-seq? hc) (fn [_] false)))

;; the stubs, by identity: a host may carry no metadata on a fn (ClojureWasm)
(def ^:private stubs (atom #{}))
(defn- unsupported
  "A stub for what this host lacks; registered, so a host with a java.* surface
   of its own is not shadowed by it (cohost.boot leaves such names to the host)."
  [what]
  (let [f (fn [& _] (throw (ex-info (str "cohost.shim: " what " is not available on this host") {:cohost/unsupported what})))]
    (swap! stubs conj f)
    f))
(defn stub? [x] (contains? @stubs x))

;; (to-array nil) is an empty array on the JVM; some hosts reject nil
(defn- to-array* [coll] (host/host-array (if (nil? coll) [] coll)))
(def ^:private host-record? (host-fn 'record? (constantly false)))
(defn record?* [x] (host-record? x))
(def ^:private host-map-entry? (host-fn 'map-entry? (fn [x] (and (vector? x) (= 2 (count x))))))
(def ^:private host-tagged-literal? (host-fn 'tagged-literal? (fn [x] (and (map? x) (contains? x :tag) (contains? x :form)))))
(def ^:private host-reader-conditional? (host-fn 'reader-conditional? (fn [x] (and (map? x) (contains? x :splicing?)))))
;; a stock multimethod is a fn whose state rides in its metadata (below), or,
;; on a host whose fns carry none (ClojureWasm), in a table by identity
;; (a closure, as a multimethod's is: Phel's capture-free fns take metadata, its closures none)
(def ^:private fn-meta? (host/try-all (fn [] (let [p ::probe] (some? (meta (with-meta (fn [] p) {p true})))))))
(def ^:private mf-states (atom {}))
(defn- mf-state [mf] (if fn-meta? (::multifn (meta mf)) (get @mf-states mf)))
(defn multifn?* [x] (and (fn? x) (some? (mf-state x))))

;; ---------------------------------------------------------------------------
;; class tokens: a class is a symbol naming it

(def ^:private token-ancestors
  '{clojure.lang.PersistentVector #{clojure.lang.IPersistentVector clojure.lang.APersistentVector clojure.lang.IPersistentCollection clojure.lang.IPersistentStack clojure.lang.Associative clojure.lang.Sequential clojure.lang.Indexed clojure.lang.Counted clojure.lang.IObj clojure.lang.IMeta clojure.lang.IFn clojure.lang.Reversible clojure.lang.IEditableCollection clojure.lang.IReduce clojure.lang.IReduceInit clojure.lang.IKVReduce clojure.lang.ILookup java.util.List java.util.Collection java.lang.Iterable java.lang.Comparable java.lang.Object}
    clojure.lang.PersistentArrayMap #{clojure.lang.IPersistentMap clojure.lang.APersistentMap clojure.lang.IPersistentCollection clojure.lang.Associative clojure.lang.ILookup clojure.lang.Counted clojure.lang.IObj clojure.lang.IMeta clojure.lang.IFn clojure.lang.IEditableCollection clojure.lang.IKVReduce clojure.lang.Seqable java.util.Map java.lang.Iterable java.lang.Object}
    clojure.lang.MapEntry #{clojure.lang.AMapEntry clojure.lang.APersistentVector clojure.lang.AFn clojure.lang.IMapEntry java.util.Map$Entry clojure.lang.IPersistentVector clojure.lang.Associative clojure.lang.IPersistentStack clojure.lang.IPersistentCollection clojure.lang.Sequential clojure.lang.Indexed clojure.lang.Counted clojure.lang.IFn clojure.lang.Reversible clojure.lang.ILookup clojure.lang.IHashEq clojure.lang.Seqable java.util.List java.util.RandomAccess java.util.Collection java.lang.Iterable java.lang.Comparable java.lang.Runnable java.util.concurrent.Callable java.io.Serializable java.lang.Object}
    clojure.lang.PersistentQueue #{clojure.lang.IPersistentList clojure.lang.IPersistentStack clojure.lang.IPersistentCollection clojure.lang.Obj clojure.lang.IObj clojure.lang.IMeta clojure.lang.Counted clojure.lang.Sequential clojure.lang.Seqable clojure.lang.IHashEq java.util.Collection java.lang.Iterable java.io.Serializable java.lang.Object}
    clojure.lang.PersistentHashMap #{clojure.lang.IPersistentMap clojure.lang.APersistentMap clojure.lang.IPersistentCollection clojure.lang.Associative clojure.lang.ILookup clojure.lang.Counted clojure.lang.IObj clojure.lang.IMeta clojure.lang.IFn clojure.lang.IEditableCollection clojure.lang.IKVReduce clojure.lang.Seqable java.util.Map java.lang.Iterable java.lang.Object}
    clojure.lang.PersistentTreeMap #{clojure.lang.IPersistentMap clojure.lang.APersistentMap clojure.lang.IPersistentCollection clojure.lang.Associative clojure.lang.ILookup clojure.lang.Counted clojure.lang.IObj clojure.lang.IMeta clojure.lang.IFn clojure.lang.Sorted clojure.lang.Reversible clojure.lang.IKVReduce java.util.Map java.lang.Object}
    clojure.lang.PersistentHashSet #{clojure.lang.IPersistentSet clojure.lang.APersistentSet clojure.lang.IPersistentCollection clojure.lang.Counted clojure.lang.IObj clojure.lang.IMeta clojure.lang.IFn clojure.lang.IEditableCollection java.util.Set java.util.Collection java.lang.Iterable java.lang.Object}
    clojure.lang.PersistentTreeSet #{clojure.lang.IPersistentSet clojure.lang.APersistentSet clojure.lang.IPersistentCollection clojure.lang.Counted clojure.lang.IObj clojure.lang.IMeta clojure.lang.IFn clojure.lang.Sorted clojure.lang.Reversible java.util.Set java.lang.Object}
    clojure.lang.PersistentList #{clojure.lang.IPersistentList clojure.lang.ISeq clojure.lang.ASeq clojure.lang.IPersistentCollection clojure.lang.IPersistentStack clojure.lang.Sequential clojure.lang.Counted clojure.lang.IObj clojure.lang.IMeta clojure.lang.IReduce clojure.lang.IReduceInit java.util.List java.lang.Object}
    clojure.lang.LazySeq #{clojure.lang.ISeq clojure.lang.IPersistentCollection clojure.lang.Sequential clojure.lang.IPending clojure.lang.IObj clojure.lang.IMeta java.util.List java.lang.Object}
    clojure.lang.Cons #{clojure.lang.ISeq clojure.lang.ASeq clojure.lang.IPersistentCollection clojure.lang.Sequential clojure.lang.IObj clojure.lang.IMeta java.util.List java.lang.Object}
    clojure.lang.ASeq #{clojure.lang.ISeq clojure.lang.IPersistentCollection clojure.lang.Sequential clojure.lang.IObj clojure.lang.IMeta java.lang.Object}
    clojure.lang.Symbol #{clojure.lang.Named clojure.lang.IObj clojure.lang.IMeta clojure.lang.IFn java.lang.Comparable java.lang.Object}
    clojure.lang.Keyword #{clojure.lang.Named clojure.lang.IFn java.lang.Comparable java.lang.Object}
    clojure.lang.Var #{clojure.lang.IRef clojure.lang.IReference clojure.lang.IDeref clojure.lang.IMeta clojure.lang.IFn clojure.lang.Settable java.lang.Object}
    clojure.lang.Atom #{clojure.lang.IAtom clojure.lang.IAtom2 clojure.lang.IRef clojure.lang.IReference clojure.lang.IDeref clojure.lang.IMeta java.lang.Object}
    clojure.lang.Delay #{clojure.lang.IDeref clojure.lang.IPending java.lang.Object}
    clojure.lang.Volatile #{clojure.lang.IDeref java.lang.Object}
    clojure.lang.Reduced #{clojure.lang.IDeref java.lang.Object}
    clojure.lang.AFunction #{clojure.lang.Fn clojure.lang.IFn clojure.lang.IObj clojure.lang.IMeta java.lang.Runnable java.util.concurrent.Callable java.util.Comparator java.lang.Object}
    clojure.lang.MultiFn #{clojure.lang.IFn java.lang.Object}
    clojure.lang.Namespace #{clojure.lang.IReference clojure.lang.IMeta java.lang.Object}
    java.lang.String #{java.lang.CharSequence java.lang.Comparable java.lang.Object}
    java.lang.Long #{java.lang.Number java.lang.Comparable java.lang.Object}
    java.lang.Double #{java.lang.Number java.lang.Comparable java.lang.Object}
    java.lang.Boolean #{java.lang.Comparable java.lang.Object}
    java.lang.Character #{java.lang.Comparable java.lang.Object}
    clojure.lang.BigInt #{java.lang.Number java.lang.Object}
    java.math.BigDecimal #{java.lang.Number java.lang.Comparable java.lang.Object}
    clojure.lang.Ratio #{java.lang.Number java.lang.Comparable java.lang.Object}
    java.util.regex.Pattern #{java.lang.Object}
    java.util.UUID #{java.lang.Comparable java.lang.Object}
    clojure.lang.ExceptionInfo #{clojure.lang.IExceptionInfo java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.lang.RuntimeException #{java.lang.Exception java.lang.Throwable java.lang.Object}
    java.lang.IllegalArgumentException #{java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.IllegalFormatException #{java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.IllegalFormatConversionException #{java.util.IllegalFormatException java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.IllegalFormatPrecisionException #{java.util.IllegalFormatException java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.IllegalFormatWidthException #{java.util.IllegalFormatException java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.IllegalFormatFlagsException #{java.util.IllegalFormatException java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.IllegalFormatArgumentIndexException #{java.util.IllegalFormatException java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.IllegalFormatCodePointException #{java.util.IllegalFormatException java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.FormatFlagsConversionMismatchException #{java.util.IllegalFormatException java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.MissingFormatArgumentException #{java.util.IllegalFormatException java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.MissingFormatWidthException #{java.util.IllegalFormatException java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.UnknownFormatConversionException #{java.util.IllegalFormatException java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.UnknownFormatFlagsException #{java.util.IllegalFormatException java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.DuplicateFormatFlagsException #{java.util.IllegalFormatException java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.lang.NumberFormatException #{java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.lang.IllegalStateException #{java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.lang.UnsupportedOperationException #{java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.lang.ArithmeticException #{java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.lang.ClassCastException #{java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.lang.NullPointerException #{java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.lang.IndexOutOfBoundsException #{java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.lang.ArrayIndexOutOfBoundsException #{java.lang.IndexOutOfBoundsException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.lang.StringIndexOutOfBoundsException #{java.lang.IndexOutOfBoundsException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.NoSuchElementException #{java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.ConcurrentModificationException #{java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    clojure.lang.Compiler$CompilerException #{java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.lang.InterruptedException #{java.lang.Exception java.lang.Throwable java.lang.Object}
    java.io.IOException #{java.lang.Exception java.lang.Throwable java.lang.Object}
    java.lang.Error #{java.lang.Throwable java.lang.Object}
    java.lang.AssertionError #{java.lang.Error java.lang.Throwable java.lang.Object}
    java.lang.StackOverflowError #{java.lang.VirtualMachineError java.lang.Error java.lang.Throwable java.lang.Object}
    java.lang.OutOfMemoryError #{java.lang.VirtualMachineError java.lang.Error java.lang.Throwable java.lang.Object}
    java.lang.IllegalAccessError #{java.lang.IncompatibleClassChangeError java.lang.LinkageError java.lang.Error java.lang.Throwable java.lang.Object}
    java.lang.Exception #{java.lang.Throwable java.lang.Object}
    clojure.lang.ArityException #{java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.util.regex.PatternSyntaxException #{java.lang.IllegalArgumentException java.lang.RuntimeException java.lang.Exception java.lang.Throwable java.lang.Object}
    java.lang.Throwable #{java.lang.Object}
    java.lang.Class #{java.lang.Object}})

(def ^:private short-name
  "java.lang.String and String are the same token."
  (into {} (keep (fn [c]
                   (let [s (name c)
                         i (loop [i (dec (count s))] (cond (neg? i) -1 (= "." (str (nth s i))) i :else (recur (dec i))))]
                     (when (and (not (neg? i)) (or (= "java.lang." (subs s 0 (inc i))) (= "java.lang" (subs s 0 i))))
                       [(symbol (subs s (inc i))) c])))
                 (keys token-ancestors))))

(def ^:private short-name-extra
  '{BigInteger java.math.BigInteger BigDecimal java.math.BigDecimal
    Array java.lang.reflect.Array ExceptionInfo clojure.lang.ExceptionInfo
    IExceptionInfo clojure.lang.IExceptionInfo
    BlockingQueue java.util.concurrent.BlockingQueue
    LinkedBlockingQueue java.util.concurrent.LinkedBlockingQueue
    Callable java.util.concurrent.Callable Future java.util.concurrent.Future
    Writer java.io.Writer})

(defn- has-dot? [sym] (not (neg? (loop [i (dec (count (name sym)))] (cond (neg? i) -1 (= "." (str (nth (name sym) i))) i :else (recur (dec i)))))))

(def ^:private canon-cache (atom {}))
(defn- canon
  "Any bare JVM class name is a java.lang class unless listed otherwise.
   Memoized: instance? and the codemod's checkcasts ask it on every call, of
   the few class tokens source names."
  [c]
  (if-not (symbol? c)
    c
    (let [hit (get @canon-cache c absent)]
      (if (identical? hit absent)
        (let [r (cond (contains? short-name c) (get short-name c)
                      (contains? short-name-extra c) (get short-name-extra c)
                      (has-dot? c) c
                      :else (symbol (str "java.lang." (name c))))]
          (swap! canon-cache assoc c r)
          r)
        hit))))

;; a host whose own map-entry? exists tells entries from 2-vectors
(def ^:private host-has-map-entry? (some? (host-fn 'map-entry? nil)))
;; the host's queue type, when it has one
(def ^:private host-queue?
  (let [q (host/empty-queue)]
    (if (some? q) (let [t (type q)] (fn [x] (and (some? x) (= t (type x))))) (constantly false))))
(defn- hash-map-kind?
  "PersistentHashMap or PersistentArrayMap: the host's own type name when it
   says (the JVM, jolt), else the JVM's threshold (an array map holds up to 8)."
  [m]
  (let [t #?(:cljs (some-> (type m) (.-cljs$lang$ctorStr)) :default (str (type m)))]
    (cond (and (string? t) (not (neg? (h-index-of t "PersistentHashMap" 0)))) true
          (and (string? t) (not (neg? (h-index-of t "PersistentArrayMap" 0)))) false
          :else (> (count m) 8))))

;; the type of a host object (a record's, a user type's): on Phel every struct's
;; type is :struct and every object's :php/object, so its PHP class, as a token
(defn- type-of [x]
  #?(:phel (if (php/is_object x) (symbol (php/str_replace "\\" "." (php/get_class x))) (type x))
     :default (type x)))

(defn class-of
  "The JVM class token (a symbol) of a host value."
  [x]
  (cond
    (nil? x) nil
    (string? x) 'java.lang.String
    (boolean? x) 'java.lang.Boolean
    (h-char? x) 'java.lang.Character
    (h-ratio? x) 'clojure.lang.Ratio
    (h-decimal? x) 'java.math.BigDecimal
    (h-bigint? x) 'clojure.lang.BigInt
    (integer? x) 'java.lang.Long
    (number? x) 'java.lang.Double
    (keyword? x) 'clojure.lang.Keyword
    (symbol? x) 'clojure.lang.Symbol
    (h-var? x) 'clojure.lang.Var
    (h-lazy-seq? x) 'clojure.lang.LazySeq
    (and host-has-map-entry? (host-map-entry? x)) 'clojure.lang.MapEntry
    (vector? x) 'clojure.lang.PersistentVector
    (host-record? x) (type-of x)
    (map? x) (cond (h-sorted? x) 'clojure.lang.PersistentTreeMap
                   (hash-map-kind? x) 'clojure.lang.PersistentHashMap
                   :else 'clojure.lang.PersistentArrayMap)
    (host-queue? x) 'clojure.lang.PersistentQueue
    (set? x) (if (h-sorted? x) 'clojure.lang.PersistentTreeSet 'clojure.lang.PersistentHashSet)
    (list? x) 'clojure.lang.PersistentList
    (seq? x) 'clojure.lang.Cons
    (h-atom? x) 'clojure.lang.Atom
    (h-delay? x) 'clojure.lang.Delay
    (h-volatile? x) 'clojure.lang.Volatile
    (h-reduced? x) 'clojure.lang.Reduced
    (or (multifn?* x) (h-multifn? x)) 'clojure.lang.MultiFn
    (h-namespace? x) 'clojure.lang.Namespace
    (h-regex? x) 'java.util.regex.Pattern
    (h-uuid? x) 'java.util.UUID
    (h-exception? x) (canon (or (:cohost/class (ex-data x))
                                   (when (host/ex-info? x) 'clojure.lang.ExceptionInfo)
                                   (host/exception-class x)))
    (fn? x) 'clojure.lang.AFunction
    (host/iter? x) 'java.util.Iterator
    ;; a host class (a deftype instance, a JS or Python object): the host's own
    :else (or (type-of x) 'java.lang.Object)))

(defn class-supers [c] (get token-ancestors (canon c) #{}))
(defn class-bases [c]
  (let [all (class-supers c)
        ;; direct = not implied by another super; Object is every class's
        ;; ancestor, so it is a base only when nothing else is
        all (if (next all) (remove #{'java.lang.Object} all) all)]
    (remove (fn [p] (some #(and (not= % p) (contains? (get token-ancestors % #{}) p)) all)) all)))

(defn- token-isa? [child parent]
  (let [child (canon child) parent (canon parent)]
    (or (= child parent)
        (= parent 'java.lang.Object)
        (contains? (get token-ancestors child #{}) parent))))

(declare class-token?)
(declare chunked-cons?)

(def ^:private interface-preds
  {'clojure.lang.IPersistentVector vector?
   ;; a record is a map (Phel's struct is no map? to Phel)
   'clojure.lang.IPersistentMap #(or (map? %) (host-record? %))
   'clojure.lang.IPersistentSet set?
   'clojure.lang.IPersistentList #(or (list? %) (host-queue? %))
   'clojure.lang.IPersistentStack #(or (vector? %) (list? %) (host-queue? %))
   'clojure.lang.IPersistentCollection coll?
   'clojure.lang.ISeq seq?
   'clojure.lang.Seqable coll?
   'clojure.lang.Sequential sequential?
   'clojure.lang.Associative associative?
   'clojure.lang.ILookup #(or (map? %) (vector? %) (host-record? %))
   'clojure.lang.Counted h-counted?
   'clojure.lang.Indexed h-indexed?
   'clojure.lang.Reversible h-reversible?
   'clojure.lang.Sorted h-sorted?
   'clojure.lang.IChunkedSeq #(or (hc-chunked? %) (chunked-cons? %))
   'clojure.lang.IDrop (constantly false)
   'clojure.lang.IFn host/ifn?*
   'clojure.lang.Fn host/host-fn?
   'clojure.lang.IObj #(or (and (coll? %) (not (and host-has-map-entry? (host-map-entry? %)))) (symbol? %) (fn? %))
   'clojure.lang.IMeta #(or (coll? %) (symbol? %) (fn? %) (h-var? %) (h-namespace? %) (h-atom? %))
   'clojure.lang.IReference #(or (h-var? %) (h-namespace? %) (h-atom? %))
   'clojure.lang.IRef #(or (h-var? %) (h-atom? %))
   'clojure.lang.IDeref h-deref?
   'clojure.lang.IBlockingDeref h-deref?
   'clojure.lang.IPending h-pending?
   ;; sorted collections have no transient form
   'clojure.lang.IEditableCollection #(and (or (vector? %) (map? %) (set? %)) (not (h-sorted? %)) (not (host-record? %)))
   'clojure.lang.ITransientCollection h-transient?
   'clojure.lang.ITransientAssociative h-transient?
   'clojure.lang.ITransientMap h-transient?
   'clojure.lang.ITransientVector h-transient?
   'clojure.lang.ITransientSet h-transient?
   ;; vectors and lists here; ranges, an Eduction, a user type: the host's answer
   'clojure.lang.IReduceInit #(or (vector? %) (list? %) (host/host-instance? 'clojure.lang.IReduceInit %))
   'clojure.lang.IReduce #(or (vector? %) (list? %) (host/host-instance? 'clojure.lang.IReduce %))
   'clojure.lang.IKVReduce #(or (map? %) (vector? %) (host-record? %))
   'clojure.lang.Named #(or (symbol? %) (keyword? %))
   'java.util.Map$Entry host-map-entry?
   'clojure.lang.IMapEntry host-map-entry?
   'clojure.lang.MapEntry host-map-entry?
   'clojure.lang.MultiFn #(or (multifn?* %) (h-multifn? %))
   'clojure.lang.TaggedLiteral host-tagged-literal?
   'clojure.lang.ReaderConditional host-reader-conditional?
   'clojure.lang.IType (constantly false)
   'clojure.lang.IRecord host-record?
   'clojure.lang.IAtom h-atom?
   'clojure.lang.IAtom2 h-atom?
   'clojure.lang.IExceptionInfo host/ex-info?
   'java.lang.Comparable #(or (number? %) (string? %) (keyword? %) (symbol? %) (boolean? %) (vector? %) (h-char? %))
   ;; by the host's predicates, not class-of: on a host whose chars are
   ;; one-character strings, "a" is both a String and a Character
   'java.lang.String string?
   'java.lang.Character h-char?
   'java.lang.CharSequence #(or (string? %) (h-sb? %))
   'java.lang.Number number?
   'java.lang.Integer integer?
   'java.lang.Short integer?
   'java.lang.Byte integer?
   'java.lang.Float #(host/host-instance? 'java.lang.Float %)
   'java.math.BigInteger h-bigint?
   'java.lang.Iterable coll?
   'java.util.Collection #(and (coll? %) (not (map? %)))
   'java.util.List #(or (vector? %) (seq? %) (list? %))
   'java.util.Set set?
   'java.util.Map map?
   'java.util.RandomAccess vector?
   'java.util.Iterator host/iter?
   'java.util.regex.Matcher (constantly false)
   'java.lang.Runnable fn?
   'java.util.concurrent.Callable fn?
   'java.util.concurrent.Future (constantly false)
   'java.lang.Class #(class-token? %)
   'java.lang.Throwable h-exception?
   'java.lang.Object some?})

(defn- class-token?
  "A symbol naming a JVM class the shim knows (a `class?` on the JVM)."
  [x]
  (and (symbol? x)
       (let [c (canon x)] (or (contains? token-ancestors c) (contains? interface-preds c)))))

(defn- builtin?
  "The host's own data: the shim's predicates and class table are the answer
   for these (a JVM vector is an IDrop, but core.clj's IDrop path is not the
   shim's to take)."
  [x]
  (or (nil? x) (coll? x) (seq? x) (string? x) (number? x) (keyword? x) (symbol? x)
      (boolean? x) (fn? x) (h-char? x) (h-var? x)))

(defn instance-of?
  "`(instance? c x)` for a class token `c` (a symbol): the shim's predicates
   and class hierarchy; for an object that is not the host's own data (a
   reify or deftype instance), also the host's own class when it has one
   (clojure.lang.IObj on the JVM or jolt). A host class value (a record type,
   js/Error, a Python class) is the host's own instance? question."
  [c x]
  (if-not (symbol? c)
    (boolean (host/class-instance? c x))
    ;; an interface token is already canonical: its predicate first
    (let [p (get interface-preds c)
          c (if p c (canon c))
          p (or p (get interface-preds c))]
      (boolean
       (and (some? x)
            (or (if p
                  (p x)
                  (token-isa? (class-of x) c))
                ;; an exception's class is the shim's hierarchy: a shim-raised
                ;; NumberFormatException may be a host ex-info underneath, and
                ;; is no IExceptionInfo (stock ex-data of it is nil, as on the JVM)
                (when-not (or (builtin? x) (h-exception? x))
                  (host/host-instance? c x))))))))

(defn cast-message [c x]
  (str "class " (class-of x) " cannot be cast to class " (canon c)))

(defn check-cast
  "The JVM's checkcast of an interop target the source hints (^Pattern re in
   clojure.string/split): x itself, nil included, when it is an instance of c."
  [c x]
  (if (or (nil? x) (instance-of? c x)) x (throw (host/ex* 'java.lang.ClassCastException (cast-message c x)))))

(defn- cast-fn
  "check-cast for the one class token c, its predicate looked up once."
  [c]
  (let [p (get interface-preds c)
        c' (if p c (canon c))
        p (or p (get interface-preds c'))]
    (fn [x]
      (if (or (nil? x)
              (if p (p x) (token-isa? (class-of x) c'))
              (and (not (builtin? x)) (not (h-exception? x)) (host/host-instance? c' x)))
        x
        (throw (host/ex* 'java.lang.ClassCastException (cast-message c x)))))))

(defn- intern-shim!
  "A shim var name holding v (on ClojureScript, a property of the shim's
   namespace object)."
  [d v]
  #?(:cljs (gobj/set js/cohost.shim (munge d) v)
     :default (host/intern! 'cohost.shim (symbol d) v)))

(defn- token-name [prefix c]
  (str prefix (apply str (map (fn [ch] (let [ch (str ch)] (if (= "." ch) "_" ch))) (str c)))))

(defn- instance-fn
  "instance-of? for the one class token c, its predicate looked up once."
  [c]
  (let [p (get interface-preds c)
        c' (if p c (canon c))
        p (or p (get interface-preds c'))]
    (fn [x]
      (boolean (and (some? x)
                    (or (if p (p x) (token-isa? (class-of x) c'))
                        (and (not (builtin? x)) (not (h-exception? x)) (host/host-instance? c' x))))))))

(def ^:private installed-is (atom {}))
(defn install-is!
  "The name of a shim var holding instance-of? for class token c, built on its
   first use: the codemod's (instance? C x) calls it."
  [c]
  (or (get @installed-is c)
      (let [d (token-name "is$" c)]
        (intern-shim! d (instance-fn c))
        (swap! installed-is assoc c d)
        d)))

(declare dot)
(def ^:private installed-missing (atom {}))
(defn install-missing-dot!
  "The name of a shim var holding the dispatcher for a method m the shim
   lacks (a user type's own member, else the thrower), built on its first use
   rather than at every call."
  [m]
  (or (get @installed-missing m)
      (let [d (str "dot$" (name m))]
        (intern-shim! d (dot (name m) (missing-method (symbol (name m)))))
        (swap! installed-missing assoc m d)
        d)))

(defn install-casts!
  "For each [name token]: a shim var name holding check-cast for that class
   token, built once (on ClojureScript, a property of the shim's namespace
   object) — the codemod's checkcast of a hinted local calls it. -> the
   names installed."
  [pairs]
  (mapv (fn [[d c]]
          (let [v (cast-fn c)]
            #?(:cljs (gobj/set js/cohost.shim (munge d) v)
               :default (host/intern! 'cohost.shim (symbol d) v))
            (symbol d)))
        pairs))

(defn catches?
  "Does a `(catch c e ...)` clause for JVM class `c` apply to host exception `e`?"
  [c e]
  (let [c (canon c)]
    (if (contains? '#{java.lang.Throwable java.lang.Object} c)
      true
      ;; Exception and RuntimeException go through the hierarchy too: on the
      ;; JVM they leave Errors (AssertionError, StackOverflowError) alone
      (let [k (or (:cohost/class (ex-data e))
                  (when (host/ex-info? e) 'clojure.lang.ExceptionInfo)
                  (host/exception-class e))]
        (token-isa? (canon k) c)))))

;; ---------------------------------------------------------------------------
;; RT

;; Phel's cons onto anything but a seq (a vector, a map, nil) is a vector
(def RT-cons #?(:phel (fn [x coll] (if (seq? coll) (cons x coll) (let [s (seq coll)] (if s (cons x s) (list x)))))
                :default cons))
(def RT-first first)
(def RT-next next)
(def RT-more rest)
(def RT-seq seq)
(def RT-conj conj)
(def RT-count count)
(def RT-nth nth)
;; Phel's get reads any other object as a PHP array, which throws: the JVM's
;; get answers not-found for what is no lookup
(def RT-get
  #?(:phel (let [lookup? (fn [m] (or (not (php/is_object m)) (php/is_a m "ArrayAccess") (set? m) (seq? m)
                                     (php/is_a m "Phel\\Lang\\Collections\\HashSet\\TransientHashSetInterface")))]
             (fn ([m k] (if (lookup? m) (get m k) nil))
               ([m k nf] (if (lookup? m) (get m k nf) nf))))
     :default get))
;; a record on Phel is a struct, which holds its fields alone: a key outside
;; them makes a map of its fields and that key (on the JVM, a record still)
(def ^:private assoc*
  #?(:phel (fn [m k v] (if (and (host-record? m) (not (contains? m k))) (assoc (into {} (seq m)) k v) (assoc m k v)))
     :default assoc))
(def RT-assoc assoc*)
(def RT-dissoc dissoc)
(def RT-contains contains?)
(def RT-find find)
(def RT-keys keys)
(def RT-vals vals)
(def RT-peek peek)
(def RT-pop #?(:phel (fn [coll] (pop coll)) :default pop)) ; (Phel's pop is a macro)
(def RT-subvec (host-fn 'subvec (unsupported "subvec")))
(def ^:private arr? (or (host/array-fn) (host-fn 'array? (constantly false))))
(def RT-vector (fn [& xs] (if (and (= 1 (count xs)) (arr? (first xs))) (vec (first xs)) (vec xs))))
(def RT-toArray to-array*)
(def RT-seqToTypedArray (fn ([s] (to-array* s)) ([_t s] (to-array* s))))
(def RT-object_array (fn [n-or-seq] (if (number? n-or-seq) (to-array* (repeat n-or-seq nil)) (to-array* n-or-seq))))
(def RT-alength #?(:phel count :default (host-fn 'alength count))) ; (Phel's takes a PHP array)
(def RT-aclone #?(:phel (fn [a] (to-array* (seq a))) :default (host-fn 'aclone (fn [a] (to-array (seq a))))))
(def RT-booleanCast boolean)
;; (int \\a) is the char's code; where chars are one-character strings the
;; host's int would parse the string instead
(defn- char-aware [f] (fn [x] (if (h-char? x) (h-char-code x) (f x))))
(def RT-intCast (char-aware (host-fn 'int int)))
(def RT-longCast (char-aware (host-fn 'long (host-fn 'int identity))))
(def RT-doubleCast (host-fn 'double (host-fn 'float identity)))
(def RT-floatCast (host-fn 'float identity))
(def RT-charCast (host-fn 'char identity))
(def RT-byteCast (host-fn 'byte (host-fn 'int identity)))
(def RT-shortCast (host-fn 'short (host-fn 'int identity)))
;; (int) / (long) / (byte) / (short) of the JVM: a double truncates and
;; saturates (NaN is 0), an integer keeps its low bits
(defn- wrap32 [n] (let [m (bit-and n 0xFFFFFFFF)] (if (>= m 0x80000000) (- m 0x100000000) m)))
(defn- d2l [d lo hi] (cond (h-nan? d) 0 (>= d hi) hi (<= d lo) lo :else (RT-longCast d)))
(defn- int-bits [x] (cond (h-char? x) (h-char-code x)
                          (integer? x) x
                          :else (d2l (RT-doubleCast x) -2147483648 2147483647)))
(def RT-uncheckedIntCast (fn [x] (wrap32 (int-bits x))))
(defn- char-cce [] (host/ex* 'java.lang.ClassCastException "class java.lang.Character cannot be cast to class java.lang.Number"))
(def RT-uncheckedLongCast (fn [x] (cond (h-char? x) (throw (char-cce))
                                        (integer? x) x
                                        :else (d2l (RT-doubleCast x) -9223372036854775808 9223372036854775807))))
(def RT-uncheckedDoubleCast RT-doubleCast)
(def RT-uncheckedFloatCast RT-floatCast)
(def RT-uncheckedCharCast RT-charCast)
(def RT-uncheckedByteCast (fn [x] (when (h-char? x) (throw (char-cce))) (- (bit-and (+ (wrap32 (int-bits x)) 128) 255) 128)))
(def RT-uncheckedShortCast (fn [x] (when (h-char? x) (throw (char-cce))) (- (bit-and (+ (wrap32 (int-bits x)) 32768) 65535) 32768)))
(declare matcher?)
;; RT.canSeq: seqs, colls, strings, nil, arrays; not the shim's iterator or
;; matcher (records here, but no Seqable on the JVM)
(def ^:private h-seqable? (host-fn 'seqable? nil))
(def RT-canSeq (fn [x] (and (not (host/iter? x)) (not (matcher? x))
                            (or (host/seqable?* x) (arr? x) (host/mlist? x)
                                ;; a Seqable or Iterable object of another type: the host's seqable?
                                (boolean (and h-seqable? (not (builtin? x)) (h-seqable? x)))))))
(def RT-iter (fn [coll] (if (host/iter? coll) coll (host/iter coll))))
(def RT-isReduced (host-fn 'reduced? (unsupported "reduced?")))
(def ^:private id-counter (atom 0))
(def RT-nextID (fn [] (swap! id-counter inc)))
(def load-hook (atom nil))
(def RT-load (fn [path & _] (if-let [f @load-hook] (f path) (throw (ex-info "cohost.shim: no loader bound" {:path path})))))
;; the host's read-string when it has one; else the adapter's reader (a host
;; with no read-string of its own, like ClojureScript, still reads)
(def RT-readString (let [rs (host-fn 'read-string nil)]
                     (fn ([s] (if rs (rs s) (host/read-string* s {})))
                       ([s opts] (if rs (rs opts s) (host/read-string* s opts))))))
(def RT-baseLoader (fn [] nil))
(def RT-addURL (fn [& _] nil))
;; RT.chunkIteratorSeq: nil for an exhausted iterator (so `(sequence xf [])`
;; is `()` and `iterator-seq` of nothing is nil), else a lazy seq over it
(def RT-chunkIteratorSeq
  (fn chunk-iterator-seq [it]
    (when (host/iter-has-next? it)
      (host/lazy-seq* (fn [] (cons (host/iter-next! it) (chunk-iterator-seq it)))))))
(def RT-printString pr-str)

;; ---------------------------------------------------------------------------
;; Numbers

(def Numbers-add +)
(def Numbers-addP (host-fn '+' +))
(def Numbers-minus (fn ([x] (- x)) ([x y] (- x y))))
(def Numbers-minusP (host-fn '-' -))
(def Numbers-multiply *)
(def Numbers-multiplyP (host-fn '*' *))
;; a double divided by zero is an infinity or NaN, as IEEE 754 and the JVM
;; say (jolt's / as a value and basilisp's Python division throw instead)
(defn- ieee-div-zero [x y]
  (let [cs (get (host/math-fns) 'copySign)
        neg-y (boolean (when cs (neg? (cs 1.0 y))))]
    (cond (or (h-nan? x) (zero? x)) (host/parse-double* "NaN")
          (not= (neg? x) neg-y) (host/parse-double* "-Infinity")
          :else (host/parse-double* "Infinity"))))
(def Numbers-divide (let [h /] (fn [x y] (if (and (zero? y) (or (float? x) (float? y))) (ieee-div-zero x y) (h x y)))))
(def Numbers-inc inc)
(def Numbers-incP (host-fn 'inc' inc))
(def Numbers-dec dec)
(def Numbers-decP (host-fn 'dec' dec))
(def Numbers-lt <)
(def Numbers-lte <=)
(def Numbers-gt >)
(def Numbers-gte >=)
(def Numbers-equiv ==)
(def Numbers-isZero zero?)
(def Numbers-isPos pos?)
(def Numbers-isNeg neg?)
(defn- divide-by-zero [] (host/ex* 'java.lang.ArithmeticException "Divide by zero"))
;; Numbers.quotient / remainder check the divisor for every operand type
(def Numbers-quotient (fn [x y] (if (zero? y) (throw (divide-by-zero)) (quot x y))))
(def Numbers-remainder (fn [x y] (if (zero? y) (throw (divide-by-zero)) (rem x y))))
;; Numbers.max / min: a NaN argument wins; a tie is the second argument
(def Numbers-max (fn [x y] (cond (h-nan? x) x (h-nan? y) y (> x y) x :else y)))
(def Numbers-min (fn [x y] (cond (h-nan? x) x (h-nan? y) y (< x y) x :else y)))
(def Numbers-abs (host-fn 'abs (fn [x] (if (neg? x) (- x) x))))
(def Numbers-num (fn [x] (if (number? x) x (throw (host/ex* 'java.lang.ClassCastException (cast-message 'java.lang.Number x))))))
(def Numbers-and bit-and)
(def Numbers-or bit-or)
(def Numbers-xor bit-xor)
(def Numbers-not bit-not)
(def Numbers-andNot (host-fn 'bit-and-not (fn [x y] (bit-and x (bit-not y)))))
(def Numbers-shiftLeft bit-shift-left)
(def Numbers-shiftRight bit-shift-right)
(def Numbers-unsignedShiftRight (host-fn 'unsigned-bit-shift-right bit-shift-right))
(def Numbers-clearBit (host-fn 'bit-clear (fn [x n] (bit-and x (bit-not (bit-shift-left 1 n))))))
(def Numbers-setBit (host-fn 'bit-set (fn [x n] (bit-or x (bit-shift-left 1 n)))))
(def Numbers-flipBit (host-fn 'bit-flip (fn [x n] (bit-xor x (bit-shift-left 1 n)))))
(def Numbers-testBit (host-fn 'bit-test (fn [x n] (not (zero? (bit-and x (bit-shift-left 1 n)))))))
(def Numbers-unchecked_add (host-fn 'unchecked-add +))
(def Numbers-unchecked_negate (host-fn 'unchecked-negate -))
;; Numbers.unchecked_minus(x) is unchecked-negate's: the host's
;; unchecked-subtract has no 1-arity on every host (the JVM's has only [x y])
(def Numbers-unchecked_minus (let [sub (host-fn 'unchecked-subtract -)]
                               (fn ([x] (Numbers-unchecked_negate x)) ([x y] (sub x y)))))
(def Numbers-unchecked_multiply (host-fn 'unchecked-multiply *))
(def Numbers-unchecked_inc (host-fn 'unchecked-inc inc))
(def Numbers-unchecked_dec (host-fn 'unchecked-dec dec))
;; the int family is 32-bit two's complement arithmetic (wrap32, above)
(def Numbers-unchecked_int_divide (fn [x y] (wrap32 (quot (wrap32 x) (wrap32 y)))))
(def Numbers-unchecked_int_add (fn [x y] (wrap32 (+ (wrap32 x) (wrap32 y)))))
(def Numbers-unchecked_int_subtract (fn [x y] (wrap32 (- (wrap32 x) (wrap32 y)))))
(def Numbers-unchecked_int_multiply (fn [x y] (wrap32 (* (wrap32 x) (wrap32 y)))))
(def Numbers-unchecked_int_inc (fn [x] (wrap32 (inc (wrap32 x)))))
(def Numbers-unchecked_int_dec (fn [x] (wrap32 (dec (wrap32 x)))))
(def Numbers-unchecked_int_negate (fn [x] (wrap32 (- (wrap32 x)))))
(def Numbers-unchecked_int_remainder (fn [x y] (rem (wrap32 x) (wrap32 y))))
(def Numbers-toBigInt (host-fn 'bigint identity))
(def Numbers-toBigDecimal (host-fn 'bigdec identity))
(def Numbers-rationalize (host-fn 'rationalize identity))
(def Numbers-booleans identity)
(def Numbers-bytes identity)
(def Numbers-chars identity)
(def Numbers-shorts identity)
(def Numbers-floats identity)
(def Numbers-ints identity)
(def Numbers-doubles identity)
(def Numbers-longs identity)
;; a primitive array: every element cast like the JVM's (int-array 2 1.5) -> [1 1]
(defn- typed-array [zero cast]
  (fn ([size-or-seq] (if (number? size-or-seq)
                       (to-array* (repeat size-or-seq zero))
                       (to-array* (map cast size-or-seq))))
    ([size init-or-seq] (if (or (coll? init-or-seq) (string? init-or-seq))
                          (to-array* (take size (concat (map cast init-or-seq) (repeat zero))))
                          (to-array* (repeat size (cast init-or-seq)))))))
(def Numbers-boolean_array (typed-array false boolean))
(def Numbers-byte_array (typed-array 0 RT-byteCast))
(def Numbers-char_array (typed-array (RT-charCast 0) RT-charCast))
(def Numbers-short_array (typed-array 0 RT-shortCast))
(def Numbers-float_array (typed-array 0.0 RT-floatCast))
(def Numbers-int_array (typed-array 0 RT-intCast))
(def Numbers-double_array (typed-array 0.0 RT-doubleCast))
(def Numbers-long_array (typed-array 0 RT-longCast))
(def Numbers-isNaN h-nan?)
(def Numbers-isInfinite h-infinite?)

;; ---------------------------------------------------------------------------
;; Util

(def ^:private host-reducers
  "{stock fn -> the host's own}: core's + and * (cohost.boot sets it after the
   boot). reduce calls its f with 0 or 2 arguments, where the stock fn is the
   host's, and a host reduce over its own + open-codes the step."
  (atom {}))
(defn set-host-reducers! [m] (reset! host-reducers m))
(defn- host-reducer [f] (get @host-reducers f f))

;; the host's reduce over coll: Phel's over a map walks its values, so a map's
;; entries, its seq, are walked there
(defn- host-reduce
  ([f coll] (reduce f #?(:phel (if (map? coll) (seq coll) coll) :default coll)))
  ([f init coll] (reduce f init #?(:phel (if (map? coll) (seq coll) coll) :default coll))))
(def protocols-coll-reduce (fn ([coll f] (host-reduce (host-reducer f) coll)) ([coll f init] (host-reduce (host-reducer f) init coll))))
(declare -kvreduce)
(def protocols-kv-reduce (fn [coll f init] (-kvreduce coll f init)))
(def protocols-iterator-reduce! (fn ([it f] (reduce f (RT-chunkIteratorSeq it))) ([it f init] (reduce f init (RT-chunkIteratorSeq it)))))
(def RT-REQUIRE_LOCK (atom ::require-lock))
(def TimeUnit-MILLISECONDS :milliseconds)
(def TimeUnit-SECONDS :seconds)
(def TimeUnit-NANOSECONDS :nanoseconds)

(def Util-equiv =)
(def Util-equals =)
(def Util-compare compare)
;; Util.hash is Object.hashCode (nil is 0), not hasheq; hashCombine is boost's,
;; in 32-bit int arithmetic (0x9e3779b9 as an int)
(declare -hashCode)
(def Util-hash (fn [x] (if (nil? x) 0 (-hashCode x))))
(def Util-hashCombine (fn [seed h]
                        (let [seed (wrap32 seed)]
                          (bit-xor seed (wrap32 (+ (wrap32 h) -1640531527 (wrap32 (bit-shift-left seed 6)) (bit-shift-right seed 2)))))))
(def Util-hasheq hash)
;; JVM keywords are interned, so equal keywords are identical; a host that
;; does not intern them (ClojureScript) answers by equality for keywords
(def Util-identical (let [h (host-fn 'identical? (fn [a b] (identical? a b)))]
                      (fn [a b] (or (h a b) (and (keyword? a) (keyword? b) (= a b))))))
(def Util-classOf class-of)
(def Util-isInteger integer?)

;; ---------------------------------------------------------------------------
;; Var, Keyword, Symbol, Namespace, Compiler

(def Var-pushThreadBindings (fn [m] (host/push-thread-bindings! m)))
(declare Var-popThreadBindings)
(def Var-getThreadBindings (host-fn 'get-thread-bindings (fn [] (host/thread-bindings))))
(def Var-cloneThreadBindingFrame (fn [] nil))
(def Var-resetThreadBindingFrame (fn [_] nil))
;; Var.intern(ns, sym) creates the namespace and leaves the var unbound (the
;; host's 2-arity intern); Var.intern(ns, sym, root) binds it
(def Var-intern (let [in (host-fn 'intern nil) cn (host-fn 'create-ns (fn [_] nil))
                      nn (host-fn 'ns-name identity)
                      ns-sym (fn [ns] (if (h-namespace? ns) (nn ns) ns))]
                  (fn ([ns sym] (cn (ns-sym ns))
                                (if in (in (ns-sym ns) sym) (host/intern! (ns-sym ns) sym nil)))
                    ([ns sym val] (cn (ns-sym ns)) (host/intern! (ns-sym ns) sym val)))))
(def Var-find (host-fn 'find-var (fn [sym] (get (host/ns-interns-map (symbol (namespace sym))) (symbol (name sym))))))
;; Var.create(): an anonymous var (with-local-vars). The hosts have no
;; anonymous vars, so one is interned under a fresh name in a scratch namespace
;; and made dynamic, which is what pushThreadBindings asks of it.
(def ^:private locals-ns (delay (host/ensure-bare-ns! 'cohost.locals) 'cohost.locals))
(def ^:private var-create* (let [in (host-fn 'intern nil)]
                             (fn var-create
                               ([] (host/set-dynamic! (if in
                                                        (in @locals-ns (gensym "local__"))
                                                        (host/intern! @locals-ns (gensym "local__") nil))))
                               ([x] (host/set-dynamic! (host/intern! @locals-ns (gensym "local__") x))))))
;; Var.create's vars are anonymous on the JVM; here each is interned in
;; cohost.locals, and leaves it when a binding frame pops and no binding in
;; this thread holds it (the var object stays usable)
(def ^:private live-locals (atom 0))
(def Var-create (fn [& args] (swap! live-locals inc) (apply var-create* args)))
(def Var-popThreadBindings
  ;; (ClojureScript's ns-unmap is a compile-time macro: its locals stay)
  (let [unmap #?(:cljs nil :default (host-fn 'ns-unmap nil))]
    (fn []
      (host/pop-thread-bindings!)
      (when (and unmap (pos? @live-locals))
        (let [left (reduce (fn [n [sym v]]
                             (if (host/var-thread-bound? v) (inc n) (do (unmap @locals-ns sym) n)))
                           0 (seq (host/ns-interns-map @locals-ns)))]
          (reset! live-locals left))))))

(def Keyword-intern (fn ([x] (if (symbol? x) (keyword (namespace x) (name x)) (keyword x)))
                      ([ns n] (keyword ns n))))
(def Keyword-find (let [fk (host-fn 'find-keyword nil)]
                    (fn ([x] (if fk (if (symbol? x) (fk (namespace x) (name x)) (fk x)) (Keyword-intern x)))
                      ([ns n] (if fk (fk ns n) (keyword ns n))))))
(def Symbol-intern (fn ([s] (symbol s)) ([ns n] (symbol ns n))))
(def Symbol-create (fn ([s] (symbol nil s)) ([ns n] (symbol ns n))))
(def Namespace-find (host-fn 'find-ns (unsupported "find-ns")))
(def Namespace-findOrCreate (host-fn 'create-ns (unsupported "create-ns")))
(def Namespace-all (host-fn 'all-ns (fn [] [])))
(def Namespace-remove (host-fn 'remove-ns (unsupported "remove-ns")))

(def eval-hook
  "Set by cohost.boot: eval through the codemod, in cohost.core."
  (atom (fn [form] (host/eval-form form))))
(def Compiler-eval (fn [form] (@eval-hook form)))
(def Compiler-load (unsupported "Compiler/load"))
;; macroexpand-1 is the macro's own expansion: the codemod's rewrite of it is
;; for the compiler, which expands the form again when it is evaluated
(def Compiler-macroexpand1 (fn [form] (binding [host/*raw-macro-output* true] (host/macroexpand-1* form))))
(def Compiler-maybeResolveIn (let [r (host-fn 'ns-resolve nil)] (fn [ns sym] (if r (r ns sym) (get (host/ns-interns-map ns) sym)))))
(def Compiler-specials
  '#{def if do let* loop* recur fn* quote var throw try catch finally monitor-enter monitor-exit
     . new set! deftype* reify* letfn* case* & clojure.core/import*})
(def Compiler-munge (host-fn 'munge identity))
;; Compiler.demunge: each munged token back to its character ("_" is "-",
;; "_QMARK_" is "?", "$" is "/"), the longest token first, as the JVM does
(def ^:private demunge-map
  {"_COLON_" ":" "_PLUS_" "+" "_GT_" ">" "_LT_" "<" "_EQ_" "=" "_TILDE_" "~" "_BANG_" "!"
   "_CIRCA_" "@" "_SHARP_" "#" "_SINGLEQUOTE_" "'" "_DOUBLEQUOTE_" "\"" "_PERCENT_" "%"
   "_CARET_" "^" "_AMPERSAND_" "&" "_STAR_" "*" "_BAR_" "|" "_LBRACE_" "{" "_RBRACE_" "}"
   "_LBRACK_" "[" "_RBRACK_" "]" "_SLASH_" "/" "_BSLASH_" "\\" "_QMARK_" "?" "_" "-" "$" "/"})
(def ^:private demunge-tokens (sort-by (comp - count) (keys demunge-map)))
(def Compiler-demunge
  (fn [s]
    (let [s (str s) n (count s)]
      (loop [i 0 out []]
        (if (>= i n)
          (apply str out)
          (if-let [t (some (fn [t] (when (and (<= (+ i (count t)) n) (= t (subs s i (+ i (count t))))) t)) demunge-tokens)]
            (recur (+ i (count t)) (conj out (get demunge-map t)))
            (recur (inc i) (conj out (subs s i (inc i))))))))))
(def Compiler_HostExpr-maybeClass (fn [& _] nil))
(def Compiler_HostExpr-maybeSpecialTag (fn [& _] nil))
(def Reflector-prepRet (fn [_c x] x))
(def Agent-shutdown (fn [] nil))
(def Agent-releasePendingSends (fn [] 0))

;; ---------------------------------------------------------------------------
;; collections: statics and constructors

(def PersistentList-creator list)
(def PersistentHashMap-create (fn [kvs] (apply hash-map (seq kvs))))
(def PersistentHashMap-createWithCheck
  (fn [kvs]
    (let [ks (take-nth 2 (seq kvs))]
      (when (not= (count ks) (count (set ks)))
        (throw (host/ex* 'IllegalArgumentException (str "Duplicate key: " (some (fn [[k n]] (when (> n 1) k)) (seq (frequencies ks)))))))
      (apply hash-map (seq kvs)))))
(def PersistentArrayMap-EMPTY {})
(def PersistentArrayMap-create (fn [m] (into {} m)))
(def ^:private h-array-map (host-fn 'array-map nil))
(def PersistentArrayMap-createAsIfByAssoc
  (fn [arr]
    ;; an odd count ends in a trailing element (1.11's trailing map for
    ;; & {:keys ...}) taken as the JVM takes it, EMPTY.cons(x): nil adds
    ;; nothing, a [k v] pair or a map its entries, anything else is an error
    (let [s (seq arr)
          odd (odd? (count s))
          tail (when odd (conj {} (last s)))]
      ;; the tail's entries follow the pairs; each key keeps its first
      ;; position and its last value, in the host's array map, which keeps
      ;; that order at any size (assoc would make a hash map past 8 entries)
      (let [kvs (concat (partition 2 (if odd (butlast s) s)) (seq tail))]
        (if h-array-map
          (let [last-v (reduce (fn [m [k v]] (assoc m k v)) {} kvs)]
            (apply h-array-map (mapcat (fn [k] [k (get last-v k)]) (distinct (map first kvs)))))
          (reduce (fn [m [k v]] (assoc m k v)) {} kvs))))))
(def PersistentArrayMap-createWithCheck PersistentHashMap-createWithCheck)
(def PersistentHashSet-create (fn [coll] (set coll)))
(def PersistentHashSet-createWithCheck
  (fn [coll]
    (let [s (seq coll)]
      (when (not= (count s) (count (set s)))
        (throw (host/ex* 'IllegalArgumentException (str "Duplicate key: " (some (fn [[k n]] (when (> n 1) k)) (seq (frequencies s)))))))
      (set s))))
(def PersistentTreeMap-create
  (let [sm (host-fn 'sorted-map nil) smb (host-fn 'sorted-map-by nil)]
    (fn ([kvs] (if sm (apply sm (seq kvs)) ((unsupported "sorted-map"))))
      ([cmp kvs] (if smb (apply smb cmp (seq kvs)) ((unsupported "sorted-map-by")))))))
(def PersistentTreeSet-create
  (let [ss (host-fn 'sorted-set nil) ssb (host-fn 'sorted-set-by nil)]
    (fn ([coll] (if ss (apply ss (seq coll)) ((unsupported "sorted-set"))))
      ([cmp coll] (if ssb (apply ssb cmp (seq coll)) ((unsupported "sorted-set-by")))))))
(def LazilyPersistentVector-create (fn [coll] (vec coll)))
;; createOwning(Object... items): called from Clojure with one array, the items
(def LazilyPersistentVector-createOwning (fn [arr] (vec arr)))
(def MapEntry-create (fn [k v] (first (hash-map k v))))
(def new-MapEntry MapEntry-create)

;; static fields a program may set! (clojure.lang.RT/checkSpecAsserts)
(def ^:private statics (atom {"RT-checkSpecAsserts" false}))
(defn static-get [nm] (get @statics nm))
(defn static-set! [nm v] (swap! statics assoc nm v) v)
(def EdnReader-readString (fn [s opts] (host/edn-read-string opts s)))
(def PersistentVector-create (fn [coll] (vec coll)))
(def PersistentVector-EMPTY [])
(def Range-create (fn ([end] (range end)) ([start end] (range start end)) ([start end step] (range start end step))))
(def LongRange-create Range-create)
(def Repeat-create (fn ([x] (repeat x)) ([n x] (repeat n x))))
(def Cycle-create (fn [coll] (cycle coll)))
(def Iterate-create (fn [f x] (iterate f x)))
;; clojure.lang.TransformerIterator: the outputs of a transducer pulled from
;; iterator(s) on demand. Written here rather than over the host's `sequence`
;; so every host gets the JVM's buffering and completion (basilisp's sequence
;; completes the transducer per element: (partition-all 2) gave singletons).
(defn- xform-seq [xform its]
  (let [out (atom [])
        done (atom false)
        rf (xform (fn ([] nil) ([acc] acc) ([acc x] (swap! out conj x) acc)))
        finish! (fn [] (when-not @done (reset! done true) (rf nil)))
        pull! (fn []
                (loop []
                  (when (and (empty? @out) (not @done))
                    (if (every? host/iter-has-next? its)
                      (let [r (apply rf nil (map host/iter-next! its))]
                        (when (h-reduced? r) (finish!))
                        (recur))
                      (finish!)))))
        step (fn step []
               (host/lazy-seq*
                (fn []
                  (pull!)
                  (when (seq @out)
                    (let [x (first @out)]
                      (swap! out subvec 1)
                      (cons x (step)))))))]
    (step)))
(def TransformerIterator-create (fn [xform it] (host/iter (xform-seq xform [it]))))
(def TransformerIterator-createMulti (fn [xform its] (host/iter (xform-seq xform (vec its)))))
(def Murmur3-hashOrdered (host-fn 'hash-ordered-coll (fn [coll] (hash (vec coll)))))
(def Murmur3-hashUnordered (host-fn 'hash-unordered-coll (fn [coll] (hash (set coll)))))
(def Murmur3-mixCollHash (host-fn 'mix-collection-hash (fn [h _n] h)))
(def BigInt-fromBigInteger identity)
(def BigInt-valueOf (host-fn 'bigint identity))
(def LispReader-read (let [rd (host-fn 'read (unsupported "read"))] (fn ([rdr opts] (rd opts rdr)) ([rdr eof-error? eof-val _recursive?] (rd rdr eof-error? eof-val)))))
(def TaggedLiteral-create (host-fn 'tagged-literal (fn [tag form] {:tag tag :form form})))
(def ReaderConditional-create (host-fn 'reader-conditional (fn [form splicing?] {:form form :splicing? splicing?})))
(def Delay-force (host-fn 'force (fn [x] (if (h-delay? x) (deref x) x))))
(def PersistentQueue-EMPTY (host/empty-queue))

(def new-LazySeq (host/capability :lazy-seq))   ; per lazy cell: no accessor lookup
(def new-Delay (host/capability :delay))
(def new-Atom host/atom*)
(def new-Volatile (host-fn 'volatile! (unsupported "volatile!")))
(def new-Reduced (host-fn 'reduced (unsupported "reduced")))
;; clojure.lang.Ref on a host without refs (basilisp, ClojureScript): a host
;; atom, and a transaction that runs its body once (one thread: nothing to
;; retry). A write outside a transaction is the JVM's IllegalStateException,
;; and a transaction that throws puts back every ref it wrote. (A watch fires
;; at each write, where the JVM fires it once, at the commit.) The stock ref,
;; ref-set, alter, commute, ensure, sync, dosync and io! boot over these.
(def new-Ref (host-fn 'ref (fn ([x] (host/atom* x)) ([x m] (host/atom* x m)))))
(def ^:private tx (atom nil))
(defn- in-tx! [] (when (nil? @tx) (throw (host/ex* 'java.lang.IllegalStateException "No transaction running"))))
(defn- tx-write! [r f]
  (in-tx!)
  (when-not (contains? @tx r) (swap! tx assoc r @r))
  (f))
(def LockingTransaction-runInTransaction
  (fn [f]
    (if (some? @tx)
      (f)
      (do (reset! tx {})
          (try (host/try-all f
                             (fn [e]
                               (let [written @tx]
                                 (reset! tx nil)
                                 (doseq [[r v] written] (reset! r v)))
                               (throw e)))
               (finally (reset! tx nil)))))))
(def LockingTransaction-isRunning (fn [] (some? @tx)))
(def new-Agent (host-fn 'agent (unsupported "agent")))
;; clojure.lang.MultiFn, stock: the state (name, dispatch fn, default,
;; hierarchy var, methods, prefers) rides in the fn's metadata, and dispatch
;; is MultiFn.findAndCacheBestMethod's: the methods whose dispatch value the
;; argument's isa, the dominant one by prefers or isa, ambiguity an error.
(def ^:private h-host-isa? (host-fn 'isa? nil))
;; a host class value: what `class` returns for a type user code defined
(def ^:private host-class-value?
  #?(:cljs (fn [x] (and (fn? x) (true? (.-cljs$lang$type x))))
     :lpy (fn [x] (instance? python/type x))
     ;; (Phel: a user type's class is the token of its PHP class, type-of's)
     :phel (fn [x] (and (symbol? x) (php/class_exists (php/str_replace "." "\\" (str x)) false)))
     :default (host-fn 'class? (constantly false))))
(defn- h-isa? [h child parent]
  (boolean
   (or (= child parent)
       (and (class-token? parent) (class-token? child) (token-isa? child parent))
       ;; a type user code defined (the host's own class) under a class token:
       ;; every class is an Object; the rest is the host's class hierarchy
       (and (class-token? parent) (host-class-value? child)
            (or (= 'java.lang.Object (canon parent))
                (boolean (when-let [hc (and h-host-isa? (host/host-class (canon parent)))]
                           (h-host-isa? child hc)))))
       (contains? (get (:ancestors h) child #{}) parent)
       (and (class-token? child) (some #(contains? (get (:ancestors h) % #{}) parent) (class-supers child)))
       (and (vector? parent) (vector? child) (= (count parent) (count child))
            (every? true? (map #(h-isa? h %1 %2) child parent))))))
(defn- mf-parents [h x]
  (concat (get (:parents h) x) (when (class-token? x) (class-bases x))))
(defn- mf-prefers?
  "MultiFn.prefers: x is preferred to y directly, or to a parent of y, or a
   parent of x is preferred to y."
  [prefers h x y]
  (boolean (or (contains? (get prefers x #{}) y)
               (some #(mf-prefers? prefers h x %) (mf-parents h y))
               (some #(mf-prefers? prefers h % y) (mf-parents h x)))))
(defn- mf-dominates? [prefers h x y] (or (mf-prefers? prefers h x y) (h-isa? h x y)))
(defn- mf-find-method [{:keys [name methods prefers default hierarchy]} dv]
  (let [h (deref hierarchy)
        best (reduce (fn [best e]
                       (let [k (first e)]
                         (cond (not (h-isa? h dv k)) best
                               (nil? best) e
                               (mf-dominates? prefers h k (key best)) e
                               (mf-dominates? prefers h (key best) k) best
                               :else (throw (host/ex* 'IllegalArgumentException
                                                      (str "Multiple methods in multimethod '" name "' match dispatch value: " dv
                                                           " -> " k " and " (key best) ", and neither is preferred"))))))
                     ;; (seq: Phel's reduce over a map walks its values)
                     nil (seq methods))]
    (if best (val best) (get methods default))))
(defn- mf-method
  "The method for dispatch value dv, cached per dv as the JVM's MultiFn caches
   it: the cache holds while the methods, the prefers and the hierarchy's
   value are the ones it was filled under (addMethod, preferMethod, reset and
   derive each make a new one)."
  [state dv]
  (let [st @state
        h (deref (:hierarchy st))
        c (:cache st)]
    (if (and c (identical? (:methods c) (:methods st)) (identical? (:prefers c) (:prefers st)) (identical? (:h c) h))
      (let [e (find (:table c) dv)]
        (if e
          (val e)
          (let [m (mf-find-method st dv)]
            ;; a miss is not cached, as the JVM's findAndCacheBestMethod: an
            ;; equal dispatch value may still isa? its way to a method
            (when m (swap! state update :cache (fn [c'] (if (identical? c' c) (assoc-in c [:table dv] m) c'))))
            m)))
      (let [m (mf-find-method st dv)]
        (swap! state assoc :cache {:methods (:methods st) :prefers (:prefers st) :h h :table (if m {dv m} {})})
        m))))

(def new-MultiFn
  (fn [name dispatch-fn default hierarchy]
    (let [state (atom {:name name :dispatch-fn dispatch-fn :default default :hierarchy hierarchy :methods {} :prefers {}})
          no-method (fn [dv] (throw (host/ex* 'IllegalArgumentException (str "No method in multimethod '" name "' for dispatch value: " dv))))]
      ((fn [f] (if fn-meta? (with-meta f {::multifn state}) (do (swap! mf-states assoc f state) f)))
        (fn
          ([] (let [dv (dispatch-fn) m (mf-method state dv)] (if m (m) (no-method dv))))
          ([a] (let [dv (dispatch-fn a) m (mf-method state dv)] (if m (m a) (no-method dv))))
          ([a b] (let [dv (dispatch-fn a b) m (mf-method state dv)] (if m (m a b) (no-method dv))))
          ([a b c] (let [dv (dispatch-fn a b c) m (mf-method state dv)] (if m (m a b c) (no-method dv))))
          ([a b c & more] (let [dv (apply dispatch-fn a b c more) m (mf-method state dv)]
                            (if m (apply m a b c more) (no-method dv)))))))))
(def new-Object (fn [] (atom ::object)))
(def new-ExceptionInfo host/ex-info*)
;; (Exception.) (Exception. msg) (Exception. cause) (Exception. msg cause)
(defn- ex-ctor [klass]
  (fn ([] (host/ex* klass nil))
    ([x] (if (h-exception? x) (host/ex* klass (str x) x) (host/ex* klass x)))
    ([msg cause] (host/ex* klass msg cause))))
(def new-Exception (ex-ctor 'java.lang.Exception))
(def new-RuntimeException (ex-ctor 'java.lang.RuntimeException))
(def new-Error (ex-ctor 'java.lang.Error))
(def new-Throwable (ex-ctor 'java.lang.Throwable))
(def new-AssertionError (ex-ctor 'java.lang.AssertionError))
(def new-IllegalArgumentException (ex-ctor 'java.lang.IllegalArgumentException))
(def new-IllegalStateException (ex-ctor 'java.lang.IllegalStateException))
(def new-UnsupportedOperationException (ex-ctor 'java.lang.UnsupportedOperationException))
(def new-ArithmeticException (ex-ctor 'java.lang.ArithmeticException))
(def new-ClassCastException (ex-ctor 'java.lang.ClassCastException))
(def new-IndexOutOfBoundsException (ex-ctor 'java.lang.IndexOutOfBoundsException))
(def new-NumberFormatException (ex-ctor 'java.lang.NumberFormatException))
(def new-InterruptedException (ex-ctor 'java.lang.InterruptedException))
(def new-IllegalAccessError (ex-ctor 'java.lang.IllegalAccessError))
(def new-NullPointerException (ex-ctor 'java.lang.NullPointerException))
(def new-Compiler_CompilerException (ex-ctor 'clojure.lang.Compiler$CompilerException))
;; StringBuilder() / (int capacity) / (CharSequence s); StringBuffer is the same here
(def new-StringBuilder (fn ([] (h-string-builder))
                         ([s] (cond (number? s) (h-string-builder)
                                    (nil? s) (throw (host/ex* 'java.lang.NullPointerException "Cannot invoke \"String.length()\" because \"str\" is null"))
                                    :else (h-sb-append! (h-string-builder) s)))))
(def new-StringBuffer new-StringBuilder)
(def new-BigDecimal (host-fn 'bigdec identity))
(def new-BigInteger (host-fn 'bigint identity))
(def new-ChunkBuffer (if-let [f (:chunk-buffer hc)] f (fn [_capacity] (host/mutable-list))))
(def new-ArrayList (fn ([] (host/mutable-list)) ([n-or-coll] (let [l (host/mutable-list)] (when (coll? n-or-coll) (swap! (:state l) into n-or-coll)) l))))
(def new-LinkedList new-ArrayList)
;; ChunkedCons: the seq is chunk ++ more; the chunk and the rest ride its
;; metadata, so chunk-first sees the whole chunk and chunk-rest the rest
(def new-ChunkedCons (or (:chunk-cons hc)
                         (fn [chunk more] (with-meta (concat (seq chunk) more) {::chunk chunk ::more more}))))
(defn chunked-cons? [x] (and (seq? x) (contains? (meta x) ::chunk)))
(def new-Thread (unsupported "java.lang.Thread"))
;; Thread.currentThread(): its stack trace is empty, like every trace here
(def Thread-currentThread (let [t (atom ::current-thread)] (fn [] t)))
(def new-CountDownLatch (unsupported "CountDownLatch"))

;; ---------------------------------------------------------------------------
;; java.lang & java.util statics

(def Boolean-TRUE true)
(def Boolean-FALSE false)
(def Integer-MAX_VALUE 2147483647)
(def Integer-MIN_VALUE -2147483648)
(def Integer-TYPE 'int)
(def Long-TYPE 'long)
(def Byte-TYPE 'byte)
(def Short-TYPE 'short)
(def Float-TYPE 'float)
(def Double-TYPE 'double)
(def Character-TYPE 'char)
(def Boolean-TYPE 'boolean)
(def Long-MAX_VALUE 9223372036854775807)
(def Long-MIN_VALUE -9223372036854775808)
(def Double-NaN (host/parse-double* "NaN"))
(def Double-MAX_VALUE 1.7976931348623157E308)
(def Double-MIN_VALUE 4.9E-324)
(def Double-MIN_NORMAL 2.2250738585072014E-308)
(def Float-MAX_VALUE 3.4028234663852886E38)
(def Float-MIN_VALUE 1.401298464324817E-45)
;; Long.toString(x, radix) / toHexString & co: the JVM's digits (the unsigned
;; forms read a negative number as its two's complement)
(def ^:private radix-digits "0123456789abcdefghijklmnopqrstuvwxyz")
(defn- radix-str [x radix]
  (let [radix (if (<= 2 radix 36) radix 10)]
    (if (zero? x)
      "0"
      (loop [v (if (neg? x) (Numbers-minusP x) x) acc ()]
        (if (zero? v)
          (apply str (if (neg? x) (cons "-" acc) acc))
          (recur (quot v radix) (cons (nth radix-digits (rem v radix)) acc)))))))
(defn- unsigned [x bits]
  (if (neg? x) (Numbers-addP x (if (= bits 32) 4294967296 18446744073709551616)) x))
(def Long-toString (fn ([x] (str x)) ([x radix] (radix-str x radix))))
(def Integer-toString Long-toString)
(def Integer-toHexString (fn [x] (radix-str (unsigned x 32) 16)))
(def Integer-toOctalString (fn [x] (radix-str (unsigned x 32) 8)))
(def Integer-toBinaryString (fn [x] (radix-str (unsigned x 32) 2)))
(def Long-toHexString (fn [x] (radix-str (unsigned x 64) 16)))
(def Long-toOctalString (fn [x] (radix-str (unsigned x 64) 8)))
(def Long-toBinaryString (fn [x] (radix-str (unsigned x 64) 2)))
(defn- pop-count [x bits] (loop [v (unsigned x bits) c 0] (if (zero? v) c (recur (quot v 2) (+ c (rem v 2))))))
(def Long-bitCount (fn [x] (pop-count x 64)))
(def Integer-bitCount (fn [x] (pop-count x 32)))
(def Long-compare (fn [a b] (compare a b)))
(def Integer-compare Long-compare)
(def Double-compare Long-compare)
(def Long-signum (fn [x] (cond (pos? x) 1 (neg? x) -1 :else 0)))
(def Integer-signum Long-signum)
(def Double-toString (fn [x] (str x)))
(def String-join (fn [sep coll] (apply str (interpose sep coll))))
(def Double-POSITIVE_INFINITY (/ 1.0 0.0))
(def Double-NEGATIVE_INFINITY (/ -1.0 0.0))
;; the JVM's number grammars: a host's parser is looser (parseInt "12abc" -> 12,
;; float("abc") raises ValueError), so the string is checked here first
(defn- nfe
  ([s] (nfe s 10))
  ([s radix] (host/ex* 'java.lang.NumberFormatException
                       (if (nil? s)
                         "Cannot parse null string"
                         (str "For input string: \"" s "\"" (when-not (= 10 radix) (str " under radix " radix)))))))
(defn- digit-of
  "Character.digit for the ASCII digits and letters: the value, or nil."
  [c radix]
  (let [n (h-char-code c)
        d (cond (<= 48 n 57) (- n 48) (<= 97 n 122) (- n 87) (<= 65 n 90) (- n 55) :else 99)]
    (when (< d radix) d)))
(defn- parse-radix
  "Long.parseLong / Integer.parseInt: an optional sign and the radix's digits,
   in [lo, hi], else NumberFormatException."
  [s radix lo hi]
  (when-not (string? s) (throw (nfe nil)))
  (let [n (count s)
        c0 (when (pos? n) (str (nth s 0)))
        start (if (contains? #{"-" "+"} c0) 1 0)]
    (when (< radix 2) (throw (host/ex* 'java.lang.NumberFormatException (str "radix " radix " less than Character.MIN_RADIX"))))
    (when (> radix 36) (throw (host/ex* 'java.lang.NumberFormatException (str "radix " radix " greater than Character.MAX_RADIX"))))
    (when (= start n) (throw (nfe s radix)))
    (loop [i start acc 0]
      (if (< i n)
        (if-let [d (digit-of (nth s i) radix)]
          (recur (inc i) (Numbers-addP (Numbers-multiplyP acc radix) d))
          (throw (nfe s radix)))
        (let [v (if (= "-" c0) (Numbers-minusP acc) acc)]
          (if (or (< v lo) (> v hi)) (throw (nfe s radix)) v))))))
(def ^:private lmin -9223372036854775808)
(def ^:private lmax 9223372036854775807)
(defn- parse-long-strict [s] (parse-radix s 10 lmin lmax))
(declare Math-pow)
(defn- parse-hex-double [sign ip fp ex]
  (let [mant (reduce (fn [acc c] (Numbers-addP (Numbers-multiplyP acc 16) (digit-of c 16))) 0 (str ip fp))
        e (- (parse-radix ex 10 lmin lmax) (* 4 (count fp)))
        v (* (RT-doubleCast mant) (Math-pow 2.0 (RT-doubleCast e)))]
    (if (= "-" sign) (- v) v)))
(defn- parse-double-strict [s]
  (cond
    (not (string? s)) (throw (nfe s))
    :else
    (if-let [m (re-matches #"\s*([+-]?(NaN|Infinity|(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?))[fFdD]?\s*" s)]
      (host/parse-double* (second m))
      ;; Java's HexFloatingPointLiteral: 0x1.8p1
      (if-let [[_ sign ip fp ex] (re-matches #"\s*([+-]?)0[xX]([0-9a-fA-F]*)\.?([0-9a-fA-F]*)[pP]([+-]?\d+)[fFdD]?\s*" s)]
        (if (and (empty? ip) (empty? fp)) (throw (nfe s)) (parse-hex-double sign ip fp ex))
        (throw (nfe s))))))
(def Integer-parseInt (fn ([s] (parse-radix s 10 -2147483648 2147483647)) ([s radix] (parse-radix s radix -2147483648 2147483647))))
(def Long-parseLong (fn ([s] (parse-radix s 10 lmin lmax)) ([s radix] (parse-radix s radix lmin lmax))))
(def Integer-valueOf (fn ([x] (if (string? x) (Integer-parseInt x) (RT-intCast x))) ([s radix] (Integer-parseInt s radix))))
(def Long-valueOf (fn ([x] (if (string? x) (Long-parseLong x) (RT-longCast x))) ([s radix] (Long-parseLong s radix))))
(def Math-toIntExact (fn [x] (if (or (< x -2147483648) (> x 2147483647))
                               (throw (host/ex* 'java.lang.ArithmeticException "integer overflow"))
                               x)))
(def BigInteger-valueOf (host-fn 'biginteger identity))
(def Double-valueOf (fn [x] (if (string? x) (parse-double-strict x) (RT-doubleCast x))))
(def Double-parseDouble parse-double-strict)
(declare nn)
(def Double-isNaN (fn [x] (h-nan? (nn x))))
(def Double-isInfinite (fn [x] (h-infinite? (nn x))))
(def Boolean-parseBoolean (fn [s] (and (string? s) (= "true" (h-lower-case s)))))
(def Boolean-valueOf (fn [x] (if (string? x) (Boolean-parseBoolean x) (boolean x))))
;; Character: a char or an int code point; the host's own statics where the
;; adapter names them (:java-statics), else these, over the char code
(defn- code-of [c] (if (number? c) c (h-char-code c)))
(defn- jstatic [nm dflt] (get (host/java-statics) nm dflt))
(def Character-isWhitespace
  (jstatic 'Character/isWhitespace
           ;; java.lang.Character.isWhitespace: the space, line and paragraph
           ;; separators but the non-breaking ones, plus \t \n VT \f \r FS GS RS US
           (fn [c] (let [n (code-of c)]
                     (boolean (or (<= 9 n 13) (<= 28 n 32) (== n 0x1680) (<= 0x2000 n 0x2006)
                                  (<= 0x2008 n 0x200A) (== n 0x2028) (== n 0x2029) (== n 0x205F)
                                  (== n 0x3000)))))))
(defn- char-str [c] (str (if (number? c) (RT-charCast c) c)))
(defn- cased [f c] (let [s (char-str c) r (f s)] (if (= 1 (count r)) (first r) (first s))))
(def Character-isDigit (jstatic 'Character/isDigit (fn [c] (<= 48 (code-of c) 57))))
(def Character-isLetter (jstatic 'Character/isLetter (fn [c] (let [s (char-str c)] (not= (h-upper-case s) (h-lower-case s))))))
(def Character-isLetterOrDigit (jstatic 'Character/isLetterOrDigit (fn [c] (or (Character-isLetter c) (Character-isDigit c)))))
(def Character-isAlphabetic (jstatic 'Character/isAlphabetic Character-isLetter))
(def Character-isUpperCase (jstatic 'Character/isUpperCase (fn [c] (let [s (char-str c)] (and (= s (h-upper-case s)) (not= s (h-lower-case s)))))))
(def Character-isLowerCase (jstatic 'Character/isLowerCase (fn [c] (let [s (char-str c)] (and (= s (h-lower-case s)) (not= s (h-upper-case s)))))))
(def Character-toUpperCase (jstatic 'Character/toUpperCase (fn [c] (let [r (cased host/upper-case c)] (if (number? c) (h-char-code r) r)))))
(def Character-toLowerCase (jstatic 'Character/toLowerCase (fn [c] (let [r (cased host/lower-case c)] (if (number? c) (h-char-code r) r)))))
(def Character-digit (jstatic 'Character/digit (fn [c radix] (or (digit-of (if (number? c) (RT-charCast c) c) radix) -1))))
(def Character-getNumericValue (jstatic 'Character/getNumericValue (fn [c] (or (digit-of (if (number? c) (RT-charCast c) c) 36) -1))))
(def Character-forDigit (jstatic 'Character/forDigit (fn [d radix] (if (and (<= 2 radix 36) (< -1 d radix)) (nth radix-digits d) (RT-charCast 0)))))
;; java.lang.Math: the host's own functions where it has them (the adapter's
;; :math map, by JVM name), else these, which follow the JVM's definitions
(def ^:private long-min -9223372036854775808)
(def ^:private long-max 9223372036854775807)
(defn- exact [x]
  (if (or (< x long-min) (> x long-max))
    (throw (host/ex* 'java.lang.ArithmeticException "long overflow"))
    x))
(defn- math-fn [nm dflt] (or (get (host/math-fns) nm) dflt))
(defn- unsupported-math [nm] (unsupported (str "Math/" nm)))
(def Math-E 2.718281828459045)
(def Math-PI 3.141592653589793)
(def Math-ceil (math-fn 'ceil host/math-ceil))
(def Math-floor (math-fn 'floor host/math-floor))
(def Math-sqrt (math-fn 'sqrt host/math-sqrt))
(def Math-random host/random)
(def Math-abs Numbers-abs)
;; Math.max / min: NaN wins, and 0.0 is above -0.0
(defn- neg-zero? [x] (and (float? x) (zero? x) (neg? (/ 1.0 x))))
(def Math-max (fn [a b] (cond (h-nan? a) a (h-nan? b) b
                              (and (zero? a) (zero? b) (float? a) (float? b)) (if (neg-zero? a) b a)
                              :else (if (>= a b) a b))))
(def Math-min (fn [a b] (cond (h-nan? a) a (h-nan? b) b
                              (and (zero? a) (zero? b) (float? a) (float? b)) (if (neg-zero? a) a b)
                              :else (if (<= a b) a b))))
(def Math-sin (math-fn 'sin (unsupported-math "sin")))
(def Math-cos (math-fn 'cos (unsupported-math "cos")))
(def Math-tan (math-fn 'tan (unsupported-math "tan")))
(def Math-asin (math-fn 'asin (unsupported-math "asin")))
(def Math-acos (math-fn 'acos (unsupported-math "acos")))
(def Math-atan (math-fn 'atan (unsupported-math "atan")))
(def Math-atan2 (math-fn 'atan2 (unsupported-math "atan2")))
(def Math-exp (math-fn 'exp (unsupported-math "exp")))
(def Math-log (math-fn 'log (unsupported-math "log")))
(def Math-log10 (math-fn 'log10 (unsupported-math "log10")))
(def Math-pow (math-fn 'pow (unsupported-math "pow")))
(def Math-cbrt (math-fn 'cbrt (unsupported-math "cbrt")))
(def Math-hypot (math-fn 'hypot (unsupported-math "hypot")))
(def Math-sinh (math-fn 'sinh (unsupported-math "sinh")))
(def Math-cosh (math-fn 'cosh (unsupported-math "cosh")))
(def Math-tanh (math-fn 'tanh (unsupported-math "tanh")))
(def Math-expm1 (math-fn 'expm1 (unsupported-math "expm1")))
(def Math-log1p (math-fn 'log1p (unsupported-math "log1p")))
(def Math-IEEEremainder (math-fn 'IEEEremainder (unsupported-math "IEEEremainder")))
(def Math-ulp (math-fn 'ulp (unsupported-math "ulp")))
(def Math-nextUp (math-fn 'nextUp (unsupported-math "nextUp")))
(def Math-nextDown (math-fn 'nextDown (unsupported-math "nextDown")))
(def Math-nextAfter (math-fn 'nextAfter (unsupported-math "nextAfter")))
(def Math-getExponent (math-fn 'getExponent (unsupported-math "getExponent")))
(def Math-scalb (math-fn 'scalb (unsupported-math "scalb")))
(def Math-copySign (math-fn 'copySign (unsupported-math "copySign")))
(def Math-toRadians (math-fn 'toRadians (fn [a] (* (double a) (/ Math-PI 180.0)))))
(def Math-toDegrees (math-fn 'toDegrees (fn [a] (* (double a) (/ 180.0 Math-PI)))))
(def Math-signum (math-fn 'signum (fn [a] (cond (h-nan? a) a (pos? a) 1.0 (neg? a) -1.0 :else a))))
(def Math-rint (math-fn 'rint (fn [a] (let [f (Math-floor a) d (- a f)]
                                        (cond (< d 0.5) f (> d 0.5) (+ f 1.0)
                                              (even? (long f)) f :else (+ f 1.0))))))
;; Math.round: floor(a + 1/2) computed exactly (adding 0.5 rounds
;; 0.49999999999999994 up); NaN is 0, an infinity the long's bound
(def Math-round (math-fn 'round (fn [a] (cond (h-nan? a) 0
                                              (h-infinite? a) (if (pos? a) 9223372036854775807 -9223372036854775808)
                                              :else (let [a (double a) f (Math-floor a)]
                                                      (long (if (>= (- a f) 0.5) (+ f 1.0) f)))))))
(def Math-floorDiv (math-fn 'floorDiv (fn [a b] (let [q (quot a b)] (if (and (not= (neg? a) (neg? b)) (not= (* q b) a)) (dec q) q)))))
(def Math-floorMod (math-fn 'floorMod (fn [a b] (- a (* b (Math-floorDiv a b))))))
(def Math-addExact (math-fn 'addExact (fn [a b] (exact (+ a b)))))
(def Math-subtractExact (math-fn 'subtractExact (fn [a b] (exact (- a b)))))
(def Math-multiplyExact (math-fn 'multiplyExact (fn [a b] (exact (* a b)))))
(def Math-incrementExact (math-fn 'incrementExact (fn [a] (exact (+ a 1)))))
(def Math-decrementExact (math-fn 'decrementExact (fn [a] (exact (- a 1)))))
(def Math-negateExact (math-fn 'negateExact (fn [a] (exact (- a)))))
(def System-nanoTime (host/capability :nano-time))
(def System-currentTimeMillis host/current-time-millis)
(def System-getProperty (fn ([_k] nil) ([_k dflt] dflt)))
(def System-identityHashCode hash)
;; String.format(fmt, Object... args): core.clj's format passes one array
(declare jformat)
(def String-format (fn [fmt & args]
                     (let [args (if (and (= 1 (count args)) (arr? (first args))) (seq (first args)) args)]
                       (if-let [f (host/format-fn)] (apply f fmt args) (jformat fmt args)))))
(def String-valueOf (fn [x] (if (nil? x) "null" (str x))))
;; String.CASE_INSENSITIVE_ORDER compares upper- then lower-cased characters
(def String-CASE_INSENSITIVE_ORDER
  (fn [a b] (let [f (fn [s] (h-lower-case (h-upper-case (str s))))] (compare (f a) (f b)))))
(def Array-getLength count)
(defn- aioobe [i n] (host/ex* 'java.lang.ArrayIndexOutOfBoundsException (str "Index " i " out of bounds for length " n)))
(def Array-get (fn [a i] (let [n (count a)] (if (and (<= 0 i) (< i n)) #?(:phel (aget a i) :default (nth a i)) (throw (aioobe i n))))))
;; (make-array Integer/TYPE n) is zero-filled, a class token fills with nil;
;; (make-array t d0 d1 ...) passes its dimensions as an array: nested arrays
(def Array-newInstance
  (fn [t n]
    (let [zero (get '{int 0 long 0 short 0 byte 0 double 0.0 float 0.0 boolean false} t)
          build (fn build [[d & more]]
                  (to-array* (if (seq more) (repeatedly d #(build more)) (repeat d zero))))]
      (if (number? n) (build [n]) (build (vec n))))))
(def Array-set (fn [a i v] (let [n (count a)] (if (and (<= 0 i) (< i n)) (aset a i v) (throw (aioobe i n))))))
(def Array-setInt Array-set)
(def Array-setLong Array-set)
(def Array-setBoolean Array-set)
(def Array-setFloat Array-set)
;; RT.aget / RT.aset: what aget's and aset's :inline expand to
(def RT-aget Array-get)
(def RT-aset (fn [a i v] (Array-set a i v) v))
(def Array-setDouble Array-set)
(def Array-setShort Array-set)
(def Array-setByte Array-set)
(def Array-setChar Array-set)
;; java.util.Comparator over a Clojure fn (AFunction.compare): a boolean
;; result means "less than" when true, else the reverse call decides.
(defn- compare-with
  "Comparator.compare of cmp: a Clojure fn answering a number, or a boolean
   (true: a before b), or a java.util.Comparator that is no Clojure fn."
  [cmp a b]
  (if (ifn? cmp)
    (let [r (cmp a b)]
      (if (number? r) r (if r -1 (if (cmp b a) 1 0))))
    (let [r (host/invoke-member cmp "compare" [a b] absent)]
      (if (identical? r absent)
        (throw (host/ex* 'java.lang.ClassCastException (cast-message 'java.util.Comparator cmp)))
        r))))
(defn- as-comparator [cmp] (fn [a b] (compare-with cmp a b)))
(def Arrays-sort (fn ([a] (host/sort-array! a compare)) ([a cmp] (host/sort-array! a (as-comparator cmp)))))
(def Collections-shuffle (unsupported "Collections/shuffle"))
;; Collections.reverseOrder(): the host's own where it has java.util, else a
;; comparator fn that reverses compare (or the comparator given)
(def Collections-reverseOrder (jstatic 'Collections/reverseOrder
                                       (fn ([] (fn [a b] (compare b a)))
                                         ([c] (let [c (as-comparator c)] (fn [a b] (c b a)))))))
(def Pattern-compile (fn [s] (host/re-pattern* s)))

;; java.util.regex.Matcher over the adapter's :re-find-at / :re-match-full: the
;; JVM's find (resuming after the last match, one past an empty one), group,
;; start, end, matches, lookingAt, appendReplacement ($n group references,
;; \\ escapes), appendTail, replaceAll, replaceFirst, reset.
(defrecord Matcher [re state])
(defn matcher? [x] #?(:phel (php/is_a x "cohost\\shim\\Matcher") :default (instance? Matcher x)))
(defn new-matcher [re s] (->Matcher re (atom {:s (str s) :m nil :from 0 :append 0})))

(defn- m-find
  ([mt] (let [{:keys [s from]} @(:state mt)]
          (if (> from (count s))
            (do (swap! (:state mt) assoc :m nil) false)
            (let [r (h-re-find-at (:re mt) s from)]
              (swap! (:state mt) assoc :m r
                     :from (if r (let [[b e] r] (if (= b e) (inc e) e)) (inc (count s))))
              (boolean r)))))
  ([mt from]
   (when (or (neg? from) (> from (count (:s @(:state mt)))))
     (throw (host/ex* 'java.lang.IndexOutOfBoundsException "Illegal start index")))
   (swap! (:state mt) assoc :from from :append 0 :m nil) (m-find mt)))

(defn- m-match [mt]
  (or (:m @(:state mt)) (throw (host/ex* 'java.lang.IllegalStateException "No match found"))))

(def ^:private group-name-cache (atom {}))
(defn- group-names
  "{name index}: re's named capturing groups (?<name>...), numbered as the JVM
   numbers every group, by the position of its opening parenthesis."
  [re]
  (let [src (host/re-source re)]
    (or (get @group-name-cache src)
        (let [n (count src)
              at (fn [i] (when (< i n) (str (nth src i))))
              names (loop [i 0 g 0 cls 0 acc {}]
                      (if (>= i n)
                        acc
                        (let [c (at i)]
                          (cond
                            (= c "\\") (if (= "Q" (at (inc i)))
                                          ;; \Q...\E quotes
                                         (let [j (h-index-of src "\\E" (+ i 2))]
                                           (recur (if (neg? j) n (+ j 2)) g cls acc))
                                         (recur (+ i 2) g cls acc))
                            (pos? cls) (recur (inc i) g (cond (= c "[") (inc cls) (= c "]") (dec cls) :else cls) acc)
                            (= c "[") (recur (inc i) g 1 acc)
                            (= c "(") (if (= "?" (at (inc i)))
                                        (if (and (= "<" (at (+ i 2))) (not (contains? #{"=" "!"} (at (+ i 3)))))
                                          (let [j (h-index-of src ">" (+ i 3))]
                                            (recur (inc j) (inc g) cls (assoc acc (subs src (+ i 3) j) (inc g))))
                                          (recur (+ i 2) g cls acc))
                                        (recur (inc i) (inc g) cls acc))
                            :else (recur (inc i) g cls acc)))))]
          (swap! group-name-cache assoc src names)
          names))))

;; Matcher.group(int) / group(String name)
(defn- group-index [mt g]
  (if (string? g)
    (or (get (group-names (:re mt)) g)
        (throw (host/ex* 'java.lang.IllegalArgumentException (str "No group with name <" g ">"))))
    g))

(defn- m-group
  ([mt] (m-group mt 0))
  ([mt g] (let [i (group-index mt g) gs (force (nth (m-match mt) 2))]
            (if (< -1 i (count gs))
              (nth gs i)
              (throw (host/ex* 'java.lang.IndexOutOfBoundsException (str "No group " i)))))))

(defn- m-reset ([mt] (swap! (:state mt) assoc :m nil :from 0 :append 0) mt)
  ([mt s] (swap! (:state mt) assoc :s (str s) :m nil :from 0 :append 0) mt))

(defn- digit [c] (let [i (h-index-of "0123456789" (str c) 0)] (when-not (neg? i) i)))

(defn- ascii-alnum? [ch] (let [k (h-char-code ch)] (or (<= 48 k 57) (<= 65 k 90) (<= 97 k 122))))

(defn- expand-replacement
  "Matcher.appendReplacement's replacement string: \\x is x, $n is group n
   (the longest group number that exists), ${name} the named group, anything
   else is itself."
  [repl groups names]
  (let [repl (str repl) n (count repl) gc (dec (count groups))]
    (loop [i 0 out []]
      (if (>= i n)
        (apply str out)
        (let [c (str (nth repl i))]
          (cond
            (= c "\\") (if (< (inc i) n)
                         (recur (+ i 2) (conj out (str (nth repl (inc i)))))
                         (throw (host/ex* 'java.lang.IllegalArgumentException "character to be escaped is missing")))
            (and (= c "$") (>= (inc i) n))
            (throw (host/ex* 'java.lang.IllegalArgumentException "Illegal group reference: group index is missing"))
            (and (= c "$") (= "{" (str (nth repl (inc i)))))
            (let [j (loop [j (+ i 2)] (if (and (< j n) (ascii-alnum? (nth repl j))) (recur (inc j)) j))
                  gname (subs repl (+ i 2) j)
                  iae #(host/ex* 'java.lang.IllegalArgumentException %)]
              (when (= "" gname) (throw (iae "named capturing group has 0 length name")))
              (when (or (>= j n) (not= "}" (str (nth repl j)))) (throw (iae "named capturing group is missing trailing '}'")))
              (when (digit (first gname)) (throw (iae (str "capturing group name {" gname "} starts with digit character"))))
              (let [g (or (get names gname) (throw (iae (str "No group with name {" gname "}"))))]
                (recur (inc j) (conj out (or (nth groups g) "")))))
            (= c "$") (let [d (digit (nth repl (inc i)))]
                        (when (nil? d)
                          (throw (host/ex* 'java.lang.IllegalArgumentException "Illegal group reference")))
                        (when (> d gc)
                          (throw (host/ex* 'java.lang.IndexOutOfBoundsException (str "No group " d))))
                        (let [[g j] (loop [g d j (+ i 2)]
                                      (let [d2 (when (< j n) (digit (nth repl j)))]
                                        (if (and d2 (<= (+ (* g 10) d2) gc)) (recur (+ (* g 10) d2) (inc j)) [g j])))]
                          (recur j (conj out (or (nth groups g) "")))))
            :else (recur (inc i) (conj out c))))))))

(defn- m-append-replacement [mt sb repl]
  (let [[b e gs] (m-match mt)
        gs (force gs)
        {:keys [s append]} @(:state mt)]
    (h-sb-append! sb (subs s append b))
    (h-sb-append! sb (expand-replacement repl gs (group-names (:re mt))))
    (swap! (:state mt) assoc :append e)
    mt))

(defn- m-append-tail [mt sb]
  (let [{:keys [s append]} @(:state mt)] (h-sb-append! sb (subs s append)) sb))

(defn- m-replace [mt repl all?]
  (m-reset mt)
  (if (m-find mt)
    (let [sb (h-string-builder)]
      (loop []
        (m-append-replacement mt sb repl)
        (when (and all? (m-find mt)) (recur)))
      (h-sb-str (m-append-tail mt sb)))
    (:s @(:state mt))))

(defn- regex-split
  "Pattern.split(input, limit), exactly: no leading empty string from a
   zero-width match at 0; limit 0 drops trailing empty strings."
  [re s limit]
  (when (nil? s) (throw (host/ex* 'java.lang.NullPointerException "")))
  (when-not (or (string? s) (h-sb? s))
    (throw (host/ex* 'java.lang.ClassCastException (cast-message 'java.lang.CharSequence s))))
  (let [s (str s) limited? (pos? limit) mt (new-matcher re s)]
    (loop [index 0 out []]
      (if (m-find mt)
        (let [[b e] (m-match mt)]
          (cond
            (and limited? (>= (count out) (dec limit))) (recur index out)
            (and (zero? index) (= index b) (= b e)) (recur index out)
            :else (recur e (conj out (subs s index b)))))
        (if (and (zero? index) (empty? out))
          [s]
          (let [out (if (or (not limited?) (< (count out) limit)) (conj out (subs s index)) out)]
            (if (zero? limit)
              (loop [out out] (if (and (seq out) (= "" (peek out))) (recur (pop out)) out))
              out)))))))

(def Pattern-quote
  (fn [s] (apply str (map (fn [c] (let [c (str c)] (if (contains? #{"\\" "^" "$" "." "|" "?" "*" "+" "(" ")" "[" "]" "{" "}"} c) (str "\\" c) c))) (str s)))))
(def Matcher-quoteReplacement
  (fn [s] (apply str (map (fn [c] (let [c (str c)] (if (contains? #{"\\" "$"} c) (str "\\" c) c))) (str s)))))
(def UUID-randomUUID (host-fn 'random-uuid (unsupported "random-uuid")))
(def UUID-fromString (host-fn 'parse-uuid (unsupported "parse-uuid")))
(def Class-forName (fn [s] (symbol s)))

;; ---------------------------------------------------------------------------
;; instance methods: `(.m target args)` -> `(-m target args)`

(def -first first)
(def -next (fn [x] (if (host/iter? x) (host/iter-next! x) (next x))))
(def -hasNext host/iter-has-next?)
(def -more rest)
;; (.seq coll) and Sorted.seq(ascending): subseq/rsubseq walk either way
(def -seq (fn ([x] (seq x)) ([x ascending?] (if ascending? (seq x) (rseq x)))))
(def -cons conj)
(def -count (fn [x] (if (host/mlist? x) (count @(:state x)) (count x))))
;; a String method on nil is the JVM's NullPointerException
(defn- nn [x] (if (nil? x) (throw (host/ex* 'java.lang.NullPointerException "")) x))
(def -length (fn [x] (if (h-sb? x) (count (h-sb-str x)) (count (nn x)))))
(def -reverse (fn [x] (if (h-sb? x)
                        (h-sb-append! (h-string-builder) (apply str (reverse (h-sb-str x))))
                        (reverse x))))
(def -toUpperCase (fn [s] (h-upper-case (nn s))))
(def -toLowerCase (fn [s] (h-lower-case (nn s))))
(def -charValue identity)
(def -matcher (fn [re s] (cond (nil? s) (nn s)
                               (h-sb? s) (new-matcher re (h-sb-str s))
                               (string? s) (new-matcher re s)
                               :else (throw (host/ex* 'java.lang.ClassCastException (cast-message 'java.lang.CharSequence s))))))
(def -pattern (fn [x] (if (matcher? x) (:re x) (host/re-source x))))
(def -find (fn ([mt] (m-find mt)) ([mt from] (m-find mt from))))
(def -group (fn ([mt] (m-group mt)) ([mt i] (m-group mt i))))
;; the pattern of a Matcher never changes: its group count is asked once
;; (re-groups asks at every match of re-seq)
(def -groupCount (fn [mt] (let [st (:state mt)]
                            (or (:gc @st)
                                (let [n (host/re-group-count (:re mt))] (swap! st assoc :gc n) n)))))
;; Matcher.start(g) / end(g): the host's group spans (-1 for a group that
;; did not participate)
(defn- m-span [mt g k]
  (let [i (group-index mt g) r (m-match mt) gs (force (nth r 2))]
    (cond (not (< -1 i (count gs))) (throw (host/ex* 'java.lang.IndexOutOfBoundsException (str "No group " i)))
          (zero? i) (nth r k)
          :else (if-let [sps (force (nth r 3 nil))]
                  (if-let [sp (nth sps i)] (nth sp (dec k)) -1)
                  ((unsupported "Matcher.start(group)"))))))
(def -start (fn ([mt] (nth (m-match mt) 0)) ([mt i] (m-span mt i 1))))
(def -end (fn ([mt] (nth (m-match mt) 1)) ([mt i] (m-span mt i 2))))
(def -lookingAt (fn [mt] (let [{:keys [s]} @(:state mt) r (h-re-find-at (:re mt) s 0)
                               r (when (and r (zero? (first r))) r)]
                           (swap! (:state mt) assoc :m r) (boolean r))))
(def -matches (fn ([mt] (let [r (h-re-match-full (:re mt) (:s @(:state mt)))]
                          (swap! (:state mt) assoc :m r) (boolean r)))
                ;; String.matches(regex)
                ([s regex] (boolean (h-re-match-full (Pattern-compile regex) s)))))
(def -appendReplacement (fn [mt sb repl] (m-append-replacement mt sb repl)))
(def -appendTail (fn [mt sb] (m-append-tail mt sb)))
;; Matcher.replaceAll(repl) / String.replaceAll(regex, repl), and First
(def -replaceAll (fn ([mt repl] (m-replace mt repl true))
                   ([s regex repl] (m-replace (new-matcher (Pattern-compile regex) s) repl true))))
(def -replaceFirst (fn ([mt repl] (m-replace mt repl false))
                     ([s regex repl] (m-replace (new-matcher (Pattern-compile regex) s) repl false))))
;; Pattern.split(input[, limit]) / String.split(regex[, limit])
(def -split (fn ([x y] (-split x y 0))
              ([x y limit] (to-array* (if (h-regex? x) (regex-split x y limit) (regex-split (Pattern-compile y) x limit))))))
;; String.replace(CharSequence|char target, CharSequence|char replacement): every occurrence, literally
(def -replace (fn [s target repl]
                (let [s (str (nn s)) t (str (nn target)) r (str (nn repl))]
                  (if (= "" t)
                    (apply str r (mapcat (fn [c] [c r]) s))
                    (loop [from 0 out []]
                      (let [i (h-index-of s t from)]
                        (if (neg? i)
                          (apply str (conj out (subs s from)))
                          (recur (+ i (count t)) (conj out (subs s from i) r)))))))))
(def -size -count)
(def -clear (fn [x] (reset! (:state x) []) nil))
(def -toArray (fn [x] (to-array* (if (host/mlist? x) @(:state x) x))))
(def -compare compare-with)
(def -comparator (fn [coll] (host/sorted-comparator coll)))
;; Sorted.entryKey: a tree map's entry key, a tree set's element itself
(def -entryKey (fn [coll e] (if (map? coll) (key e) e)))
;; Sorted.seqFrom(key, ascending): the entries from key on, in that direction
(def -seqFrom (let [ss (host-fn 'subseq nil) rs (host-fn 'rsubseq nil)]
                (fn [sc k ascending?]
                  (cond (and ascending? ss) (ss sc >= k)
                        (and (not ascending?) rs) (rs sc <= k)
                        :else (let [cmp (host/sorted-comparator sc)
                                    ek (fn [e] (-entryKey sc e))]
                                (if ascending?
                                  (seq (drop-while #(neg? (cmp (ek %) k)) (seq sc)))
                                  (seq (drop-while #(pos? (cmp (ek %) k)) (rseq sc)))))))))
(def -concat (fn [a b] (cond (nil? b) (throw (host/ex* 'java.lang.NullPointerException
                                                       "Cannot invoke \"String.length()\" because \"str\" is null"))
                             (not (string? b)) (throw (host/ex* 'java.lang.ClassCastException
                                                                (str "class " (class-of b) " cannot be cast to class java.lang.String")))
                             :else (str (nn a) b))))
(def -nth nth)
(def -get (fn [x & args] (cond (h-var? x) (host/var-root x)
                               (h-atom? x) (deref x)
                               (host/mlist? x) (nth @(:state x) (first args))
                               (seq args) (apply get x args)
                               (nil? x) (throw (host/ex* 'java.lang.NullPointerException "Cannot invoke \"java.util.concurrent.Future.get()\" because the target is null"))
                               (h-deref? x) (deref x)
                               :else (throw (host/ex* 'java.lang.ClassCastException
                                                      (str "class " (class-of x) " cannot be cast to class java.util.concurrent.Future"))))))
(def ^:private h-var-set (host-fn 'var-set nil))
;; Var.set: the thread binding; a host without var-set (ClojureScript) keeps
;; a binding frame's value in the var's slot
(def -set (fn [x v] (cond (h-atom? x) (tx-write! x #(reset! x v))
                          h-var-set (h-var-set x v)
                          (host/var-thread-bound? x) (host/var-set! x v)
                          :else (throw (host/ex* 'java.lang.IllegalStateException
                                                 (str "Can't change/establish root binding of: " (host/var-sym x) " with set"))))))
;; Ref.alter / commute / touch / history over the atom a Ref is here
(def -alter (fn [r f args] (tx-write! r #(apply swap! r f args))))
(def -commute (fn [r f args] (tx-write! r #(apply swap! r f args))))
(def -touch (fn [_] (in-tx!) nil))
(def -setMaxHistory (fn [r _] r))
(def -setMinHistory (fn [r _] r))
(def -getHistoryCount (fn [_] 0))
(def -getMinHistory (fn [_] 0))
(def -getMaxHistory (fn [_] 10))
(def ^:private host-alter-meta!
  #?(;; phel.core's alter-meta! of a var installs an override its compiler
     ;; never reads and no later def replaces: a var's goes to the adapter
     :phel (let [am (host-fn 'alter-meta! nil)]
             (fn [x f & args] (apply (if (h-var? x) host/alter-var-meta! am) x f args)))
     :default (host-fn 'alter-meta! (fn [x f & args] (apply host/alter-var-meta! x f args)))))
(def ^:private h-assoc! (host-fn 'assoc! nil))
(def ^:private h-dissoc! (host-fn 'dissoc! nil))
(def ^:private h-disj! (host-fn 'disj! nil))
(def ^:private h-pop! (host-fn 'pop! nil))
(def -assoc (fn [x k v] (if (h-transient? x) (h-assoc! x k v) (assoc* x k v))))
(def -assocN -assoc)
(def -without (fn [x k] (if (h-transient? x) (h-dissoc! x k) (dissoc x k))))
(def -disjoin (fn [x k] (if (h-transient? x) (h-disj! x k) (disj x k))))
(def -valAt (fn ([x k] (get x k)) ([x k d] (get x k d))))
(def -entryAt find)
(def -containsKey contains?)
(def -peek peek)
(def -pop (fn [x] (if (h-transient? x) (h-pop! x) (pop x))))
(def -meta (fn [x] (host/meta* x)))
(def -withMeta with-meta)
(defn- host-meta [m] (if (host/strip-inline?) (dissoc m :inline :inline-arities) m))
(defn- meta-map [m]
  (if (or (nil? m) (map? m)) m (throw (host/ex* 'java.lang.ClassCastException (cast-message 'clojure.lang.IPersistentMap m)))))
(def -alterMeta (fn [x f args] (apply host-alter-meta! x (fn [m & more] (host-meta (meta-map (apply f m more)))) args)))
(def -resetMeta (let [rm (host-fn 'reset-meta! (fn [x m] (host-alter-meta! x (constantly m))))]
                  (fn [x m] (rm x (host-meta (meta-map m))))))
(def -setMeta -resetMeta)
;; ClojureScript has no blocking deref: the timed form reads the value now
(def -deref (fn ([x] (deref x)) ([x ms timeout-val] #?(:cljs (deref x) :default (deref x ms timeout-val)))))
(def -invoke (fn [f & args] (apply f args)))
(def -applyTo (fn [f args] (apply f args)))
(def -reduce (if-let [chunk? (:chunk? hc)]
              ;; reduce1's (.reduce (chunk-first s) f val) over a host chunk
              (let [chunk-reduce (:chunk-reduce hc)]
                (fn ([coll f] (host-reduce (host-reducer f) coll))
                  ([coll f init] (if (chunk? coll) (chunk-reduce coll f init) (host-reduce (host-reducer f) init coll)))))
              (fn ([coll f] (host-reduce (host-reducer f) coll)) ([coll f init] (host-reduce (host-reducer f) init coll)))))
(defn chunk-reduce
  "IChunk.reduce: the chunk's elements by index, a reduced result returned
   unopened (the stock caller tests reduced? itself). The codemod sends
   (.reduce (chunk-first s) f val) here: a host's chunk may be one of its
   vectors, whose own reduce opens it."
  [c f init]
  (let [n (count c)]
    (loop [i 0 acc init]
      (if (< i n)
        (let [acc (f acc (nth c i))] (if (reduced? acc) acc (recur (inc i) acc)))
        acc))))

(def -kvreduce (let [rk (host-fn 'reduce-kv (fn [f init coll] (reduce (fn [acc [k v]] (f acc k v)) init coll)))] (fn [coll f init] (rk f init coll))))
(def -equiv =)
(def -equals =)
(declare java-hash)
(def -hashCode (jstatic 'Object/hashCode
                        (fn [x] (cond (nil? x) (throw (host/ex* 'java.lang.NullPointerException ""))
                                      (boolean? x) (if x 1231 1237)
                                      (or (string? x) (h-char? x) (integer? x)) (java-hash (if (h-char? x) (str x) x))
                                      :else (hash x)))))
(def -hasheq hash)
;; a host exception as the JVM prints it, where the host's str does not
(def ^:private h-exception-string (host/capability :exception-string))
(def -toString (fn [x] (cond (h-sb? x) (h-sb-str x) (h-regex? x) (host/re-source x)
                             (and h-exception-string (h-exception? x)) (h-exception-string x)
                             :else (str (nn x)))))
(def -compareTo compare)
(def -isEmpty (fn [x] (if (host/mlist? x) (empty? @(:state x)) (empty? x))))
(defn- type-name
  "A user type's name as the host prints it, without Python's <class '...'>."
  [t]
  (let [n (pr-str t) k (count n)]
    (if (and (> k 10) (= "<class '" (subs n 0 8)) (= "'>" (subs n (- k 2)))) (subs n 8 (- k 2)) n)))
(def -empty (let [h (host-fn 'empty (fn [coll] (cond (vector? coll) [] (map? coll) {} (set? coll) #{} :else ())))]
              ;; defrecord's empty throws (a record without its fields is no record)
              (fn [coll]
                (if (host-record? coll)
                  (throw (host/ex* 'java.lang.UnsupportedOperationException (str "Can't create empty: " (type-name (type coll)))))
                  (h coll)))))
(def -rseq (host-fn 'rseq (fn [coll] (seq (reverse coll)))))
(def -iterator (fn [x] (RT-iter (if (host/mlist? x) @(:state x) x))))
(def -drop (fn [coll n] (drop n coll)))
(def -persistent (host-fn 'persistent! (unsupported "persistent!")))
(def -asTransient (host-fn 'transient (unsupported "transient")))
(def -conj (host-fn 'conj! (unsupported "conj!")))
(def -add (if-let [append (:chunk-append hc)]
           ;; an ArrayList stand-in, or a host ChunkBuffer (new-ChunkBuffer)
           (fn [l x] (if (host/mlist? l) (do (swap! (:state l) conj x) true) (do (append l x) nil)))
           (fn [l x] (swap! (:state l) conj x) true)))
(def -chunk (if-let [chunk (:chunk hc)]
             (fn [buf] (if (host/mlist? buf) @(:state buf) (chunk buf)))
             (fn [buf] @(:state buf))))
(def -chunkedFirst (let [f (:chunk-first hc)]
                     (fn [s] (cond (hc-chunked? s) (f s) (chunked-cons? s) (::chunk (meta s)) :else [(first s)]))))
(def -chunkedMore (let [f (:chunk-rest hc)]
                    (fn [s] (cond (hc-chunked? s) (f s) (chunked-cons? s) (or (::more (meta s)) ()) :else (rest s)))))
(def -chunkedNext (let [f (:chunk-next hc)]
                    (fn [s] (cond (hc-chunked? s) (f s) (chunked-cons? s) (seq (::more (meta s))) :else (next s)))))
;; Named.getName; a non-Named value is the JVM's ClassCastException (nil: NPE);
;; a host class object (from `class` of a record) prints its name
(def -getName (fn [x] (cond (h-namespace? x) (host/ns-name* x)
                            (symbol? x) (name x)
                            (keyword? x) (name x)
                            (nil? x) (throw (host/ex* 'java.lang.NullPointerException ""))
                            (or (string? x) (number? x) (boolean? x) (coll? x))
                            (throw (host/ex* 'java.lang.ClassCastException (cast-message 'clojure.lang.Named x)))
                            :else (str x))))
(def -getNamespace namespace)
(def -getSimpleName (fn [x] (let [n (if (symbol? x) (name (canon x)) (pr-str x))
                                  i (max (host/last-index-of* n ".") (host/last-index-of* n "$") (host/last-index-of* n "/"))]
                              (subs n (inc i)))))
;; Var.sym and Keyword.sym (core.clj's `symbol` of a keyword)
(def -sym (fn [x] (if (keyword? x) (symbol (namespace x) (name x)) (host/var-sym x))))
(def -ns (fn [v] (host/var-ns v)))
(def -name (fn [x] (if (h-namespace? x) (host/ns-name* x) (name x))))
(def -getMappings (host-fn 'ns-map (unsupported "ns-map")))
(def -getAliases (host-fn 'ns-aliases (fn [_] {})))
(def -addAlias (let [al (host-fn 'alias nil)]
                 (fn [ns a target]
                   #?(:cljs ((unsupported "alias"))
                      :default (if al (binding [*ns* (host/the-ns* ns)] (al a (if (h-namespace? target) (host/ns-name* target) target)))
                                   ((unsupported "alias")))))))
(def -removeAlias (host-fn 'ns-unalias (unsupported "ns-unalias")))
(def -intern (fn [ns sym] (host/intern! ns sym nil)))
(def -unmap (host-fn 'ns-unmap (unsupported "ns-unmap")))
(def -refer (fn [ns sym v] (host/intern! ns sym (host/var-root v))))
(def -importClass (fn [& _] nil))
(def -findInternedVar (fn [ns sym] (get (host/ns-interns-map ns) sym)))
(def -getMapping (fn [ns sym] (get (-getMappings ns) sym)))
(def -isMacro host/macro-var?)
(def -isPublic (fn [v] (not (:private (host/var-meta v)))))
(def -getTag (fn [v] (:tag (host/var-meta v))))
(def -fn (fn [v] (let [x (host/var-root v)]
                   (if (ifn? x) x (throw (host/ex* 'java.lang.ClassCastException (cast-message 'clojure.lang.IFn x)))))))
(def -setMacro host/mark-macro!)
(def -isDynamic host/dynamic-var?)
(def -setDynamic (fn ([v] (host/set-dynamic! v)) ([v _b] (host/set-dynamic! v))))
(def -isBound host/var-bound?)
(def -hasRoot host/var-bound?)
(def -getRawRoot (fn [v] (host/var-raw-root v)))
;; Var.getThreadBinding: a box (non-nil even when the binding is nil), or nil
(def -getThreadBinding (fn [v] (when (host/var-thread-bound? v) [(host/var-root v)])))
(def -bindRoot (fn [v x] (host/set-var-root! v (constantly x)) v))
;; Var.alterRoot returns the new root, bindings or not
(def -alterRoot (fn [v f args] (host/set-var-root! v (fn [old] (apply f old args))) (host/var-raw-root v)))
(def -toSymbol (fn [v] (let [n (host/var-ns v) n (if (h-namespace? n) (host/ns-name* n) n)]
                         (if n (symbol (str n) (str (host/var-sym v))) (host/var-sym v)))))
;; IAtom.swap overloads: (f) (f x) (f x y) (f x y args) — the last takes the
;; remaining arguments as one seq, which core.clj's swap! passes through as-is.
(def -swap (fn ([a f] (swap! a f)) ([a f x] (swap! a f x)) ([a f x y] (swap! a f x y))
             ([a f x y args] (apply swap! a f x y args))))
(def ^:private h-vreset! (host-fn 'vreset! nil))
(def -reset (fn ([mf] (if (matcher? mf) (m-reset mf) (do (swap! (mf-state mf) assoc :methods {} :prefers {}) mf)))
              ([a x] (cond (matcher? a) (m-reset a x)
                           (h-volatile? a) (h-vreset! a x)
                           :else (reset! a x)))))
;; IAtom2.swapVals / resetVals as the JVM does them, a compare-and-set loop
;; returning [old new] (a host's own swap-vals! may order the pair the other way)
(defn- swap-vals* [a f args]
  (loop []
    (let [old @a new (apply f old args)]
      (if (compare-and-set! a old new) [old new] (recur)))))
(def -swapVals (fn ([a f] (swap-vals* a f nil)) ([a f x] (swap-vals* a f [x])) ([a f x y] (swap-vals* a f [x y]))
                 ([a f x y args] (swap-vals* a f (cons x (cons y args))))))
(def -resetVals (fn [a x] (loop [] (let [old @a] (if (compare-and-set! a old x) [old x] (recur))))))
(def -compareAndSet (host-fn 'compare-and-set! (unsupported "compare-and-set!")))
(def -addWatch (host-fn 'add-watch (unsupported "add-watch")))
(def -removeWatch (host-fn 'remove-watch (unsupported "remove-watch")))
(def -getWatches (host-fn 'get-watches (fn [_] {})))
(def -setValidator (host-fn 'set-validator! (unsupported "set-validator!")))
(def -getValidator (host-fn 'get-validator (fn [_] nil)))
(def -isRealized (host-fn 'realized? (fn [x] (if (h-pending? x) false true))))
(def -getMessage (fn [e] (host/exception-message e)))
(def -getCause (fn [e] (host/exception-cause e)))
(def -getData ex-data)
(def -getStackTrace (fn [_] []))
(def -setStackTrace (fn [e _trace] e))
(def -getClassName (fn [_frame] ""))
(def -getMethodName (fn [_frame] ""))
(def -getFileName (fn [_frame] nil))
(def -getLineNumber (fn [_frame] -1))
(def -fillInStackTrace (fn [e] e))
(def -printStackTrace (fn [e] (println (str e))))
(def -getClass class-of)
(def -isInstance (fn [c x] (instance-of? c x)))
;; Class.cast: x itself, nil included, when it is an instance of c
(def -cast (fn [c x] (if (or (nil? x) (instance-of? c x))
                       x
                       (throw (host/ex* 'java.lang.ClassCastException (str "Cannot cast " (class-of x) " to " (canon c)))))))
(def -isAssignableFrom (fn [parent child] (token-isa? child parent)))
(def -isArray (fn [_] false))
(def -isInterface (fn [c] (contains? interface-preds (canon c))))
(def -getComponentType (fn [_] nil))
;; the superclass: the most specific ancestor that is a class, not an interface
(defn- interface-token? [t]
  (let [n (name t)]
    (or (and (zero? (h-index-of n "clojure.lang.I" 0)) (> (count n) 14)
             (let [c (subs n 14 15)] (= c (h-upper-case c))))
        (contains? '#{clojure.lang.Seqable clojure.lang.Sequential clojure.lang.Associative clojure.lang.Counted
                      clojure.lang.Indexed clojure.lang.Reversible clojure.lang.Sorted clojure.lang.Named clojure.lang.Fn
                      clojure.lang.MapEquivalence clojure.lang.Settable java.lang.Comparable java.lang.CharSequence
                      java.lang.Iterable java.lang.Runnable java.lang.Cloneable java.lang.AutoCloseable java.lang.Appendable
                      java.io.Serializable java.util.Collection java.util.List java.util.Set java.util.Map java.util.Map$Entry
                      java.util.RandomAccess java.util.Iterator java.util.SequencedCollection java.util.concurrent.Callable
                      java.util.concurrent.Future java.lang.constant.Constable java.lang.constant.ConstantDesc} t))))
(def -getSuperclass
  (fn [c] (let [c (canon c)
                classes (remove #(or (= % c) (interface-token? %)) (get token-ancestors c))]
            ;; java.lang.Object is every class's ancestor
            (first (filter (fn [p] (every? #(or (= % p) (= % 'java.lang.Object) (contains? (get token-ancestors p #{}) %)) classes))
                           classes)))))
(def -getInterfaces (fn [c] (vec (filter #(contains? interface-preds %) (get token-ancestors (canon c))))))
(def -getKey key)
(def -getValue val)
(defn- sioobe [msg] (host/ex* 'java.lang.StringIndexOutOfBoundsException msg))
(defn- as-string [s]
  (cond (string? (nn s)) s
        (h-sb? s) (h-sb-str s)
        :else (throw (host/ex* 'java.lang.ClassCastException (str "class " (class-of s) " cannot be cast to class java.lang.String")))))
(def -substring (fn ([s a] (let [s (as-string s) n (count s)]
                             (if (and (<= 0 a) (<= a n)) (subs s a) (throw (sioobe (str "begin " a ", end " n ", length " n))))))
                  ([s a b] (let [s (as-string s) n (count s)]
                             (if (and (<= 0 a) (<= a b) (<= b n))
                               (subs s a b)
                               (throw (sioobe (str "begin " a ", end " b ", length " n))))))))
(def -subSequence (fn [s a b] (subs (str s) a b)))
(def -charAt (fn [s i] (let [s (nn s) n (count s)]
                         (if (and (<= 0 i) (< i n)) (nth s i) (throw (sioobe (str "Index " i " out of bounds for length " n)))))))
;; String.indexOf(str|char|char-code[, from]); List.indexOf(x) for anything else
(def ^:private h-char (host-fn 'char identity))
(defn- needle [x] (if (number? x) (str (h-char x)) (str x)))
(def -indexOf (fn ([s x] (-indexOf s x 0))
                ([s x from]
                 (if (string? s)
                   (let [x (needle (nn x)) from (max 0 from)]
                     (if (> from (count s)) (if (= "" x) (count s) -1) (h-index-of s x from)))
                   (or (first (keep-indexed (fn [i y] (when (= y x) i)) s)) -1)))))
;; String.lastIndexOf(str|char[, from]): the last start at or before from
(def -lastIndexOf (fn ([s x] (-lastIndexOf s x (count s)))
                    ([s x from]
                     (let [x (needle (nn x)) n (count x) m (count s)]
                       (loop [i (min from (- m n))]
                         (cond (neg? i) -1 (= (subs s i (+ i n)) x) i :else (recur (dec i))))))))
(def -startsWith (fn [s p] (let [s (nn s) p (nn p)] (and (>= (count s) (count p)) (= (subs s 0 (count p)) p)))))
(def -endsWith (fn [s p] (let [s (nn s) p (nn p)] (and (>= (count s) (count p)) (= (subs s (- (count s) (count p))) p)))))
(def -contains (fn [s x] (not (neg? (-indexOf s x)))))
;; String.trim (chars <= U+0020), strip / stripLeading / stripTrailing / isBlank
;; (Character.isWhitespace), equalsIgnoreCase, compareToIgnoreCase, repeat,
;; codePointAt, toCharArray: as java.lang.String has them
(def -trim (fn [s] (let [s (nn s) n (count s)
                         sp? (fn [i] (<= (h-char-code (nth s i)) 32))
                         b (loop [i 0] (if (and (< i n) (sp? i)) (recur (inc i)) i))
                         e (loop [j n] (if (and (> j b) (sp? (dec j))) (recur (dec j)) j))]
                     (subs s b e))))
(defn- strip* [s lead? trail?]
  (let [s (nn s) n (count s)
        w? (fn [i] (Character-isWhitespace (nth s i)))
        b (if lead? (loop [i 0] (if (and (< i n) (w? i)) (recur (inc i)) i)) 0)
        e (if trail? (loop [j n] (if (and (> j b) (w? (dec j))) (recur (dec j)) j)) n)]
    (subs s b e)))
(def -strip (fn [s] (strip* s true true)))
(def -stripLeading (fn [s] (strip* s true false)))
(def -stripTrailing (fn [s] (strip* s false true)))
(def -isBlank (fn [s] (= "" (strip* s true true))))
(defn- char-fold [c] (h-char-code (first (h-lower-case (h-upper-case (str c))))))
(def -equalsIgnoreCase (fn [s t] (let [s (nn s)]
                                   (boolean (and (string? t) (= (count s) (count t))
                                                 (every? true? (map (fn [a b] (or (= a b) (= (char-fold a) (char-fold b)))) s t)))))))
(def -compareToIgnoreCase (fn [s t] (let [s (nn s) t (nn t) n (min (count s) (count t))]
                                      (loop [i 0]
                                        (if (< i n)
                                          (let [a (char-fold (nth s i)) b (char-fold (nth t i))]
                                            (if (= a b) (recur (inc i)) (- a b)))
                                          (- (count s) (count t)))))))
(def -repeat (fn [s n] (let [s (nn s)]
                         (when (neg? n) (throw (host/ex* 'java.lang.IllegalArgumentException (str "count is negative: " n))))
                         (apply str (repeat n s)))))
(def -codePointAt (fn [s i] (let [s (nn s) n (count s)]
                              (when-not (< -1 i n)
                                (throw (host/ex* 'java.lang.StringIndexOutOfBoundsException (str "Index " i " out of bounds for length " n))))
                              (let [hi (h-char-code (nth s i))]
                                (if (and (<= 55296 hi 56319) (< (inc i) n))
                                  (let [lo (h-char-code (nth s (inc i)))]
                                    (if (<= 56320 lo 57343) (+ 65536 (* (- hi 55296) 1024) (- lo 56320)) hi))
                                  hi)))))
(def -toCharArray (fn [s] (to-array* (vec (nn s)))))
(def -append (fn [sb x] (h-sb-append! sb (if (nil? x) "null" x))))
(def -close host/close!)
(def -flush (fn [_] nil))
(def -numerator (host-fn 'numerator identity))
(def -denominator (host-fn 'denominator (constantly 1)))
(def -bigIntegerValue identity)
(def -toBigInteger identity)
(def -doubleValue RT-doubleCast)
(def -longValue RT-longCast)
(def -intValue RT-intCast)
(def -floatValue RT-floatCast)
(def -getMethodTable (fn [mf] (:methods @(mf-state mf))))
(def -getPreferTable (fn [mf] (:prefers @(mf-state mf))))
(def -addMethod (fn [mf dv f] (swap! (mf-state mf) assoc-in [:methods dv] f) mf))
(def -removeMethod (fn [mf dv] (swap! (mf-state mf) update :methods dissoc dv) mf))
(def -preferMethod (fn [mf x y]
                     (let [{:keys [name prefers hierarchy]} @(mf-state mf)]
                       (when (mf-prefers? prefers (deref hierarchy) y x)
                         (throw (host/ex* 'IllegalStateException (str "Preference conflict in multimethod '" name "': " y " is already preferred to " x))))
                       (swap! (mf-state mf) update-in [:prefers x] (fnil conj #{}) y)
                       mf)))
(def -getMethod (fn [mf dv] (mf-find-method @(mf-state mf) dv)))
(def -isDone (fn [_] true))
(def -isCancelled (fn [_] false))

(def monitor-enter (fn [_] nil))
(def monitor-exit (fn [_] nil))

;; ---------------------------------------------------------------------------
;; what is here (the rewriter's `known?` set)

;; (.m x args) in source becomes ((dot "m" -m) x args): the call keeps the
;; source's positions, so -> and doto thread into it as into the original. The
;; dispatcher: an object of a type user code defined (deftype, defrecord,
;; reify, proxy) answers its own member m when it has one; the host's own
;; data, and the shim's and adapter's own records, go to -m.
(def ^:private shim-types
  #{(type-of (host/->Iter nil)) (type-of (host/->SB nil)) (type-of (host/->MList nil)) (type-of (->Matcher nil nil))})
;; the answer is the type's (a host's user-type? reads only the class), asked
;; at every member call: computed once per type
(def ^:private host-object-types (atom {}))
(def ^:private h-host-data? (host/capability :host-data?))
(defn host-object? [x]
  (and (some? x)
       (not (h-host-data? x))
       (let [t #?(:cljs (type x) :default (class x))
             hit (get @host-object-types t absent)]
         (if (identical? hit absent)
           (let [r (boolean (and (host/user-type? x) (not (contains? shim-types (type-of x)))))]
             (swap! host-object-types assoc t r)
             r)
           hit))))
(defn- npe-on [m] (host/ex* 'java.lang.NullPointerException (str "Cannot invoke \"" m "()\" because the target is null")))
;; members defrecord itself defines, which a host's record answers otherwise
;; (jolt's .empty is nil, ClojureScript's records have no empty): the shim's
(def ^:private record-members #{"empty"})
(defn- non-record-object? [x] (and (host-object? x) (not (host-record? x))))
(defn dot [m f]
  (let [own? (if (contains? record-members m) non-record-object? host-object?)]
    (fn ([x] (let [_ (when (nil? x) (throw (npe-on m)))
                   r (if (own? x) (host/invoke-member x m [] absent) absent)]
               (if (identical? r absent) (f x) r)))
      ([x a] (let [_ (when (nil? x) (throw (npe-on m)))
                   r (if (own? x) (host/invoke-member x m [a] absent) absent)]
               (if (identical? r absent) (f x a) r)))
      ([x a b] (let [_ (when (nil? x) (throw (npe-on m)))
                     r (if (own? x) (host/invoke-member x m [a b] absent) absent)]
                 (if (identical? r absent) (f x a b) r)))
      ([x a b c] (let [_ (when (nil? x) (throw (npe-on m)))
                       r (if (own? x) (host/invoke-member x m [a b c] absent) absent)]
                   (if (identical? r absent) (f x a b c) r)))
      ([x a b c & more] (let [_ (when (nil? x) (throw (npe-on m)))
                              r (if (own? x) (host/invoke-member x m (into [a b c] more) absent) absent)]
                          (if (identical? r absent) (apply f x a b c more) r))))))

(defn shim-value
  "The value of the shim var named nm (a string). vars: (host/ns-interns-map
   'cohost.shim), unused on ClojureScript, where it is the namespace object's
   property the codemod's shim-ref names."
  [vars nm]
  #?(:cljs (gobj/get js/cohost.shim (munge nm))
     :default (some-> (get vars (symbol nm)) host/var-root)))

(defn install-dots!
  "For each instance method -m the shim has (method-syms, symbols): dot$m,
   the dispatcher (dot \"m\" -m), built once — the codemod's (.m x args)
   calls it rather than building a dispatcher at every evaluation of the
   call. A shim var (on ClojureScript, the property of the namespace object
   its shim-ref names). -> the names installed."
  [method-syms]
  (let [vars #?(:cljs nil :default (host/ns-interns-map 'cohost.shim))]
    (vec (keep (fn [s]
                 (let [nm (str s)
                       f (shim-value vars nm)]
                   (when (fn? f)
                     (let [d (str "dot$" (subs nm 1))
                           v (dot (subs nm 1) f)]
                       #?(:cljs (gobj/set js/cohost.shim (munge d) v)
                          :default (host/intern! 'cohost.shim (symbol d) v))
                       (symbol d)))))
               method-syms))))

;; ---------------------------------------------------------------------------
;; java.util.Formatter, where the host's own format is not the JVM's
;; (ClojureScript's goog.string.format, basilisp's Python %): the specifier
;; %[index$][flags][width][.precision]conversion with the conversions
;; b h s c d o x e f g n % (B H S C X E G upper-case the result), the flags
;; - # + space 0 , ( and < (the previous argument), and the JVM's exceptions
;; for a missing argument or width, an unknown conversion, a precision a
;; conversion takes none of, and an argument of the wrong kind.

(defn- zeros [n] (apply str (repeat n "0")))
(defn- digit-val [c] (- (h-char-code c) 48))
(defn- small-int [t]
  (let [neg (= "-" (subs t 0 1)) t (if (contains? #{"-" "+"} (subs t 0 1)) (subs t 1) t)]
    (* (if neg -1 1) (reduce (fn [a c] (+ (* a 10) (digit-val c))) 0 t))))

(defn- dec-digits
  "A finite non-negative double as [digits exp], x = 0.digits * 10^exp: the
   host's shortest decimal significand, no leading or trailing zero (\"\" for 0)."
  [x]
  (let [s (str x)
        e-at (let [i (h-index-of s "e" 0)] (if (neg? i) (h-index-of s "E" 0) i))
        mant (if (neg? e-at) s (subs s 0 e-at))
        ex (if (neg? e-at) 0 (small-int (subs s (inc e-at))))
        dot (h-index-of mant "." 0)
        ip (if (neg? dot) mant (subs mant 0 dot))
        ds (str ip (if (neg? dot) "" (subs mant (inc dot))))
        lead (count (take-while #(= "0" (str %)) ds))
        ds (subs ds lead)
        ds (subs ds 0 (- (count ds) (count (take-while #(= "0" (str %)) (reverse ds)))))]
    [ds (- (+ ex (count ip)) lead)]))

(defn- inc-digits [ds]
  (loop [i (dec (count ds)) tail ""]
    (cond (neg? i) (str "1" tail)
          (= 9 (digit-val (nth ds i))) (recur (dec i) (str "0" tail))
          :else (str (subs ds 0 i) (inc (digit-val (nth ds i))) tail))))

(defn- round-half-up
  "[digits exp] rounded half up to n significant digits (n <= 0: to the digit
   above the first), the JVM Formatter's RoundingMode.HALF_UP on the
   shortest decimal: a carry raises the exponent."
  [ds exp n]
  (cond (>= n (count ds)) [ds exp]
        (neg? n) ["" exp]
        (< (digit-val (nth ds n)) 5) [(subs ds 0 n) exp]
        :else (let [r (inc-digits (subs ds 0 n))]
                (if (> (count r) n) [r (inc exp)] [r exp]))))

(defn- fixed-parts
  "[integer-digits fraction-digits] of 0.ds * 10^exp with p fraction digits"
  [ds exp p]
  (let [ip (cond (or (= "" ds) (<= exp 0)) "0"
                 (>= (count ds) exp) (subs ds 0 exp)
                 :else (str ds (zeros (- exp (count ds)))))
        fs (cond (= "" ds) ""
                 (<= exp 0) (str (zeros (- exp)) ds)
                 (> (count ds) exp) (subs ds exp)
                 :else "")]
    [ip (subs (str fs (zeros p)) 0 p)]))

(defn- f-parts [x p]
  (let [[ds e] (dec-digits x) [ds e] (round-half-up ds e (+ e p))] (fixed-parts ds e p)))

(defn- e-parts
  "|x| as [d ddd exponent], Java's %e with p digits after the point"
  [x p]
  (let [[ds e] (dec-digits x)]
    (if (= "" ds)
      ["0" (zeros p) 0]
      (let [[ds e] (round-half-up ds e (inc p))
            ds (str ds (zeros (- (inc p) (count ds))))]
        [(subs ds 0 1) (subs ds 1 (inc p)) (dec e)]))))

(defn- group-thousands [ds]
  (let [n (count ds)]
    (apply str (map-indexed (fn [i c] (if (and (pos? i) (zero? (mod (- n i) 3))) (str "," c) (str c))) ds))))

(defn- java-hash
  "Object.hashCode for %h: String's and Long's exactly, else the host's hash."
  [x]
  (cond (string? x) (reduce (fn [h c] (wrap32 (+ (* 31 h) (h-char-code c)))) 0 x)
        (and (integer? x) (<= 0 x 4294967295)) (wrap32 x)
        (and (integer? x) (<= -2147483648 x -1)) (- (- x) 1)
        :else (hash x)))

(defn- jformat [fmt args]
  (let [fmt (str fmt) n (count fmt) args (vec args)
        at (fn [i] (when (< i n) (str (nth fmt i))))
        digit? (fn [c] (and (some? c) (<= 48 (h-char-code c) 57)))
        ex (fn [k msg] (host/ex* k msg))]
    (loop [i 0 out [] ordinary 0 prev nil]
      (if (>= i n)
        (apply str out)
        (let [c (at i)]
          (if (not= c "%")
            (recur (inc i) (conj out c) ordinary prev)
            (let [j0 (inc i)
                  dj (loop [j j0] (if (digit? (at j)) (recur (inc j)) j))
                  [index j] (if (and (> dj j0) (= "$" (at dj))) [(small-int (subs fmt j0 dj)) (inc dj)] [nil j0])
                  fj (loop [j j] (if (contains? #{"-" "#" "+" " " "0" "," "(" "<"} (at j)) (recur (inc j)) j))
                  flags (set (map str (subs fmt j fj)))
                  wj (loop [j fj] (if (digit? (at j)) (recur (inc j)) j))
                  width (when (> wj fj) (small-int (subs fmt fj wj)))
                  pk (when (= "." (at wj)) (loop [k (inc wj)] (if (digit? (at k)) (recur (inc k)) k)))
                  _ (when (and pk (= pk (inc wj)))
                      (throw (ex 'java.util.UnknownFormatConversionException "Conversion = '.'")))
                  prec (when pk (small-int (subs fmt (inc wj) pk)))
                  cj (or pk wj)
                  conv (at cj)
                  _ (when (nil? conv) (throw (ex 'java.util.UnknownFormatConversionException "Conversion = '%'")))
                  spec (subs fmt i (inc cj))
                  lc (h-lower-case conv)
                  upper? (and (not= lc conv) (contains? #{"b" "h" "s" "c" "x" "e" "g"} lc))
                  _ (when-not (contains? #{"b" "h" "s" "c" "d" "o" "x" "e" "f" "g" "n" "%"} (if upper? lc conv))
                      (if (contains? #{"t" "T"} conv)
                        ((unsupported "java.util.Formatter's date conversions (%t)"))
                        (throw (ex 'java.util.UnknownFormatConversionException (str "Conversion = '" conv "'")))))
                  _ (when (and (nil? width) (or (contains? flags "-") (contains? flags "0")))
                      (throw (ex 'java.util.MissingFormatWidthException spec)))
                  _ (when (and prec (contains? #{"c" "d" "o" "x" "n" "%"} lc))
                      (throw (ex 'java.util.IllegalFormatPrecisionException (str prec))))
                  takes? (not (contains? #{"n" "%"} lc))
                  missing #(ex 'java.util.MissingFormatArgumentException (str "Format specifier '" spec "'"))
                  [arg ordinary prev] (cond (not takes?) [nil ordinary prev]
                                            (contains? flags "<") (if prev [(first prev) ordinary prev] (throw (missing)))
                                            index (if (<= 1 index (count args))
                                                    [(nth args (dec index)) ordinary [(nth args (dec index))]]
                                                    (throw (missing)))
                                            (< ordinary (count args)) [(nth args ordinary) (inc ordinary) [(nth args ordinary)]]
                                            :else (throw (missing)))
                  wrong #(ex 'java.util.IllegalFormatConversionException (str conv " != " (class-of arg)))
                  trunc (fn [v] (if prec (subs v 0 (min prec (count v))) v))
                  ;; sign, parentheses and zero padding around a number's digits
                  signed (fn [neg? mag finite?]
                           (let [paren? (and neg? (contains? flags "("))
                                 sign (cond paren? "(" neg? "-" (contains? flags "+") "+" (contains? flags " ") " " :else "")
                                 tail (if paren? ")" "")
                                 pad (if (and width finite? (contains? flags "0")) (zeros (- width (count sign) (count mag) (count tail))) "")]
                             (str sign pad mag tail)))
                  grouped (fn [ip] (if (contains? flags ",") (group-thousands ip) ip))
                  floating (fn [f]
                             (cond (not (or (double? arg) (h-decimal? arg))) (throw (wrong))
                                   (h-nan? arg) "NaN"
                                   (h-infinite? arg) (signed (neg? arg) "Infinity" false)
                                   ;; -0.0 is negative too (where the host's str shows it)
                                   :else (let [neg? (or (neg? arg) (= "-" (subs (str arg) 0 1)))]
                                           (signed neg? (f (if neg? (- arg) arg)) true))))
                  body (if (and (nil? arg) takes? (not= lc "b"))
                         (trunc "null")
                         (case lc
                           "%" "%"
                           "n" "\n"
                           "b" (trunc (str (if (nil? arg) false (if (boolean? arg) arg true))))
                           "s" (trunc (str arg))
                           "h" (trunc (Integer-toHexString (java-hash arg)))
                           "c" (cond (h-char? arg) (str arg)
                                     (integer? arg) (str (char arg))
                                     :else (throw (wrong)))
                           "d" (if (integer? arg)
                                 (signed (neg? arg) (grouped (str (if (neg? arg) (- arg) arg))) true)
                                 (throw (wrong)))
                           ("o" "x") (if (integer? arg)
                                       (let [radix (if (= lc "o") 8 16)
                                             big-neg? (and (neg? arg) (h-bigint? arg))
                                             mag (cond big-neg? (radix-str (- arg) radix)
                                                       (neg? arg) (if (= radix 8) (Long-toOctalString arg) (Long-toHexString arg))
                                                       :else (radix-str arg radix))
                                             mag (if (contains? flags "#") (str (if (= radix 8) "0" "0x") mag) mag)]
                                         (signed big-neg? mag true))
                                       (throw (wrong)))
                           "f" (floating (fn [a] (let [[ip fp] (f-parts a (or prec 6))]
                                                   (str (grouped ip) (when (or (pos? (count fp)) (contains? flags "#")) ".") fp))))
                           "e" (floating (fn [a] (let [p (or prec 6) [d fp x10] (e-parts a p)]
                                                   (str d (when (or (pos? p) (contains? flags "#")) ".") fp
                                                        "e" (if (neg? x10) "-" "+")
                                                        (let [m (if (neg? x10) (- x10) x10)] (if (< m 10) (str "0" m) (str m)))))))
                           "g" (floating (fn [a] (let [p (cond (nil? prec) 6 (zero? prec) 1 :else prec)
                                                       [ds e] (dec-digits a)
                                                       [ds2 e2] (round-half-up ds e p)]
                                                   (if (or (= "" ds) (and (>= e2 -3) (<= e2 p)))
                                                     (let [[ip fp] (fixed-parts ds2 (if (= "" ds) 1 e2) (- p (if (= "" ds) 1 e2)))]
                                                       (str (grouped ip) (when (pos? (count fp)) ".") fp))
                                                     (let [[d fp x10] (e-parts a (dec p))]
                                                       (str d (when (pos? (count fp)) ".") fp
                                                            "e" (if (neg? x10) "-" "+")
                                                            (let [m (if (neg? x10) (- x10) x10)] (if (< m 10) (str "0" m) (str m)))))))))))
                  body (if upper? (h-upper-case body) body)
                  body (cond (nil? width) body
                             (contains? flags "-") (str body (apply str (repeat (- width (count body)) " ")))
                             :else (str (apply str (repeat (- width (count body)) " ")) body))]
              (recur (inc cj) (conj out body) ordinary prev))))))))

(def public-names
  "The unqualified names of every public var in this namespace."
  #?(:cljs (cohost.shim-names/public-names)
     :default (set (keys (ns-publics 'cohost.shim)))))

(ns cohost.hosts.cljw
  "The ClojureWasm adapter (cljw: Clojure on Zig). cljw names clojure.lang and
   java.* classes of its own, with the JVM's exception hierarchy, so this is
   the jolt adapter's shape. What differs is what its runtime keeps out of
   reach:
   - no clojure.core/read over a stream: read-forms finds each top-level
     form's extent and reads that text alone;
   - a var is a macro only by defmacro (^:macro on a def is ignored, and there
     is no .setMacro); dynamic only by (def ^:dynamic x);
   - no Var interop (.isMacro .getRawRoot ...): the var's metadata answers;
   - Matcher.find/start/end take no index or group; no region;
   - class names print without their package (\"Long\", \"IllegalStateException\");
   - an unknown member is a catchable error, but cljw's \"not implemented\"
     is not: interop the shim lacks stays the shim's (no passthrough)."
  (:require [cohost.adapter :as adapter]
            [clojure.edn :as edn]))

;; ---------------------------------------------------------------------------
;; reading: each top-level form's extent by a scan, then read-string of that
;; text, so a form is read after the one before it ran (a ::kw after an ns
;; form is that namespace's). The scan indexes a vector of the chars: nth of
;; a cljw string walks it from the start.

(def ^:private delims #{\( \) \[ \] \{ \} \" \;})
(defn- ws? [c] (or (= c \,) (Character/isWhitespace c)))

(defn- skip-ws
  "The index of the next form at or after i, past whitespace, commas and
   ; comments."
  [cs i]
  (let [n (count cs)]
    (loop [i i]
      (if (>= i n)
        n
        (let [c (nth cs i)]
          (cond (ws? c) (recur (inc i))
                (= c \;) (recur (loop [j i] (if (or (>= j n) (= (nth cs j) \newline)) j (recur (inc j)))))
                :else i))))))

(defn- token-end [cs i]
  (let [n (count cs)]
    (loop [i i]
      (if (or (>= i n) (let [c (nth cs i)] (or (ws? c) (contains? delims c)))) i (recur (inc i))))))

(defn- string-end "i at the opening quote -> past the closing one" [cs i]
  (loop [i (inc i)]
    (let [c (nth cs i)]
      (cond (= c \\) (recur (+ i 2)) (= c \") (inc i) :else (recur (inc i))))))

(declare form-end)
(defn- coll-end [cs i close]
  (loop [i (skip-ws cs i)]
    (if (= (nth cs i) close) (inc i) (recur (skip-ws cs (form-end cs i))))))

(defn- form-end
  "The index past the form starting at i (a #_ discard runs on to the form
   after it)."
  [cs i]
  (if (>= i (count cs))
    i
    (let [c (nth cs i)
          after (fn [k] (form-end cs (skip-ws cs k)))]
      (case c
        \( (coll-end cs (inc i) \))
        \[ (coll-end cs (inc i) \])
        \{ (coll-end cs (inc i) \})
        \" (string-end cs i)
        \\ (token-end cs (+ i 2))
        (\' \` \@) (after (inc i))
        \~ (after (if (= \@ (nth cs (inc i) nil)) (+ i 2) (inc i)))
        \^ (after (form-end cs (skip-ws cs (inc i))))
        \# (let [d (nth cs (inc i) nil)]
             (case d
               (\( \{) (form-end cs (inc i))
               \" (string-end cs (inc i))
               (\' \=) (after (+ i 2))
               (\_ \^) (after (form-end cs (skip-ws cs (+ i 2))))
               \? (after (if (= \@ (nth cs (+ i 2) nil)) (+ i 3) (+ i 2)))
               \: (after (token-end cs (+ i 2)))
               \# (token-end cs (+ i 2))
               ;; a tagged literal: the tag, then its form
               (after (token-end cs (inc i)))))
        (token-end cs i)))))

(defn- read-forms [src cb]
  (let [cs (vec src) n (count cs)]
    (loop [i (skip-ws cs 0)]
      (when (< i n)
        (let [j (form-end cs i)
              f (read-string {:eof ::none :read-cond :allow} (subs src i j))]
          (when-not (= f ::none) (cb f))
          (recur (skip-ws cs j)))))))

;; ---------------------------------------------------------------------------
;; vars

(defn- var-ns-sym [v] (ns-name (:ns (meta v))))

;; A var's metadata says :macro both for the var's macro bit and for a def
;; whose name carried ^:macro (the def keeps the key and leaves the bit:
;; meta.zig synthVarMeta projects the bit over the stored map), and a later
;; plain def of a macro clears the bit (tree_walk.zig evalDef) but not the key.
;; So in the namespaces cohost makes, a var is a macro while it holds the root
;; mark-macro! made a macro (or the wrapper boot put over it since); elsewhere
;; (cljw's own namespaces) the metadata is the bit.
(def ^:private cohost-nses (atom #{}))
(def ^:private marked (atom {}))
(defn- root-of [v] (adapter/try-all (fn [] (var-get v))))
(defn- macro-var? [v]
  (and (true? (:macro (meta v)))
       (or (not (contains? @cohost-nses (var-ns-sym v)))
           (let [r (get @marked v ::none)] (and (not= ::none r) (identical? r (root-of v)))))))

;; The protocols cljw's deftype / reify / defrecord write bare into the code
;; they emit (a clojure.lang interface section becomes cljw protocol sections:
;; host_interface.zig), so they must resolve in the namespace the type is in;
;; none is a clojure.core name.
(def ^:private emitted-bare
  (vec (filter #(var? (ns-resolve 'clojure.core %))
               '[Associative CharSequence Comparable IBlockingDeref IDeref IEditableCollection IFn IKVReduce
                 ILookup IMapEntry IObj IPending IPersistentCollection IPersistentMap IPersistentSet
                 IPersistentStack IPersistentVector IReduce ISeq ITransientAssociative ITransientCollection
                 ITransientMap ITransientSet ITransientVector Indexed Reversible Seqable Sorted])))

(defn- mark-macro!
  "cljw sets a var's macro bit only for defmacro, and sets it when it analyzes
   the defmacro, on the var it interns for the name, before the macro's fn is
   analyzed (special_forms.zig analyzeDefmacro; env.zig internDeclare returns
   the existing var as it is). So a defmacro of v's name whose body cannot be
   analyzed makes v a macro and leaves its root alone; the metadata the
   defmacro set is put back."
  [v]
  (let [m (meta v)]
    (binding [*ns* (:ns m)]
      (adapter/try-all (fn [] (eval (list 'defmacro (:name m) [] 'cohost.hosts.cljw/no-such-var)))))
    (alter-meta! v (fn [_] (assoc m :macro true)))
    (swap! marked assoc v (root-of v))
    v))

(defn- set-dynamic!
  "(def ^:dynamic x) with no value keeps x's root and makes it dynamic."
  [v]
  (let [m (meta v)]
    (binding [*ns* (:ns m)]
      (eval (list 'def (with-meta (:name m) {:dynamic true}))))
    (alter-meta! v (fn [_] (assoc m :dynamic true)))
    v))

;; ---------------------------------------------------------------------------
;; macro expansions: cljw's analyzer takes back lists, cons cells, lazy and
;; chunked seqs and ranges as code, but not an array seq, which is what a fn's
;; & rest args are (analyzer.zig valueToForm); a stock macro builds its
;; expansion from them. Every seq not a list becomes one; what does not change
;; stays the same object.

(declare as-code)
(defn- code-meta
  "x' carrying x's metadata as code (a defn's name carries :arglists, a seq
   its own expansion built); x' itself when nothing changed."
  [x x']
  (let [m (meta x) m' (when m (as-code m))]
    (if (and (identical? x x') (identical? m m')) x (with-meta x' m'))))

(defn- as-code [x]
  (cond
    (seq? x) (let [xs (doall (map as-code x))]
               (code-meta x (if (and (list? x) (every? true? (map identical? xs x))) x (apply list xs))))
    (vector? x) (let [xs (mapv as-code x)]
                  (code-meta x (if (every? true? (map identical? xs x)) x xs)))
    (and (map? x) (not (record? x)))
    (let [kvs (mapv (fn [[k v]] [(as-code k) (as-code v)]) x)]
      (code-meta x (if (every? (fn [[[k v] [k' v']]] (and (identical? k k') (identical? v v'))) (map vector kvs (seq x)))
                     x
                     (into (empty x) kvs))))
    (set? x) (let [xs (mapv as-code x)]
               (code-meta x (if (every? true? (map identical? xs (seq x))) x (into (empty x) xs))))
    (symbol? x) (code-meta x x)
    ;; stock defmulti's expansion holds #'global-hierarchy itself
    (var? x) (list 'var (symbol (str (var-ns-sym x)) (str (:name (meta x)))))
    :else x))

;; A var's root while this thread binds it: cljw's alter-var-root applies f to
;; the binding's value (and stores that as the root), and var-get reads the
;; binding. So the frames binding v are popped (each one's bindings saved
;; whole), the root read, and the frames pushed back as they were; intern sets
;; the root, keeping the var's bits.
(defn- raw-root [v]
  (if (thread-bound? v)
    (let [frames (loop [fs []]
                   (if (thread-bound? v)
                     (let [b (get-thread-bindings)]
                       ;; a frame cljw's own binding made is no push frame: stop
                       (if (adapter/try-all (fn [] (pop-thread-bindings) true))
                         (recur (conj fs b))
                         fs))
                     fs))]
      (try (adapter/try-all (fn [] (var-get v)))
           (finally (doseq [b (rseq frames)] (push-thread-bindings b)))))
    (var-get v)))

(defn- alter-root! [v f]
  (let [r (if (thread-bound? v)
            (let [m (meta v) r (f (raw-root v))]
              (intern (var-ns-sym v) (:name m) r)
              r)
            (alter-var-root v f))]
    (when (contains? @marked v) (swap! marked assoc v r))
    r))

;; ---------------------------------------------------------------------------
;; regexes: Matcher.find takes no start, so a search from an index walks the
;; matches from the start of the string; a search that resumes where the last
;; one ended (find, re-seq) continues on that matcher

(def ^:private last-match (volatile! nil))

(defn- groups [m] (mapv (fn [i] (.group m i)) (range (inc (.groupCount m)))))

(defn- re-find-at [re s from]
  (let [[re0 s0 m0 end] @last-match
        m (if (and (identical? re re0) (identical? s s0) (<= end from)) m0 (re-matcher re s))]
    (loop []
      (if (.find m)
        (if (< (.start m) from)
          (recur)
          (let [b (.start m) e (.end m)]
            (vreset! last-match [re s m e])
            [b e (groups m) nil]))
        (do (vreset! last-match nil) nil)))))

(defn- re-match-full [re s]
  (let [m (re-matcher re s)]
    (when (.matches m) [0 (count s) (groups m) nil])))

;; ---------------------------------------------------------------------------
;; exceptions: the classes cljw constructs; their names print without the
;; package, which exception-class puts back

(def ^:private exception-ctors
  {'java.lang.Throwable [#(Throwable.) #(Throwable. %) #(Throwable. %1 %2)]
   'java.lang.Exception [#(Exception.) #(Exception. %) #(Exception. %1 %2)]
   'java.lang.RuntimeException [#(RuntimeException.) #(RuntimeException. %) #(RuntimeException. %1 %2)]
   'java.lang.IllegalArgumentException [#(IllegalArgumentException.) #(IllegalArgumentException. %) nil]
   'java.lang.IllegalStateException [#(IllegalStateException.) #(IllegalStateException. %) nil]
   'java.lang.UnsupportedOperationException [#(UnsupportedOperationException.) #(UnsupportedOperationException. %) nil]
   'java.lang.ArithmeticException [#(ArithmeticException.) #(ArithmeticException. %) nil]
   'java.lang.NumberFormatException [#(NumberFormatException.) #(NumberFormatException. %) nil]
   'java.lang.ClassCastException [#(ClassCastException.) #(ClassCastException. %) nil]
   'java.lang.NullPointerException [#(NullPointerException.) #(NullPointerException. %) nil]
   'java.lang.IndexOutOfBoundsException [#(IndexOutOfBoundsException.) #(IndexOutOfBoundsException. %) nil]})

(defn- new-exception [klass msg cause]
  (when-let [[c0 c1 c2] (get exception-ctors klass)]
    (cond (some? cause) (when c2 (c2 msg cause))
          (some? msg) (c1 msg)
          :else (c0))))

;; host_class.zig's table: the classes outside java.lang
(def ^:private class-packages
  {"ExceptionInfo" "clojure.lang" "ArityException" "clojure.lang"
   "IOException" "java.io" "FileNotFoundException" "java.io" "EOFException" "java.io"
   "CancellationException" "java.util.concurrent" "RejectedExecutionException" "java.util.concurrent"
   "TimeoutException" "java.util.concurrent" "ExecutionException" "java.util.concurrent"
   "DateTimeException" "java.time" "DateTimeParseException" "java.time.format"
   "UnsupportedTemporalTypeException" "java.time.temporal"})

(defn- exception-string
  "Throwable.toString as the JVM writes it: cljw writes the class's short
   name (\"IllegalStateException: s\")."
  [e]
  (let [s (str e) n (str (class e))]
    (if (and (.startsWith s n) (not (.contains n ".")))
      (str (get class-packages n "java.lang") "." s)
      s)))

(defn- exception-class [e]
  (let [n (str (class e))]
    (if-let [p (get class-packages n)] (symbol (str p "." n)) (symbol n))))

;; ---------------------------------------------------------------------------
;; members of user types: cljw's own `.` form, compiled once per [name arity]
;; in this namespace; a missing member is "No implementation of method 'm'"

(def ^:private members (atom {}))
(defn- invoke-member [x nm args absent]
  (let [n (count args) k [nm n]
        f (or (get @members k)
              (let [ps (vec (repeatedly n #(gensym "a")))
                    f (binding [*ns* (the-ns 'cohost.hosts.cljw)]
                        (eval (list 'fn* (into ['o] ps)
                                    (list '. 'o (if (zero? n) (symbol nm) (apply list (symbol nm) ps))))))]
                (swap! members assoc k f)
                f))]
    (adapter/try-all (fn [] (apply f x args))
                     (fn [e]
                       (if (let [m (ex-message e)] (and m (.startsWith m "No implementation of method")))
                         absent
                         (throw e))))))

;; deftype, defrecord and reify instances: whatever is not the host's own data
(defn- user-type? [x]
  (or (record? x)
      (not (or (nil? x) (string? x) (number? x) (char? x) (boolean? x) (keyword? x) (symbol? x)
               (coll? x) (fn? x) (var? x) (seq? x) (instance? Throwable x) (class? x)
               (instance? java.util.regex.Pattern x) (instance? clojure.lang.IDeref x)
               (instance? clojure.lang.IPending x) (instance? clojure.lang.MultiFn x)
               (instance? clojure.lang.ITransientCollection x) (uuid? x) (inst? x)
               (instance? StringBuilder x)
               (contains? #{"Namespace" "array" "Atom" "Volatile" "Reduced"} (str (class x)))))))

;; ---------------------------------------------------------------------------
;; java.lang statics, each compiled by cljw or left to the shim

(defn- statics
  "{key fn} of the forms that cljw compiles: {key (fn [args] (Class/m args))}."
  [m]
  (into {} (keep (fn [[k form]] (when-let [f (adapter/try-all (fn [] (eval form)))] [k f]))) m))

(def ^:private math
  (statics
   (merge
    (into {} (for [n '[sin cos tan asin acos atan toRadians toDegrees exp log log10 sqrt cbrt ceil floor
                       rint ulp signum sinh cosh tanh expm1 log1p getExponent nextUp nextDown]]
               [n (list 'fn ['a] (list (symbol "Math" (name n)) '(double a)))]))
;; (round: cljw's rounds 0.49999999999999994 to 1; the shim's is the JVM's)
    (into {} (for [n '[incrementExact decrementExact negateExact]]
               [n (list 'fn ['a] (list (symbol "Math" (name n)) '(long a)))]))
    (into {} (for [n '[IEEEremainder atan2 pow hypot copySign nextAfter]]
               [n (list 'fn ['a 'b] (list (symbol "Math" (name n)) '(double a) '(double b)))]))
    (into {} (for [n '[addExact subtractExact multiplyExact floorDiv floorMod]]
               [n (list 'fn ['a 'b] (list (symbol "Math" (name n)) '(long a) '(long b)))]))
    {'scalb '(fn [a b] (Math/scalb (double a) (int b)))})))

(def ^:private java-statics
  (statics
   (merge
    (into {} (for [n '[isWhitespace isDigit isLetter isLetterOrDigit isAlphabetic isUpperCase isLowerCase]]
               [(symbol "Character" (name n))
                (list 'fn ['c] (list (symbol "Character" (name n)) '(if (number? c) (int c) (int (char c)))))]))
    {'Character/toUpperCase '(fn [c] (if (number? c) (int (Character/toUpperCase (char c))) (Character/toUpperCase (char c))))
     'Character/toLowerCase '(fn [c] (if (number? c) (int (Character/toLowerCase (char c))) (Character/toLowerCase (char c))))
     'Character/digit '(fn [c r] (Character/digit (char c) (int r)))
     'Character/getNumericValue '(fn [c] (Character/getNumericValue (char c)))})))

;; Extend positions: cljw's protocol dispatch reaches a value through its own
;; concrete or persistent type; java.util.Map, Set, List, Number and kin reach
;; nothing (they fall to Object). Measured per value kind (2026-10-31).
(def ^:private host-types
  '{java.util.Map [clojure.lang.IPersistentMap]
    clojure.lang.IPersistentMap [clojure.lang.IPersistentMap]
    java.util.Set [clojure.lang.APersistentSet]
    clojure.lang.IPersistentSet [clojure.lang.APersistentSet]
    java.util.List [clojure.lang.IPersistentVector clojure.lang.ISeq]
    clojure.lang.Sequential [clojure.lang.IPersistentVector clojure.lang.ISeq]
    java.util.RandomAccess [clojure.lang.IPersistentVector]
    java.util.Collection [clojure.lang.IPersistentVector clojure.lang.ISeq clojure.lang.APersistentSet]
    java.lang.Iterable [clojure.lang.IPersistentVector clojure.lang.ISeq clojure.lang.APersistentSet clojure.lang.IPersistentMap]
    clojure.lang.IPersistentCollection [clojure.lang.IPersistentVector clojure.lang.ISeq clojure.lang.APersistentSet clojure.lang.IPersistentMap]
    clojure.lang.Seqable [clojure.lang.IPersistentVector clojure.lang.ISeq clojure.lang.APersistentSet clojure.lang.IPersistentMap]
    clojure.lang.Counted [clojure.lang.IPersistentVector clojure.lang.IPersistentList clojure.lang.APersistentSet clojure.lang.IPersistentMap]
    clojure.lang.Associative [clojure.lang.IPersistentMap clojure.lang.IPersistentVector]
    java.util.Map$Entry [clojure.lang.MapEntry]
    clojure.lang.IMapEntry [clojure.lang.MapEntry]
    java.lang.Number [Long Double clojure.lang.BigInt clojure.lang.Ratio java.math.BigDecimal]
    java.lang.CharSequence [String]})

;; ---------------------------------------------------------------------------

(def adapter
  {:name :cljw
   :core-ns 'clojure.core
   :catch-all 'Throwable
   :read-forms read-forms
   :ensure-bare-ns! (fn [ns-sym]
                      (swap! cohost-nses conj ns-sym)
                      (binding [*ns* *ns*]
                        (eval (list 'ns ns-sym (list :refer-clojure :only emitted-bare)))
                        (the-ns ns-sym)))
   ;; set! of *ns* under a binding moves *ns* but not the reader's ::
   ;; resolution (it stays with the binding); in-ns moves both
   ;; (cljw's in-ns takes the name unevaluated)
   :set-current-ns! (fn [ns-sym] (eval (list 'clojure.core/in-ns (list 'quote ns-sym))))
   :refer-from-core! (fn [into-ns sym]
                       (binding [*ns* (the-ns into-ns)]
                         (ns-unmap into-ns sym)
                         (refer 'clojure.core :only [sym])))
   ;; one lookup, not the namespace's whole map
   :resolve-core (fn [sym]
                   (let [v (ns-resolve 'clojure.core sym)]
                     (when (and (var? v) (= 'clojure.core (var-ns-sym v)) (not (:private (meta v)))) v)))
   :interned-var (fn [ns-sym sym]
                   (when (find-ns ns-sym)
                     (let [v (ns-resolve ns-sym sym)]
                       (when (and (var? v) (= ns-sym (var-ns-sym v)) (= sym (:name (meta v)))) v))))
   :current-ns (fn [] (ns-name *ns*))
   :macro-var? macro-var?
   :alter-var-root! alter-root!
   :var-raw-root raw-root
   :mark-macro! mark-macro!
   :set-dynamic! set-dynamic!
   :dynamic-var? (fn [v] (true? (:dynamic (meta v))))
   :empty-queue (fn [] clojure.lang.PersistentQueue/EMPTY)
   :namespace? (fn [x] (= "Namespace" (str (class x))))
   :var-bound? (fn [v] (bound? v))
   :var-thread-bound? (fn [v] (thread-bound? v))
   :lazy-seq (fn [f] (lazy-seq (f)))
   :delay (fn [f] (delay (f)))
   :exception? (fn [x] (instance? Throwable x))
   :new-exception new-exception
   :exception-class exception-class
   :exception-string exception-string
   ;; (Exception.) has the message "" here, the JVM's none
   :exception-message (fn [e] (let [m (ex-message e)] (when-not (= "" m) m)))
   :ex-info? (fn [x] (and (instance? clojure.lang.ExceptionInfo x) (not (contains? (ex-data x) :cohost/class))))
   :nano-time (fn [] (System/nanoTime))
   :current-time-millis (fn [] (System/currentTimeMillis))
   :math-ceil (fn [x] (Math/ceil x))
   :math-floor (fn [x] (Math/floor x))
   :math-sqrt (fn [x] (Math/sqrt x))
   :parse-long (fn [s] (adapter/try-all (fn [] (Long/parseLong s))))
   :parse-double (fn [s] (Double/parseDouble s))
   :format (fn [fmt & args] (apply format fmt args))
   ;; java.util.Arrays/sort takes no comparator here
   :sort-array! (fn [arr cmp]
                  (let [v (vec (sort cmp (seq arr)))]
                    (dotimes [i (count v)] (aset arr i (nth v i)))
                    arr))
   :close! (fn [x] (.close x))
   :index-of (fn [s sub from] (.indexOf s sub (int from)))
   ;; cljw's compiler ignores :inline; the codemod applies core.clj's
   :rewrite-inline? true
   ;; (fn* name [x] ...) is a syntax error: the codemod binds name with letfn*
   :named-fn*? false
   :code-form as-code
   :host-class (fn [token] (let [c (adapter/try-all (fn [] (resolve token)))] (when (class? c) c)))
   :host-type (fn [token] (get host-types token))
   :user-type? user-type?
   :invoke-member invoke-member
   :re-find-at re-find-at
   :re-match-full re-match-full
   :re-group-count (fn [re] (.groupCount (re-matcher re "")))
   :upper-case (fn [s] (.toUpperCase (str s)))
   :lower-case (fn [s] (.toLowerCase (str s)))
   :string-builder (fn [] (StringBuilder.))
   :sb-append! (fn [sb x] (.append sb (str x)) sb)
   :sb-string (fn [sb] (str sb))
   :sb? (fn [x] (instance? StringBuilder x))
   :edn-read-string (fn [opts s] (edn/read-string opts s))
   :math math
   :java-statics java-statics
   :transient? (fn [x] (instance? clojure.lang.ITransientCollection x))
   :lazy-seq? (fn [x] (instance? clojure.lang.LazySeq x))
   :atom? (fn [x] (= "Atom" (str (class x))))
   :volatile? (fn [x] (volatile? x))
   :delay? (fn [x] (delay? x))
   :reduced? (fn [x] (reduced? x))
   :deref? (fn [x] (instance? clojure.lang.IDeref x))
   :pending? (fn [x] (instance? clojure.lang.IPending x))
   :char? (fn [x] (char? x))
   :regex? (fn [x] (instance? java.util.regex.Pattern x))
   :uuid? (fn [x] (uuid? x))
   :bigint? (fn [x] (instance? clojure.lang.BigInt x))
   :ratio? (fn [x] (ratio? x))
   :decimal? (fn [x] (decimal? x))
   :indexed? (fn [x] (indexed? x))
   :counted? (fn [x] (counted? x))
   :reversible? (fn [x] (reversible? x))
   :sorted? (fn [x] (sorted? x))
   :sorted-comparator (fn [x] (let [c (.comparator x)] (if (ifn? c) c (fn [a b] (.compare c a b)))))
   :multifn? (fn [x] (instance? clojure.lang.MultiFn x))})

(adapter/install! adapter)

;; The interop codemod. A form read from the stock clojure/core.clj is full of
;; JVM host interop: `(. clojure.lang.RT (cons x s))`, `(clojure.lang.Numbers/add
;; x y)`, `(new clojure.lang.LazySeq f)`, `(.withMeta x m)`, class symbols as
;; values (`(instance? clojure.lang.IFn x)`), `(catch IllegalArgumentException e
;; ...)`, `^String` type hints. This namespace turns every one of those into a
;; call on `cohost.shim`, whose vars map the clojure.lang surface onto the host
;; clojure-like's own data structures. Purely syntactic, host-independent; the
;; per-host facts it needs arrive in the ctx map (which shim vars exist, the
;; host's catch-all exception symbol, whether unknown interop may pass through
;; to the host untouched).
;;
;; It runs twice per form: once on the source form before eval, and once on the
;; output of every stock macro (the boot wraps each macro fn), because macros
;; like `lazy-seq`, `..`, `with-open` or `definline` emit interop into their
;; callers' code.
(ns cohost.rewrite)

;; ---------------------------------------------------------------------------
;; small string helpers (no clojure.string: keep the host surface minimal)

(defn- starts-with? [s prefix]
  (let [n (count prefix)]
    (and (>= (count s) n) (= (subs s 0 n) prefix))))

(defn- ends-with? [s suffix]
  (let [n (count suffix) m (count s)]
    (and (>= m n) (= (subs s (- m n)) suffix))))

(defn- lower-case-1 [c]
  (let [i (loop [i 0] (cond (= i 26) -1 (= c (subs "ABCDEFGHIJKLMNOPQRSTUVWXYZ" i (inc i))) i :else (recur (inc i))))]
    (if (neg? i) c (subs "abcdefghijklmnopqrstuvwxyz" i (inc i)))))

(defn- last-dot [s]
  (loop [i (dec (count s))]
    (cond (neg? i) -1
          (= "." (str (nth s i))) i
          :else (recur (dec i)))))

(def ^:private simple-class-names (atom {}))
(defn simple-class-name
  "`clojure.lang.Compiler$HostExpr` -> \"Compiler_HostExpr\"; `String` -> \"String\".
   Memoized (a per-character walk, asked at every static, constructor and
   method site)."
  [sym]
  (or (get @simple-class-names sym)
      (let [s (name sym)
            i (last-dot s)
            s (if (neg? i) s (subs s (inc i)))
            r (apply str (map (fn [c] (if (= "$" (str c)) "_" (str c))) s))]
        (swap! simple-class-names assoc sym r)
        r)))

;; ---------------------------------------------------------------------------
;; class-symbol recognition

(def java-lang-names
  "java.lang (auto-imported on the JVM) plus the classes core.clj imports."
  (set '[Object String Boolean Byte Short Integer Long Float Double Character Number
     Math System Class Thread Runnable Comparable CharSequence StringBuilder
     StringBuffer Iterable Throwable Exception Error RuntimeException
     IllegalArgumentException IllegalStateException UnsupportedOperationException
     ArithmeticException ClassCastException NullPointerException
     IndexOutOfBoundsException ArrayIndexOutOfBoundsException StringIndexOutOfBoundsException
     NumberFormatException InterruptedException AssertionError StackOverflowError
     OutOfMemoryError Void Enum Cloneable AutoCloseable Process ProcessBuilder
     Runtime ThreadLocal ClassLoader StackTraceElement Appendable
     ;; (import ...) forms in core.clj
     BigInteger BigDecimal Array ExceptionInfo IExceptionInfo BlockingQueue
     LinkedBlockingQueue Callable Future Writer]))

(def class-prefixes
  ["java." "javax." "clojure.lang." "clojure.asm." "clojure.core.protocols"])

;; Per-namespace facts, bound by `rewrite` for the namespace being compiled.
(def ^:dynamic *imports*
  "{ShortName full.class.Name} from the current namespace's (:import ...)."
  {})
(def ^:dynamic *current-ns*
  "The name (a string) of the namespace being compiled."
  nil)
(def ^:dynamic *ns-renames*
  "{\"clojure.walk\" \"cohost.walk\"}: stock libraries booted under
   another name, so explicit `clojure.walk/x` references reach them."
  {})
(def ^:dynamic *loader-nses*
  "Names (strings) of the namespaces cohost's loader made: syntax-quote there
   qualifies a class name the namespace does not import with the namespace."
  #{})
(def ^:dynamic *kw-unrenames*
  "{\"cohost.walk\" \"clojure.walk\"}: a ::keyword read inside a renamed stock
   library carries the library's own name, as on the JVM."
  {})
(defn- unrename-kw [k]
  (if-let [o (and (namespace k) (get *kw-unrenames* (namespace k)))] (keyword o (name k)) k))
(defn- unrename-data
  "Keywords inside quoted data, the same way."
  [x]
  (cond (keyword? x) (unrename-kw x)
        (and (seq? x) (seq x)) (let [y (apply list (map unrename-data x))] (if (meta x) (with-meta y (meta x)) y))
        (vector? x) (let [y (mapv unrename-data x)] (if (meta x) (with-meta y (meta x)) y))
        (map? x) (let [y (into {} (map (fn [[k v]] [(unrename-data k) (unrename-data v)]) x))] (if (meta x) (with-meta y (meta x)) y))
        (set? x) (let [y (into #{} (map unrename-data x))] (if (meta x) (with-meta y (meta x)) y))
        :else x))
(def ^:dynamic *per-arity-impls*
  "true on a host whose extend-protocol / extend-type take one clause per
   arity (basilisp) rather than (m ([x] ...) ([x y] ...))."
  false)
(def ^:dynamic *host-type*
  "nil, or a fn from a class token to the host type form(s) an `extend`,
   `extend-type` or `extend-protocol` position should name on this host."
  nil)
(def ^:dynamic *plain*
  "A name taken from a form, bare: the adapter's :plain-symbol (a let-go symbol
   carrying metadata is a wrapper no symbol equals); identity elsewhere."
  identity)

(def ^:dynamic *locals*
  "The names the enclosing forms bind (fn and let params, catch bindings, the
   binding vectors of let / loop / for / doseq ... before they expand): a
   local spelled like a class is a local."
  #{})
(def ^:dynamic *local-tags*
  "{local hint} of the enclosing locals hinted with a class whose checkcast
   decides an interop call: String.split(regex) and Pattern.split(input) share
   a name with their roles swapped, so clojure.string/split's ^Pattern re given
   a String is the JVM's ClassCastException, not a split of the separator."
  {})

(def checked-hints
  "hint -> the class token its checkcast tests: a regex where a String also has
   the method, a CharSequence (clojure.string's ^CharSequence s), a transient
   (assoc! / conj! / disj! / dissoc! / pop! / persistent! of a persistent
   collection is the JVM's ClassCastException)"
  '{Pattern java.util.regex.Pattern java.util.regex.Pattern java.util.regex.Pattern
    CharSequence java.lang.CharSequence java.lang.CharSequence java.lang.CharSequence
    clojure.lang.ITransientCollection clojure.lang.ITransientCollection
    clojure.lang.ITransientAssociative clojure.lang.ITransientAssociative
    clojure.lang.ITransientMap clojure.lang.ITransientMap
    clojure.lang.ITransientVector clojure.lang.ITransientVector
    clojure.lang.ITransientSet clojure.lang.ITransientSet})

(defn cast-name
  "The shim's prebuilt checkcast for class token c (shim/install-casts!):
   java.util.regex.Pattern -> cast$java_util_regex_Pattern."
  [c]
  (str "cast$" (apply str (map (fn [ch] (let [ch (str ch)] (if (= "." ch) "_" ch))) (str c)))))

(def ^:private object-methods
  "java.lang.Object's methods: a call of one on a hinted local is no checkcast"
  #{"toString" "hashCode" "equals" "getClass" "notify" "notifyAll" "wait"})

(defn- hint-tags
  "*local-tags* once syms are bound: a sym hinted with a checked class gains its
   class token; any other bound here shadows an outer one."
  [tags syms]
  (reduce (fn [m s] (let [t (:tag (meta s)) s (*plain* s)]
                      (if-let [c (and (symbol? t) (get checked-hints t))] (assoc m s c) (dissoc m s))))
          tags syms))

(def ^:private checked-tokens (set (vals checked-hints)))

(def ^:dynamic *local-classes*
  "{local java.lang.StringBuilder} of the enclosing locals known to hold a
   StringBuilder (hinted so, or bound to a new one): a method called on one is
   the shim's, with no user-type dispatch (no user type is a StringBuilder)."
  {})

(def ^:private sb-hints (set '[StringBuilder java.lang.StringBuilder]))
(def ^:private sb-ctors (set '[StringBuilder. java.lang.StringBuilder.]))
(def ^:private sb-returning (set '[.append .insert .reverse]))

(defn- sb-ctor?
  "Does form x build a StringBuilder?"
  [x]
  (and (seq? x)
       (let [h (first x)]
         (or (contains? sb-ctors h)
             (and (= 'new h) (contains? sb-hints (second x)))))))

(defn- class-tags
  "*local-classes* once [sym init] pairs are bound (init nil for a parameter)."
  [classes pairs]
  (reduce (fn [m [s init]] (if (or (contains? sb-hints (:tag (meta s))) (sb-ctor? init))
                             (assoc m (*plain* s) 'java.lang.StringBuilder)
                             (dissoc m (*plain* s))))
          classes pairs))

(def ^:private marked-classes
  "What a marker local can say of a local: one of these class tokens; the
   index past the last says the local is untyped (a rebinding without a hint)."
  (vec (distinct (concat (vals checked-hints) ['java.lang.StringBuilder]))))

(def ^:private marker-prefix "cohost__hint_")

(def ^:dynamic *marker-depth*
  "{local depth} of the markers in scope: a nested binding's marker is one
   deeper, and the deepest says what the local is."
  {})

(defn- marker
  "The marker local saying local holds a token instance (token nil: no class),
   at depth d. A macro's expansion is rewritten knowing only the locals its
   &env names, and jolt's carries no hints."
  [local token d]
  (let [i (or (loop [i 0] (cond (= i (count marked-classes)) nil (= token (nth marked-classes i)) i :else (recur (inc i))))
              (count marked-classes))]
    (symbol (str marker-prefix i "_" d "__" (name local)))))

(defn- parse-nat [s]
  (when (seq s)
    (loop [k 0 acc 0]
      (if (>= k (count s)) acc
          (let [v (get {"0" 0 "1" 1 "2" 2 "3" 3 "4" 4 "5" 5 "6" 6 "7" 7 "8" 8 "9" 9} (subs s k (inc k)))]
            (when v (recur (inc k) (+ (* 10 acc) v))))))))

(defn- marker-info
  "[local token-or-nil depth] of a marker symbol, else nil."
  [sym]
  (let [n (name sym) p (count marker-prefix)]
    (when (and (> (count n) p) (= marker-prefix (subs n 0 p)))
      (let [r (subs n p)
            us (loop [j 0 acc []] (if (>= j (count r)) acc (recur (inc j) (if (= "_" (subs r j (inc j))) (conj acc j) acc))))]
        ;; r is i_d__local
        (when (and (>= (count us) 3) (= (nth us 2) (inc (nth us 1))))
          (let [i (parse-nat (subs r 0 (nth us 0)))
                d (parse-nat (subs r (inc (nth us 0)) (nth us 1)))
                local (subs r (+ 2 (nth us 1)))]
            (when (and i d (<= i (count marked-classes)) (seq local))
              [(symbol local) (when (< i (count marked-classes)) (nth marked-classes i)) d])))))))

(defn- env-types
  "{:tags :classes :depth} from the markers among a macro's &env locals: for
   each local, its deepest marker."
  [locals]
  (let [deepest (reduce (fn [m [l t d]] (if (> d (get-in m [l 1] 0)) (assoc m l [t d]) m))
                        {} (keep marker-info locals))]
    ;; (seq deepest): its entries (Phel's keep and map over a map see its values)
    {:tags (into {} (keep (fn [[l [t]]] (when (contains? checked-tokens t) [l t]))) (seq deepest))
     :classes (into {} (keep (fn [[l [t]]] (when (= 'java.lang.StringBuilder t) [l t]))) (seq deepest))
     :depth (into {} (map (fn [[l [_ d]]] [l d])) (seq deepest))}))

(defn- markers-for
  "The marker bindings for syms, bound with the given tags and classes: a
   typed local, or one that rebinds a name a marker typed. -> [bindings depths]"
  [syms tags classes]
  (reduce (fn [[bs ds] s]
            (let [t (or (get tags s) (get classes s))]
              (if (or t (contains? *marker-depth* s))
                (let [d (inc (get *marker-depth* s 0))]
                  [(conj bs (marker s t d) nil) (assoc ds s d)])
                [bs ds])))
          [[] *marker-depth*] (remove #{'&} syms)))

(defn- with-markers
  "body under a let* of the marker bindings (when there are any)."
  [bs body]
  (if (seq bs) [(apply list 'let* bs body)] body))

(def ^:private class-prefixed (atom {}))
(defn- class-prefixed?
  "Does the name start with a JVM package prefix? Memoized per name (the
   rewriter asks it of every bare symbol it walks)."
  [s]
  (let [c @class-prefixed]
    (if (contains? c s)
      (get c s)
      (let [r (boolean (some #(starts-with? s %) class-prefixes))]
        (if (> (count c) 50000) (reset! class-prefixed {s r}) (swap! class-prefixed assoc s r))
        r))))

(defn class-sym?
  "A bare symbol naming a JVM class (or clojure.core.protocols, whose fns the
   rewriter treats like statics: `clojure.core.protocols/coll-reduce`)."
  [x]
  (and (symbol? x) (nil? (namespace x))
       (or (contains? java-lang-names x)
           (contains? *imports* x)
           (class-prefixed? (name x)))))

(def static-fields
  "Members of `(. Class member)` / `Class/member` that are fields (values), not
   zero-arg methods. Everything else bare is called."
  (set '[creator EMPTY TRUE FALSE MAX_VALUE MIN_VALUE TYPE NaN POSITIVE_INFINITY
     NEGATIVE_INFINITY out err in ZERO ONE TEN CASE_INSENSITIVE_ORDER
     MILLISECONDS SECONDS NANOSECONDS MICROSECONDS specials REQUIRE_LOCK checkSpecAsserts
     PI E MIN_NORMAL MIN_EXPONENT MAX_EXPONENT SIZE BYTES]))

(def core-ns-names
  "Syntax-quote in a core namespace qualifies an unknown class name with the
   namespace: `Array` read inside cohost.core becomes cohost.core/Array."
  #{"cohost.core" "clojure.core" "basilisp.core" "cljs.core"})

(defn declass
  "cohost.core/Array -> Array, cohost.core/Exception. -> Exception. when the name
   is a class (or a class constructor); else the symbol itself. Syntax-quote in
   a library namespace qualifies its imported class names with that namespace."
  [x]
  (if (and (symbol? x) (namespace x)
           (or (contains? core-ns-names (namespace x)) (= *current-ns* (namespace x))
               (contains? *loader-nses* (namespace x))))
    (let [n (name x)
          base (if (and (> (count n) 1) (ends-with? n ".")) (subs n 0 (dec (count n))) n)]
      (if (class-sym? (symbol base)) (symbol n) x))
    x))

(def special-heads
  (set '[quote var . new try catch finally fn* let* loop* letfn* def do if throw
     recur set! monitor-enter monitor-exit deftype* reify* case* import*]))

(defn canon-head
  "basilisp.core/new -> new: some readers namespace-qualify special forms
   inside syntax-quote."
  [h]
  (if (and (symbol? h) (namespace h) (contains? core-ns-names (namespace h))
           (contains? special-heads (symbol (name h))))
    (symbol (name h))
    h))

(def reader-plumbing
  "clojure.core/ names a reader emits for `, ~@, @ and #': host plumbing that
   stays the host's."
  (set '[deref seq concat list vector hash-map hash-set with-meta apply unquote
     unquote-splicing syntax-quote import* in-ns refer alias load name]))

(defn restock
  "Explicit clojure.core/x references name the booted core; explicit
   references to a stock library booted under another name name that."
  [ctx x]
  (if-let [ns (and (symbol? x) (namespace x) (not (:cohost/host (meta x))) (namespace x))]
    (cond (= "clojure.core" ns) (if (or (and (contains? reader-plumbing (symbol (name x)))
                                             ;; @x after the boot names the stock deref, so
                                             ;; with-redefs of deref reaches it, as on the JVM
                                             (not (and (= "deref" (name x)) @(:booted? ctx))))
                                        ;; a host var referred into the booted core (*out*)
                                        ;; is resolved through the host's own namespace
                                        (contains? @(:host-referred ctx) (symbol (name x))))
                                  x
                                  (symbol (:core-ns ctx) (name x)))
          (contains? *ns-renames* ns) (symbol (get *ns-renames* ns) (name x))
          :else x)
    x))

(def catch-all-classes
  "Catches everything. `Exception` is not here: on the JVM it leaves Errors
   (AssertionError, StackOverflowError) alone, which the shim's class test
   reproduces."
  (set '[Throwable Object java.lang.Throwable java.lang.Object :default]))

;; ---------------------------------------------------------------------------
;; shim symbol naming

(def shim-ns "cohost.shim")

(def ^:dynamic *shim-ref*
  "How a rewritten form names a shim var: by default the qualified symbol
   cohost.shim/<name>; a host whose evaluator cannot see the compiled
   shim namespace (self-hosted ClojureScript) supplies a js/ property path."
  (fn [nm] (symbol shim-ns nm)))

(defn static-sym [c m] (*shim-ref* (str (simple-class-name c) "-" (name m))))
(defn ctor-sym   [c]   (*shim-ref* (str "new-" (simple-class-name c))))
(defn method-sym [m]   (*shim-ref* (str "-" (name m))))
(defn- shim-fn   [nm]  (*shim-ref* nm))

(defn make-ctx
  "shim-names: the set of (unqualified) symbols interned in cohost.shim.
   catch-all: the host symbol a `catch` clause may name to catch everything.
   passthrough?: leave interop the shim lacks for the host to try (jolt has a
   wide java.* shim of its own); false means it becomes a `cohost.shim/missing-*`
   call that throws (and is reported) when reached."
  [{:keys [shim-names catch-all passthrough? core-ns shim-ref loop-variadic-recur? def-meta strip-inline?
           current-ns imports renames host-type var-form per-arity-impls? python-methods? move-methods? host-method
           record? protocol-var? interned? host-aliases inline-fn resolve-head stock-name rewritten-macro?
           instance-fn missing-dot try-all named-fn*? special-forms vector-rest-args? plain-symbol]
    :or {shim-names #{} catch-all 'Exception passthrough? false core-ns "cohost.core"}}]
  {:plain-symbol (or plain-symbol identity)
   :shim-names shim-names
   ;; {shim static name -> the host core's symbol} where the shim's static is
   ;; the host's own fn: emitted instead, so the host's compiler sees (and may
   ;; inline) its own +, first, get ...
   :host-aliases (or host-aliases {})
   ;; resolve-head: (fn [sym]) -> the var a head symbol names in the namespace
   ;; being compiled; stock-name: (fn [var]) -> its name when it is a var of the
   ;; booted core; rewritten-macro?: (fn [var]) is it a macro whose expansion the
   ;; codemod rewrites (its arguments then stay as written); inline-fn:
   ;; (fn [var arity]) -> the :inline fn of a core fn, where the host's compiler
   ;; ignores :inline and the codemod applies it (nil elsewhere)
   ;; instance-fn: (fn [class-token]) -> the name of the shim's prebuilt
   ;; instance test for it; missing-dot: (fn [method]) -> the name of the
   ;; dispatcher over a method the shim lacks (both built on first use)
   :instance-fn instance-fn
   :missing-dot missing-dot
   :kw-unrenames-cache (atom nil)
   :resolve-head resolve-head
   :stock-name (or stock-name (constantly nil))
   :rewritten-macro? (or rewritten-macro? (constantly false))
   :inline-fn inline-fn
   ;; try-all: (fn [thunk]) -> (thunk), or nil when it throws anything: the
   ;; host's catch-all (cohost.adapter/try-all), for an inline expansion that
   ;; does not take the form it is given
   :try-all (or try-all (fn [thunk] (thunk)))
   ;; false where the host's fn* takes no name: (fn* nm ...) becomes
   ;; (letfn* [nm (fn* ...)] nm)
   :named-fn*? (if (nil? named-fn*?) true (boolean named-fn*?))
   ;; {fn* fn ...}: the host's names for the special forms in the output
   :special-forms special-forms
   ;; a host whose & rest argument is a vector (Phel): each variadic arity
   ;; makes it a seq (or nil) first, as stock code builds forms of it
   :vector-rest-args? (boolean vector-rest-args?)
   ;; per-namespace facts: a thunk naming the namespace being compiled, an atom
   ;; {ns-name {Short full}} of imports, an atom {orig-ns mapped-ns} of renamed
   ;; stock libraries, and the host's type translation for extend positions
   :current-ns current-ns
   :imports (or imports (atom {}))
   :renames (or renames (atom {}))
   :host-type host-type
   :var-form var-form
   :per-arity-impls? (boolean per-arity-impls?)
   ;; a host whose deftype / reify methods are Python methods (basilisp): one
   ;; definition per name, plain parameters, a protocol receiver named this;
   ;; host-method: (fn [class-token method] host-method-name-or-nil)
   :python-methods? (boolean python-methods?)
   ;; a host whose JVM interface methods are protocol methods of its own
   ;; (ClojureScript): host-method gives [protocol method] for each
   :move-methods? (boolean move-methods?)
   :host-method host-method
   ;; record?: is a form a record instance (a reader literal); protocol-var?:
   ;; (fn [ns-sym name-sym]) is that var a protocol; interned?: (fn [sym]) is
   ;; sym a var of the namespace being compiled — such a name is never a class
   ;; token. user-types: names deftype / defrecord forms defined, same reason.
   :record? record?
   :protocol-var? protocol-var?
   :interned? interned?
   :user-types (atom #{})
   :host-referred (atom #{})
   :loader-nses (atom #{})
   :booted? (atom false)
   :catch-all catch-all
   :passthrough? passthrough?
   :core-ns core-ns
   :shim-ref shim-ref
   :loop-variadic-recur? (boolean loop-variadic-recur?)
   :strip-inline? (boolean strip-inline?)
   ;; metadata every `def` name gets on this host (basilisp: {:redef true}, so
   ;; alter-var-root and with-redefs reach compiled references)
   :def-meta def-meta
   :misses (atom {})})

(defn- known?
  "Is the shim var (named by its unqualified name) present?"
  [ctx nm] (contains? (:shim-names ctx) (symbol nm)))

(defn- static-name [c m] (str (simple-class-name c) "-" (name m)))
(defn- ctor-name [c] (str "new-" (simple-class-name c)))
(defn- method-name [m] (str "-" (name m)))

(defn- miss! [ctx kind & ks]
  (swap! (:misses ctx) update (into [kind] ks) (fnil inc 0))
  nil)

(defn misses
  "{[kind class member] count} of interop the shim lacked, so far."
  [ctx] @(:misses ctx))

;; ---------------------------------------------------------------------------
;; metadata: type hints and inline fns are JVM-compiler directives

(def ^:dynamic *strip-inline?*
  "Whether a def's :inline / :inline-arities are dropped. The JVM host inlines
   its own interop; jolt ignores the key; a host that honors it (basilisp)
   would compile the quoted JVM interop the stock inline fns build."
  false)

(declare strip-meta)

(defn- strip-arglists
  "An :arglists value with its type hints gone, as the params' are: the JVM
   compiler reads a ^long there as a primitive interface the fn lacks."
  [x]
  (cond (symbol? x) (if (meta x) (with-meta x (strip-meta (meta x))) x)
        (vector? x) (with-meta (mapv strip-arglists x) (strip-meta (meta x)))
        (seq? x) (let [y (apply list (map strip-arglists x))] (if (meta x) (with-meta y (meta x)) y))
        :else x))

(defn- strip-meta
  "Type hints are JVM-compiler directives and name JVM classes (a symbol or a
   string); any other :tag is data and stays."
  [m]
  (when m
    (let [m (if (let [t (:tag m)] (or (symbol? t) (string? t))) (dissoc m :tag) m)
          m (dissoc m :rettag)
          m (if *strip-inline?* (dissoc m :inline :inline-arities) m)
          m (if (contains? m :arglists) (update m :arglists strip-arglists) m)]
      (when (seq m) m))))

(defn- meta-capable? [x]
  (or (seq? x) (vector? x) (map? x) (set? x) (symbol? x)))

(defn- carry-meta
  "Give `out` the (stripped) metadata of `orig`."
  [out orig]
  (if (meta-capable? out)
    (let [m (strip-meta (meta orig))]
      (if (= m (meta out)) out (with-meta out (or m {}))))
    out))

;; ---------------------------------------------------------------------------
;; the walk

(declare rw)

(defn- rw-all [ctx xs] (doall (map #(rw ctx %) xs)))

(def settable-statics
  "Static fields a program may set!: read through cohost.shim/static-get."
  #{"RT-checkSpecAsserts"})

(defn- imported
  "c as the namespace being compiled imported it (cohost keeps its own
   import table: the host namespace imports nothing), for interop left to
   the host."
  [c]
  (get *imports* c c))

(defn- emit-static-value [ctx c m]
  (let [nm (static-name c m)]
    (cond (contains? settable-statics nm) (list (shim-fn "static-get") nm)
          (known? ctx nm) (static-sym c m)
          (:passthrough? ctx) nil
          :else (do (miss! ctx :static-field c m)
                    (list (shim-fn "missing-static-value") (list 'quote c) (list 'quote m))))))

(defn- rw-static [ctx c m args]
  (let [args' (rw-all ctx args)
        nm (static-name c m)]
    (cond (known? ctx nm) (apply list (or (get (:host-aliases ctx) nm) (static-sym c m)) args')
          (:passthrough? ctx) (list '. (imported c) (apply list m args'))
          ;; the head is a call returning the thrower, so the arguments keep the
          ;; positions `(. C (m args))` had: -> and doto insert into them.
          :else (do (miss! ctx :static c m)
                    (apply list (list (shim-fn "missing-static") (list 'quote c) (list 'quote m)) args')))))

(defn- sb-typed?
  "Is form x certainly a StringBuilder (a local known to hold one, a new one,
   or what append, insert or reverse of one returns)?"
  [x]
  (cond (symbol? x) (= 'java.lang.StringBuilder (get *local-classes* x))
        (sb-ctor? x) true
        (seq? x) (let [h (first x)]
                   (and (symbol? h) (nil? (namespace h))
                        (contains? sb-returning h)
                        (sb-typed? (second x))))
        :else false))

(defn- rw-method
  "(.m target args) -> (cohost.shim/dot$m target args), the shim's prebuilt
   dispatcher for m (or ((cohost.shim/dot \"m\" f) target args) where there
   is none): the head stays one form, so the arguments keep the positions
   the source had (-> and doto insert into them). An object of a type user
   code defined answers its own member m when it has one; anything else goes
   to f, the shim's -m (or, when the shim lacks it, a thrower that reports
   the miss). On a host with a java.* surface of its own, interop the shim
   lacks is left to the host."
  [ctx m xs host-form]
  ;; xs: the target and the arguments as written — none at all in a
  ;; `(.m)` a threading macro will fill, so no target is invented
  (let [xs' (rw-all ctx xs)
        ;; a target hinted with a checked class: the JVM's checkcast first, to
        ;; the class declaring the method (Object's methods need no cast:
        ;; clojure.string/upper-case of a keyword is its toString upper-cased)
        xs' (if-let [c (and (symbol? (first xs)) (not (contains? object-methods (name m)))
                            (get *local-tags* (first xs)))]
              (cons (let [d (cast-name c)]
                      (if (known? ctx d)
                        (list (shim-fn d) (first xs'))
                        (list (shim-fn "check-cast") (list 'quote c) (first xs'))))
                    (rest xs'))
              xs')
        nm (method-name m)
        f (cond (known? ctx nm) (method-sym m)
                (:passthrough? ctx) nil
                :else (do (miss! ctx :method m)
                          (list (shim-fn "missing-method") (list 'quote m))))]
    (if f
      (let [d (str "dot$" (name m))]
        (cond
          ;; (.reduce (chunk-first s) f val): IChunk.reduce, a reduced result
          ;; unopened (a host's chunk may be a vector, whose reduce opens it)
          (and (= "reduce" (name m)) (= 3 (count xs)) (known? ctx "chunk-reduce")
               (let [t (first xs)] (and (seq? t) (symbol? (first t)) (= "chunk-first" (name (first t))))))
          (apply list (shim-fn "chunk-reduce") xs')
          ;; a StringBuilder: the shim's method, no user-type dispatch
          (and (known? ctx nm) (sb-typed? (first xs)))
          (apply list (method-sym m) xs')
          ;; the prebuilt dispatcher (cohost.shim/install-dots!)
          (known? ctx d) (apply list (shim-fn d) xs')
          ;; a method the shim lacks: its thrower's dispatcher, built once
          (and (not (known? ctx nm)) (:missing-dot ctx)) (apply list (shim-fn ((:missing-dot ctx) m)) xs')
          :else (apply list (list (shim-fn "dot") (name m) f) xs')))
      ;; the host's own interop, spelled as the source spelled it (a record
      ;; field read by (.f r) is a field there, not a zero-argument method)
      (host-form xs'))))

(declare class-token)

(def host-collections
  "java.util collections a host with a java.* surface has natively: code
   outside cohost.core gets the host's own (the shim's stand-ins serve
   core.clj's internals, partition-all and partition-by)."
  (set '[java.util.ArrayList java.util.LinkedList]))

(defn- rw-new [ctx c args]
  (let [args' (rw-all ctx args)
        nm (ctor-name c)]
    (cond (and (:passthrough? ctx) (not= *current-ns* (:core-ns ctx)) (contains? host-collections (class-token c)))
          (apply list 'new (class-token c) args')
          (known? ctx nm) (apply list (ctor-sym c) args')
          (:passthrough? ctx) (apply list 'new (imported c) args')
          :else (do (miss! ctx :ctor c)
                    (apply list (list (shim-fn "missing-ctor") (list 'quote c)) args')))))

(defn- rw-dot
  "(. target member) / (. target (member args...)) / (. target member args...)"
  [ctx form]
  (let [[_ target & more] form
        target (declass target)
        x (first more)
        call? (seq? x)
        [m args] (if call? [(first x) (rest x)] [x (rest more)])
        ;; a member is named by its name alone, as the JVM compiler reads it: a
        ;; syntax-quoted inline expansion spells (. RT (cohost.core/aget a i))
        m (if (and (symbol? m) (namespace m)) (symbol (name m)) m)]
    (cond
      (class-sym? target)
      (if (and (not call?) (empty? args) (contains? static-fields m))
        (or (emit-static-value ctx target m) form)
        (rw-static ctx target m args))
      ;; (. x -field) is field access, the host's own (as (.-field x) is)
      (and (not call?) (symbol? m) (let [n (name m)] (and (> (count n) 1) (= "-" (subs n 0 1)))))
      (apply list '. (rw ctx target) m (rw-all ctx args))
      :else (rw-method ctx m (cons target args)
                       (fn [[t & as]] (if call? (list '. t (apply list m as)) (apply list '. t m as)))))))

(defn- rw-try [ctx [_ & body]]
  (let [catch? #(and (seq? %) (= 'catch (canon-head (first %))))
        finally? #(and (seq? %) (= 'finally (canon-head (first %))))
        exprs (rw-all ctx (remove #(or (catch? %) (finally? %)) body))
        catches (filter catch? body)
        finallys (map (fn [[_ & fb]] (apply list 'finally (rw-all ctx fb))) (filter finally? body))
        ;; this codemod's own output, walked again inside a macro's expansion:
        ;; its one catch-all clause is kept, not wrapped in another layer
        own? (and (= 1 (count catches))
                  (let [[_ c b] (first catches)]
                    (and (= c (:catch-all ctx)) (symbol? b) (starts-with? (name b) "e__stock"))))
        catch-clause
        (if own?
          (let [[_ c b & cb] (first catches)]
            [(apply list 'catch c b (binding [*locals* (conj *locals* b)] (doall (rw-all ctx cb))))])
          (when (seq catches)
            (let [e (gensym "e__stock")
                  ;; one host catch; dispatch on the JVM class inside it, in order.
                  clauses (map (fn [[_ c b & cb]]
                                 (let [c (declass c)]
                                   [(cond (contains? catch-all-classes c) true
                                          ;; a JVM class: the shim's class test, of the class
                                          ;; the namespace imported under that name
                                          (class-sym? c) (list (shim-fn "catches?") (list 'quote (imported c)) e)
                                          ;; a host class (js/Error, a record): the host's instance?
                                          :else (list 'instance? c e))
                                    (apply list 'let* [b e] (binding [*locals* (conj *locals* b)] (doall (rw-all ctx cb))))]))
                               catches)
                  chain (reduce (fn [else [test then]]
                                  (if (true? test) then (list 'if test then else)))
                                (list 'throw e)
                                (reverse clauses))]
              [(list 'catch (:catch-all ctx) e chain)])))]
    (apply list 'try (concat exprs catch-clause finallys))))

(defn- rw-params [params]
  (carry-meta (mapv #(if (symbol? %) (carry-meta % %) %) params) params))

(defn- mentions? [sym form]
  (boolean (some #(= sym %) (tree-seq coll? seq form))))

(defn- loop-variadic-recur
  "On a host whose `recur` in a variadic arity re-packs the trailing argument
   into a fresh rest seq (basilisp) instead of binding the rest param to it
   (the JVM: `(recur (conj coll x) (first xs) (next xs))` in core.clj's conj),
   the arity's body runs in a `loop*` over its own params: a free `recur`
   then targets the loop and rebinds the rest param positionally, as the JVM
   does. Nested loops and fns keep their own recur targets."
  [params body]
  (if (and (some #(= '& %) params) (mentions? 'recur body))
    (let [names (remove #(= '& %) params)]
      [(apply list 'loop* (vec (mapcat (fn [n] [n n]) names)) body)])
    body))

(defn- rw-fn* [ctx form]
  (let [more (rest form)
        [nm more] (if (symbol? (first more)) [(carry-meta (first more) (first more)) (rest more)] [nil more])
        single? (vector? (first more))
        arities (if single? [more] more)
        names (into (if nm #{nm} #{})
                    (mapcat (fn [[params]] (filter #(and (symbol? %) (not= '& %)) params)) arities))
        arities' (binding [*locals* (into *locals* names)]
                   (doall (map (fn [[params & body]]
                                 (let [ps0 (filter symbol? params)
                                       ps (map *plain* ps0)
                                       tags (hint-tags *local-tags* ps0)
                                       classes (class-tags *local-classes* (map (fn [p] [p nil]) ps))
                                       [bs depths] (markers-for ps tags classes)
                                       body' (binding [*local-tags* tags *local-classes* classes *marker-depth* depths]
                                               (doall (rw-all ctx body)))
                                       body' (with-markers bs body')
                                       body' (if (:loop-variadic-recur? ctx) (loop-variadic-recur params body') body')
                                       rest-arg (second (drop-while #(not= '& %) params))
                                       body' (if (and (:vector-rest-args? ctx) (symbol? rest-arg))
                                               [(apply list 'let* [rest-arg (list (shim-fn "RT-seq") rest-arg)] body')]
                                               body')]
                                   (apply list (rw-params params) body')))
                               arities)))]
    (if (and nm (not (:named-fn*? ctx)))
      (list 'letfn* [nm (apply list (carry-meta 'fn* (first form)) (if single? (first arities') arities'))] nm)
      (apply list (carry-meta 'fn* (first form)) (concat (when nm [nm]) (if single? (first arities') arities'))))))

(defn- rw-bindings [ctx bvec]
  (carry-meta (vec (mapcat (fn [[b v]] [(if (symbol? b) (carry-meta b b) (rw ctx b)) (rw ctx v)])
                           (partition 2 bvec)))
              bvec))

(defn- rw-let* [ctx [h bvec & body]]
  (let [syms0 (filter symbol? (take-nth 2 bvec))
        syms (map *plain* syms0)
        tags (hint-tags *local-tags* syms0)
        classes (class-tags *local-classes* (filter (comp symbol? first) (partition 2 bvec)))
        [bs depths] (markers-for syms tags classes)]
    (binding [*locals* (into *locals* syms)
              *local-tags* tags *local-classes* classes *marker-depth* depths]
      (let [bvec' (rw-bindings ctx bvec)
            body' (doall (rw-all ctx body))]
        (if (and (= h 'let*) (seq bs))
          ;; each marker right after its local, so the inits after it see it
          ;; too (a loop*'s bindings are recur's arity: its body gets them)
          (let [ms (into {} (map (fn [[m _]] [(first (marker-info m)) m]) (partition 2 bs)))
                bvec'' (vec (mapcat (fn [[b v]] (if-let [m (and (symbol? b) (get ms (*plain* b)))] [b v m nil] [b v]))
                                    (partition 2 bvec')))]
            (apply list h (carry-meta bvec'' bvec') body'))
          (apply list h bvec' (with-markers bs body')))))))

;; Binding forms before they expand: the names they bind are locals in the
;; whole form, so `(let [String "x"] String)` keeps its local.
(defn- pattern-syms [p]
  (cond (symbol? p) (let [p (*plain* p)] (if (= '& p) [] [p]))
        (vector? p) (mapcat pattern-syms p)
        (map? p) (mapcat (fn [[k v]]
                           (cond (= k :as) [v]
                                 (= k :or) []
                                 (and (keyword? k) (contains? #{"keys" "syms" "strs"} (name k)))
                                 (map (fn [x] (symbol (name x))) v)
                                 (keyword? k) []
                                 :else (pattern-syms k)))
                         p)
        :else []))

(defn- bvec-syms
  "let-style [pattern init ...], with for / doseq modifiers (:let nests)."
  [bvec]
  (loop [xs (seq (if (vector? bvec) bvec [])) acc []]
    (if xs
      (let [p (first xs) v (second xs)]
        (cond (= p :let) (recur (nnext xs) (into acc (bvec-syms v)))
              (keyword? p) (recur (nnext xs) acc)
              :else (recur (nnext xs) (into acc (pattern-syms p)))))
      acc)))

(defn- fn-syms
  "fn / defn / defmacro arguments: every params vector, at the top level or
   heading an arity."
  [args]
  (mapcat (fn [a] (cond (vector? a) (pattern-syms a)
                        (and (seq? a) (vector? (first a))) (pattern-syms (first a))
                        :else []))
          args))

(def binding-heads
  #{"let" "loop" "for" "doseq" "if-let" "when-let" "if-some" "when-some" "with-open" "dotimes"
    "with-local-vars" "fn" "defn" "defn-" "defmacro" "defmethod" "letfn" "as->"})

(defn- bound-names [hn args]
  (case hn
    ("let" "loop" "for" "doseq" "if-let" "when-let" "if-some" "when-some" "with-open" "dotimes" "with-local-vars")
    (bvec-syms (first args))
    ("fn" "defn" "defn-" "defmacro" "defmethod")
    (concat (when (symbol? (first args)) [(first args)]) (fn-syms args))
    "letfn" (mapcat (fn [spec] (when (seq? spec) (cons (first spec) (fn-syms (rest spec))))) (first args))
    "as->" (pattern-syms (second args))
    []))

(defn- rw-case
  "(case e test result ... default?): the tests are data the host's case
   reads as written (a symbol spelled like a class stays a symbol), except
   that keywords read in a renamed library take back their own namespace
   (::pcat in clojure.spec.alpha is :clojure.spec.alpha/pcat); e, the results
   and the default are rewritten."
  [ctx h [e & clauses]]
  (let [n (count clauses)]
    (apply list h (rw ctx e)
           (doall (map-indexed (fn [i x] (if (and (even? i) (< i (dec n))) (unrename-data x) (rw ctx x))) clauses)))))

(def ^:private code-meta-keys
  "Keys of a def name's metadata whose value is code the host evaluates:
   deftest's :test fn, core.clj's :inline fns."
  [:test :inline :inline-arities])

(defn- rw-def [ctx [_ nm & more]]
  (let [nm (carry-meta nm nm)
        nm (if (some #(contains? (meta nm) %) code-meta-keys)
             (vary-meta nm (fn [m] (reduce (fn [m k] (if (contains? m k) (update m k #(rw ctx %)) m)) m code-meta-keys)))
             nm)
        nm (if-let [dm (:def-meta ctx)] (vary-meta nm merge dm) nm)]
    (apply list 'def nm (rw-all ctx more))))

(defn- rw-map
  "A map literal is data: its keys and values are rewritten, nothing is
   removed (an attr-map's :tag/:inline reach the var through the def's
   symbol metadata, which carry-meta strips). A record literal is left as
   read; any other map keeps its kind (sorted, array)."
  [ctx m]
  (if (and (:record? ctx) ((:record? ctx) m))
    m
    (carry-meta (into (empty m) (map (fn [[k v]] [(rw ctx k) (rw ctx v)]) m)) m)))

(def class-canon
  "The full name of a bare class symbol: java.lang's are auto-imported, the
   rest are what core.clj imports."
  '{BigInteger java.math.BigInteger BigDecimal java.math.BigDecimal
    Array java.lang.reflect.Array ExceptionInfo clojure.lang.ExceptionInfo
    IExceptionInfo clojure.lang.IExceptionInfo
    BlockingQueue java.util.concurrent.BlockingQueue
    LinkedBlockingQueue java.util.concurrent.LinkedBlockingQueue
    Callable java.util.concurrent.Callable Future java.util.concurrent.Future
    Writer java.io.Writer})

(defn class-token
  "A class symbol as a value: its full name, so the token compares equal to
   what `class` returns and dispatches a multimethod defined on it."
  [s]
  (cond (contains? *imports* s) (get *imports* s)
        (contains? class-canon s) (get class-canon s)
        (neg? (last-dot (name s))) (symbol (str "java.lang." (name s)))
        :else s))

(defn- user-name?
  "A bare symbol spelled like a class that is a local, a var of the namespace
   being compiled, or a type user code defined: not a class token."
  [ctx s]
  (or (contains? *locals* s)
      (contains? @(:user-types ctx) s)
      (boolean (when-let [f (:interned? ctx)] (f s)))))

(defn- rw-symbol [ctx s]
  (let [s0 s
        s (restock ctx (declass (*plain* s)))]
    (cond
    ;; a policy replacement names the shim by its qualified symbol
      (and (namespace s) (= shim-ns (namespace s))) (shim-fn (name s))
      (and (namespace s) (class-sym? (symbol (namespace s))))
      (or (emit-static-value ctx (symbol (namespace s)) (symbol (name s)))
          (let [c (symbol (namespace s))] (if (= c (imported c)) s (symbol (str (imported c)) (name s)))))

      (and (class-sym? s) (not (user-name? ctx s))) (list 'quote (class-token s))
      :else (carry-meta s s0))))

(def ^:private extend-heads (set '[extend extend-type extend-protocol]))

(def spec-heads
  "Host forms whose structure names classes and interfaces (never values):
   only their method bodies are rewritten."
  (set '[deftype defrecord reify proxy extend-type extend-protocol extend
     definterface defprotocol]))

(defn- spec-head
  "The unqualified spec head of h, also when a macro's syntax-quote qualified it."
  [h]
  (when (and (symbol? h) (contains? spec-heads (symbol (name h)))
             (or (nil? (namespace h)) (contains? core-ns-names (namespace h))))
    (symbol (name h))))

(defn- host-types
  "The host type forms a type position stands for: a class symbol goes
   through the host's translation (one or several types); anything else, and
   any class the host names natively, stays."
  [t]
  (if (and (symbol? t) (class-sym? t))
    ;; by its full name: a host namespace need not import java.lang
    (let [h (when *host-type* (*host-type* (class-token t)))]
      (cond (nil? h) [(class-token t)] (vector? h) h :else [h]))
    [t]))


(defn- bound-syms
  "The symbols a params or fields vector binds, destructuring included (one
   too many only keeps a call from being inlined)."
  [params]
  (remove #{'&} (map *plain* (filter symbol? (tree-seq coll? seq params)))))

(defn- rw-body
  "body rewritten with the symbols params binds as locals: a field or method
   parameter named like an inline core fn (inc, compare) is no call of it."
  [ctx params body]
  (binding [*locals* (into *locals* (bound-syms params))]
    (doall (rw-all ctx body))))

(defn- rw-method-impls
  "`(m [params] body...)` or `(m ([params] body...) ...)`: bodies rewritten;
   on a per-arity host, one clause per arity. -> a seq of clauses."
  [ctx x]
  (if (and (seq? x) (symbol? (first x)))
    (let [[m & arities] x]
      (if (vector? (first arities))
        [(apply list m (first arities) (rw-body ctx (first arities) (rest arities)))]
        (let [as (mapv (fn [a] (if (and (seq? a) (vector? (first a)))
                                 (apply list (first a) (rw-body ctx (first a) (rest a)))
                                 a))
                       arities)]
          (if *per-arity-impls*
            (map (fn [a] (apply list m a)) as)
            [(apply list m as)]))))
    [(rw ctx x)]))

(defn- protocol-iface
  "a.lib.Proto -> mapped.lib/Proto when a.lib is a stock library booted under
   another name (or a namespace the loader made) and Proto is one of its
   protocols; else nil (a record or deftype keeps its class test)."
  [ctx s]
  (let [n (name s) i (last-dot n)]
    (when (pos? i)
      (let [prefix (subs n 0 i) nm (subs n (inc i))]
        (when-let [mapped (or (get *ns-renames* prefix) (when (contains? *loader-nses* prefix) prefix))]
          (when (and (seq nm) (not= (str (first nm)) (lower-case-1 (str (first nm))))
                     (let [p? (:protocol-var? ctx)] (or (nil? p?) (p? (symbol mapped) (symbol nm)))))
            (symbol mapped nm)))))))

(defn- type-groups
  "extend-protocol / extend-type bodies: [[head [impl ...]] ...], a head being
   any non-list (a type, nil, or a protocol name)."
  [xs]
  (reduce (fn [acc x]
            (if (seq? x)
              (if (seq acc) (update-in acc [(dec (count acc)) 1] conj x) acc)
              (conj acc [x []])))
          [] xs))

(def ^:private bare-base-types (set '[default object string number boolean function array symbol bigint]))

(defn- rw-extend [ctx h args]
  (case h
    extend-protocol
    (let [[p & specs] args]
      (apply list h p (mapcat (fn [[t impls]]
                                (mapcat (fn [ht] (cons ht (mapcat #(rw-method-impls ctx %) impls)))
                                        (host-types t)))
                              (type-groups specs))))
    extend-type
    (let [[t & specs] args
          body (mapcat (fn [[p impls]] (cons p (mapcat #(rw-method-impls ctx %) impls)))
                       (type-groups specs))
          forms (map (fn [ht] (apply list h ht body)) (host-types t))]
      (if (next forms) (apply list 'do forms) (first forms)))
    extend
    (let [[t & more] args
          more (rw-all ctx more)
          ;; a base type a host names by a bare word (ClojureScript's default,
          ;; string ...) is a value to the extend fn: quoted
          forms (map (fn [ht] (apply list h (cond (and (symbol? ht) (nil? (namespace ht))
                                                       (contains? bare-base-types ht))
                                                  (list 'quote ht)
                                                  (symbol? ht) ht
                                                  :else (rw ctx ht))
                                     more))
                     (host-types t))]
      (if (next forms) (apply list 'do forms) (first forms)))))

(defn- plain-params
  "A method's parameter vector with every destructuring pattern replaced by a
   fresh symbol -> [params bindings], the bindings re-binding each pattern."
  [params]
  (reduce (fn [[ps bs] p]
            (if (symbol? p)
              [(conj ps p) bs]
              (let [g (gensym "p__")] [(conj ps g) (conj bs p g)])))
          [[] []] params))

(defn- method-clause? [x]
  (and (seq? x) (symbol? (first x)) (vector? (second x))))

(defn- rw-method-clause
  "(m [params] body...) with its body rewritten. On a :python host a
   destructuring parameter moves into a let: the host's method parameters
   are plain symbols."
  [ctx [m params & body]]
  (let [body (rw-body ctx params body)]
    (if (:python-methods? ctx)
      (let [[ps bs] (plain-params params)]
        (if (seq bs)
          (list m ps (apply list (symbol (:core-ns ctx) "let") bs body))
          (apply list m ps body)))
      (apply list m params body))))

(defn- merge-arities
  "The clauses of one JVM interface method implemented in several arities ->
   one variadic member dispatching on the argument count: a Python method
   has one definition."
  [ctx m clauses]
  (if (= 1 (count clauses))
    (first clauses)
    (let [core (fn [n] (symbol (:core-ns ctx) n))
          this (gensym "this__") args (gensym "args__")
          arm (fn [[_ params & body]]
                (let [[self & ps] params]
                  (apply list (core "let")
                         (into [self this]
                               (interleave ps (map-indexed (fn [i _] (list (core "nth") args i)) ps)))
                         body)))
          dispatch (reduce (fn [else c]
                             (list 'if (list (core "=") (list (core "count") args) (dec (count (second c))))
                                   (arm c)
                                   else))
                           (list 'throw (list (core "ex-info") (str "wrong number of arguments to " m) {}))
                           (reverse clauses))]
      (list m [this '& args] dispatch))))

(defn- rw-specs-moved
  "On a :protocols host (ClojureScript) a JVM interface method the host
   implements as a protocol method moves to that protocol, under its name:
   clojure.lang.IDeref deref -> IDeref -deref, Object equals -> IEquiv -equiv,
   Object hashCode -> IHash -hash. The rest stays where it was written, and an
   interface left with no method of its own is dropped."
  [ctx xs]
  (let [hm (:host-method ctx)
        add (fn [groups h x]
              (if-let [i (first (keep-indexed (fn [i [gh]] (when (= gh h) i)) groups))]
                (if (some? x) (update-in groups [i 1] conj x) groups)
                (conj groups [h (if (some? x) [x] [])])))]
    (loop [xs xs, head nil, token nil, groups [], moved #{}]
      (if (empty? xs)
        (mapcat (fn [[h cs]] (if (nil? h) cs (cons h cs)))
                (remove (fn [[h cs]] (and (contains? moved h) (not-any? method-clause? cs))) groups))
        (let [x (first xs)]
          (cond
            (symbol? x)
            (let [h (get *imports* x x)]
              (recur (rest xs) h (when (class-sym? h) (class-token h)) (add groups h nil) moved))
            (method-clause? x)
            (let [r (when (and hm token) (hm token (first x)))
                  [h' m'] (cond (vector? r) r (symbol? r) [head r] :else [head (first x)])
                  c (apply list m' (rest (rw-method-clause ctx x)))]
              (recur (rest xs) head token (add groups h' c) (if (= h' head) moved (conj moved head))))
            :else (recur (rest xs) head token (add groups head (if (vector? x) x (rw ctx x))) moved)))))))

(declare rw-specs-in-place)

(defn- rw-specs
  "`Iface (method [params] body...) ...`: an interface symbol stays (a class
   the namespace imported by its short name is written in full: the host never
   saw the import), a method's body is rewritten, anything else is an ordinary
   form. On a :python host a JVM interface position becomes the host's type,
   its methods take the host's names and an arity-overloaded method becomes
   one variadic member; the root type's (Object's) methods take no head, the
   way the host spells its dunder methods, and come first."
  [ctx xs]
  (if (:move-methods? ctx)
    (rw-specs-moved ctx xs)
    (rw-specs-in-place ctx xs)))

(defn- rw-specs-in-place [ctx xs]
  (let [py? (:python-methods? ctx)
        root (when (and py? *host-type*) (*host-type* 'java.lang.Object))
        finish (fn [token clauses]
                 (if (and py? token (some method-clause? clauses))
                   (let [hm (:host-method ctx)
                         named (map (fn [[m & more]] (apply list (or (when hm (hm token m)) m) more))
                                    (filter method-clause? clauses))]
                     (concat (remove method-clause? clauses)
                             (map (fn [m] (merge-arities ctx m (filter #(= m (first %)) named)))
                                  (distinct (map first named)))))
                   clauses))]
    (loop [xs xs, head nil, token nil, clauses [], groups []]
      (if (empty? xs)
        (let [groups (conj groups [head (finish token clauses)])
              headless? (fn [[h _]] (= ::no-head h))]
          (concat (mapcat second (filter headless? groups))
                  (mapcat (fn [[h cs]] (if (nil? h) cs (cons h cs))) (remove headless? groups))))
        (let [x (first xs)]
          (cond
            (symbol? x)
            (let [h (get *imports* x x)
                  t (when (and py? (class-sym? h)) (class-token h))
                  ht (when (and t *host-type*) (*host-type* t))
                  ht (if (vector? ht) (first ht) ht)
                  h' (cond (and (some? root) (= ht root)) ::no-head
                           ;; a callable is any object with __call__: no interface
                           (and py? (= t 'clojure.lang.IFn)) ::no-head
                           (nil? ht) h
                           :else ht)]
              (recur (rest xs) h' t [] (conj groups [head (finish token clauses)])))
            (method-clause? x) (recur (rest xs) head token (conj clauses (rw-method-clause ctx x)) groups)
            (vector? x) (recur (rest xs) head token (conj clauses x) groups)
            :else (recur (rest xs) head token (conj clauses (rw ctx x)) groups)))))))

(defn- rw-defprotocol
  "(defprotocol P (m [x] [x y] \"doc\") ...). On a :python host the receiver
   of every signature is named this: the host counts a protocol method's
   arity from it, and matches a reify / deftype member by that count."
  [ctx [h nm & more]]
  (apply list h nm
         (map (fn [x]
                (if (and (seq? x) (symbol? (first x)))
                  (apply list (first x)
                         (map (fn [a] (if (and (vector? a) (seq a) (symbol? (first a)))
                                        (assoc a 0 'this)
                                        a))
                              (rest x)))
                  x))
              more)))

(defn- rw-spec-form [ctx form]
  (let [[h & args] form]
    (cond (or (= h 'deftype) (= h 'defrecord))
          (do (when (symbol? (first args)) (swap! (:user-types ctx) conj (first args)))
              ;; the fields are locals in every method body
              (apply list h (first args) (second args)
                     (binding [*locals* (into *locals* (bound-syms (second args)))]
                       (doall (rw-specs ctx (nnext args))))))
          (= h 'proxy)
          (apply list h (mapv #(get *imports* % %) (first args)) (rw ctx (second args)) (rw-specs ctx (nnext args)))
          (and (= h 'defprotocol) (:python-methods? ctx))
          (rw-defprotocol ctx form)
          :else (apply list h (rw-specs ctx args)))))

(def ^:dynamic *inline?*
  "False while the codemod walks the arguments of a macro whose expansion it
   rewrites: a call there is inlined in the expansion, at the arity and among
   the locals it ends up with ((-> 10 (- 1 2)) is (- 10 1 2)), as the JVM
   compiler inlines after macroexpansion; and assert or is see it as written."
  true)

(def ^:dynamic *heads*
  "One rewrite's memo of what head symbols resolve to (nothing is defined while
   a rewrite runs)."
  nil)

(defn- head-var
  "The var the head symbol h names in the namespace being compiled, or nil
   (also for a local, a special form, or a host without the resolver)."
  [ctx h]
  (when-let [f (and (symbol? h) (not (contains? special-heads h)) (not (contains? *locals* h)) (:resolve-head ctx))]
    (let [c *heads*]
      (if-let [e (and c (find @c h))]
        (val e)
        (let [v (f (restock ctx h))]
          (when c (vswap! c assoc h v))
          v)))))

(defn- stock-name
  "The name of the stock core var h names, or nil (a local, or a namespace's
   own var of that name)."
  [ctx h]
  (when-let [v (head-var ctx h)] ((:stock-name ctx) v)))

(defn- rewritten-macro?
  "Does h name a macro whose expansion the codemod rewrites (a stock or user
   macro, not the host's own)?"
  [ctx h]
  (when-let [v (head-var ctx h)] ((:rewritten-macro? ctx) v)))

(defn- inline-expansion
  "On a host whose compiler ignores :inline, the codemod applies it as the JVM
   compiler does: (h args) with h naming a core fn whose :inline covers this
   arity -> that fn's expansion (then rewritten: (+ a b) becomes the shim's
   Numbers-add, or the host's own + where the shim's is that). nil otherwise,
   and for a local named like a core fn."
  [ctx h args]
  (when-let [f (and *inline?* (:inline-fn ctx))]
    (when-let [v (head-var ctx h)]
      (when-let [inl (f v (count args))]
        ((:try-all ctx) (fn [] (apply inl args)))))))

(defn- macro-head
  "The head of a call of a stock macro. Where the host has a special form of
   that name (Phel's fn, let, loop), the special form would win over the
   macro: the head is written qualified, which no special form is."
  [ctx h]
  (if-let [sf (:special-forms ctx)]
    (if (and (symbol? h) (nil? (namespace h)) (contains? (set (vals sf)) h)
             (when-let [v (head-var ctx h)] ((:stock-name ctx) v)))
      (symbol (:core-ns ctx) (name h))
      (rw ctx h))
    (rw ctx h)))

(defn- rw-list [ctx form]
  (if (empty? form)
    form
    (let [h (declass (canon-head (*plain* (first form))))
          args (rest form)
          hn (when (and (symbol? h) (nil? (namespace h))) (name h))
          inst-c (delay (rw ctx (second form)))
          out
          (cond
            (= h 'var) (if (symbol? (second form)) (list 'var (restock ctx (second form))) form)
            (= h 'quote) (if (seq *kw-unrenames*) (list 'quote (unrename-data (second form))) form)
            ;; jolt's reader keeps ` as a marker the analyzer expands in *ns*;
            ;; the template is data — the macro's output gets rewritten instead.
            (= h 'clojure.core/syntax-quote) form
            (= h '.) (rw-dot ctx form)
            ;; (.. x f more...) is expanded here, before its first argument can be
            ;; rewritten as a value: `(.. clojure.lang.Var create setDynamic)`
            ;; names a class, which the stock `..` would otherwise see quoted.
            (= h '..) (let [[x f & more] args
                            inner (list '. x f)]
                        (rw ctx (if (seq more) (apply list '.. inner more) inner)))
            (= h 'new) (let [c (declass (second form))]
                         (if (class-sym? c)
                           (rw-new ctx c (nnext form))
                           (apply list 'new c (rw-all ctx (nnext form)))))
            (= h 'try) (rw-try ctx form)
            (= h 'fn*) (rw-fn* ctx form)
            (or (= h 'let*) (= h 'loop*) (= h 'letfn*)) (rw-let* ctx form)
            (or (= h 'deftype*) (= h 'reify*)) form
            (= h 'def) (rw-def ctx form)
            ;; (instance? a.lib.Proto x): a protocol's interface class, named
            ;; through a library that boots under another name: satisfies?
            (and (symbol? h) (= "instance?" (name h)) (symbol? (second form)) (nil? (namespace (second form)))
                 (protocol-iface ctx (second form)))
            (list (symbol (:core-ns ctx) "satisfies?") (protocol-iface ctx (second form)) (rw ctx (nth form 2)))
            ;; (instance? SomeClass x) naming a class: the shim's test directly, not
            ;; core's instance? -> (. c (isInstance x)) -> a dispatcher -> the test
            (and (symbol? h) (= "instance?" (name h)) (= 3 (count form))
                 (or (nil? (namespace h)) (contains? core-ns-names (namespace h)))
                 (not (contains? *locals* h))
                 ;; the stock instance?, not a namespace's own; and not inside a
                 ;; macro's arguments (clojure.test's is reads instance? there)
                 *inline?*
                 (or (nil? (:resolve-head ctx)) (= 'instance? (stock-name ctx h)))
                 (let [c @inst-c] (and (seq? c) (= 'quote (first c)) (symbol? (second c)))))
            (let [c @inst-c
                  nm (when-let [f (:instance-fn ctx)] (f (second c)))]
              (if nm
                ;; the class's prebuilt test (cohost.shim/install-is!)
                (list (shim-fn nm) (rw ctx (nth form 2)))
                (list (shim-fn "instance-of?") c (rw ctx (nth form 2)))))
            ;; (set! (. Class field) v) / (set! Class/field v) on a settable static
            (and (= h 'set!) (let [t (second form)]
                               (or (and (seq? t) (= '. (first t)) (class-sym? (declass (second t))))
                                   (and (symbol? t) (namespace t) (class-sym? (symbol (namespace t)))))))
            (let [t (second form)
                  [c f] (if (seq? t) [(declass (second t)) (nth t 2)] [(symbol (namespace t)) (symbol (name t))])]
              (list (shim-fn "static-set!") (static-name c f) (rw ctx (nth form 2))))
            (contains? extend-heads (spec-head h))
            (rw-extend ctx (spec-head h) args)
            (spec-head h) (rw-spec-form ctx (cons (spec-head h) args))
            (or (= h 'monitor-enter) (= h 'monitor-exit))
            (apply list (shim-fn hn) (rw-all ctx args))
            ;; (.method target args...) and (.-field target): a field the shim
            ;; does not name stays the host's own property access
            (and hn (starts-with? hn ".") (> (count hn) 1) (not= hn ".."))
            (if (and (starts-with? hn ".-") (> (count hn) 2))
              ;; `(.-x o)` is strictly a field on the JVM: the host's own access
              (apply list h (rw-all ctx args))
              (rw-method ctx (symbol (subs hn 1)) args (fn [xs'] (apply list h xs'))))
            ;; (Class. args...)
            (and hn (ends-with? hn ".") (> (count hn) 1)
                 (class-sym? (symbol (subs hn 0 (dec (count hn))))))
            (rw-new ctx (symbol (subs hn 0 (dec (count hn)))) args)
            ;; (Class/static args...); (Integer/MAX_VALUE) reads the field, as
            ;; (. Integer MAX_VALUE) does
            (and (symbol? h) (namespace h) (class-sym? (symbol (namespace h))))
            (let [c (symbol (namespace h)) m (symbol (name h))]
              (if (and (empty? args) (contains? static-fields m))
                (or (emit-static-value ctx c m) (apply list h args))
                (rw-static ctx c m args)))
            ;; a macro whose expansion the codemod rewrites (a stock or user
            ;; macro, not the host's): its arguments stay as written, and are
            ;; rewritten once, in the expansion, as the JVM compiler sees them
            ;; after macroexpansion: inlining at the arity they end at
            ;; ((-> 10 (- 1 2))), assert and is reading the forms, and the
            ;; locals of &env (typed ones through their markers)
            (rewritten-macro? ctx h)
            (apply list (macro-head ctx h) args)
            ;; a binding form before it expands: what it binds are locals
            (and hn (contains? binding-heads hn))
            (let [names (bound-names hn args)]
              (binding [*locals* (into *locals* names)
                        *local-tags* (hint-tags *local-tags* names)
                        *inline?* (and *inline?* (not (rewritten-macro? ctx h)))]
                (apply list (rw ctx h) (doall (rw-all ctx args)))))
            ;; case: its test constants are read as written by the host's case
            (= hn "case") (rw-case ctx h args)
            :else (if-let [e (inline-expansion ctx h args)]
                    (rw ctx e)
                    ;; a macro whose expansion the codemod rewrites: its
                    ;; arguments without inlining (the expansion inlines them)
                    (if (and *inline?* (rewritten-macro? ctx h))
                      (binding [*inline?* false] (apply list (rw ctx h) (doall (rw-all ctx args))))
                      (apply list (rw ctx h) (rw-all ctx args)))))]
      (carry-meta out form))))

(defn rw [ctx form]
  (cond
    (seq? form) (rw-list ctx form)
    (vector? form) (carry-meta (vec (rw-all ctx form)) form)
    (map? form) (rw-map ctx form)
    (set? form) (carry-meta (into #{} (rw-all ctx form)) form)
    (symbol? form) (rw-symbol ctx form)
    (keyword? form) (unrename-kw form)
    (or (nil? form) (number? form) (string? form) (boolean? form)) form
    ;; a Var object a macro embedded in its expansion (stock defmulti's
    ;; #'global-hierarchy): some host compilers take only (var ns/name)
    (:var-form ctx) (or ((:var-form ctx) form) form)
    :else form))

(defn- kw-unrenames
  "*kw-unrenames* for the renames map as it is: computed again only when a
   library load changed it."
  [ctx]
  (let [rn @(:renames ctx)
        c (:kw-unrenames-cache ctx)
        [k v] (when c @c)]
    (if (and c (identical? k rn))
      v
      (let [v (into {(:core-ns ctx) "clojure.core"} (keep (fn [[o m]] (when (not= o m) [m o]))) (seq rn))]
        (when c (reset! c [rn v]))
        v))))

(defn- host-special-forms
  "form with its fn*, let*, loop* and letfn* named as the host names them (sf:
   the adapter's :special-forms); letfn*'s [name (fn* ...) ...] pairs become
   (name arities) specs. A fn* named like one of those special forms (stock fn,
   booted as (def fn (fn* fn ...))) loses its name, which would shadow the
   special form in its body. Quoted forms stay as they are."
  [sf form]
  (letfn [(walk [x]
            (cond
              (seq? x)
              (let [h (first x)]
                (cond (or (empty? x) (= 'quote h)) x
                      (and (= 'letfn* h) (contains? sf 'letfn*))
                      (let [[_ bs & body] x
                            spec (fn [[n f]]
                                   (let [tail (rest f) tail (if (symbol? (first tail)) (rest tail) tail)]
                                     (apply list n (map walk tail))))]
                        (with-meta (apply list (get sf 'letfn*) (mapv spec (partition 2 bs)) (map walk body)) (meta x)))
                      :else (let [tail (rest x)
                                  tail (if (and (= 'fn* h) (symbol? (first tail)) (contains? (set (vals sf)) (first tail)))
                                         (rest tail)
                                         tail)]
                              (with-meta (apply list (map walk (cons (get sf h h) tail))) (meta x)))))
              (vector? x) (with-meta (mapv walk x) (meta x))
              (map? x) (with-meta (into {} (map (fn [[k v]] [(walk k) (walk v)]) (seq x))) (meta x))
              (set? x) (with-meta (into #{} (map walk x)) (meta x))
              :else x))]
    (walk form)))

(defn rewrite
  "The codemod: form -> form the host can compile against cohost.shim.
   opts :locals — the local names in scope (a macro expansion's, from its
   &env)."
  ([ctx form] (rewrite ctx form nil))
  ([ctx form {:keys [locals]}]
   (let [cur (when-let [f (:current-ns ctx)] (str (f)))
         types (env-types locals)]
     (binding [*plain* (:plain-symbol ctx identity)
               *locals* (into #{} locals)
               *local-tags* (:tags types)
               *local-classes* (:classes types)
               *marker-depth* (:depth types)
               *inline?* true
               *heads* (volatile! {})
               *strip-inline?* (boolean (:strip-inline? ctx))
               *current-ns* cur
               *imports* (get @(:imports ctx) cur {})
               *ns-renames* @(:renames ctx)
               *loader-nses* @(:loader-nses ctx)
               *per-arity-impls* (:per-arity-impls? ctx)
               *kw-unrenames* (kw-unrenames ctx)
               *host-type* (:host-type ctx)]
       (let [out (if-let [ref (:shim-ref ctx)]
                   (binding [*shim-ref* ref] (rw ctx form))
                   (rw ctx form))]
         (if-let [sf (:special-forms ctx)] (host-special-forms sf out) out))))))

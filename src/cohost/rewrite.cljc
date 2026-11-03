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
(ns cohost.rewrite
  (:require [cohost.adapter :as host]
            [cohost.policy :as policy])
  ;; names defined here that let-go's core also has (it warns of each)
  #?(:lg (:refer-clojure :exclude [starts-with? ends-with?])))

(def ^:dynamic *native-ints*
  "Loop locals the codemod started from an integer literal through the adapter's
   :loop-int-init (A5): native longs on jank. A + - * whose every operand is one
   of them, an integer literal, or such an arithmetic form is emitted as the
   host's own op (:native-int-aliases), which is exact on two native longs; any
   other operand goes through the shim's checked Numbers-*. Rebinding a name
   (let*, loop*, letfn*, fn* params) drops it."
  #{})

(def ^:dynamic *plain*
  "A symbol taken from a form, bare: the adapter's :plain-symbol (a let-go
   symbol carrying metadata is a wrapper no symbol equals, with no name or
   namespace of its own); identity elsewhere."
  identity)

;; Dispatch-branch timers used to wrap every rewrite call. The macro was
;; defined in this namespace, which self-hosted ClojureScript does not expand,
;; so each call returned nil and the battery was empty. The wrappers are gone.
;; The atoms stay so a profile run can still print the (now empty) shares.
(def profile-branches (volatile! nil))
(def profile-child (volatile! 0))

;; macro name -> expand ns, and macro name -> call count. Profiling on only.
(def profile-macro-ns (volatile! nil))
(def profile-macro-n (volatile! nil))

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
  (let [sym (*plain* sym)]
  (or (get @simple-class-names sym)
      (let [s (name sym)
            i (last-dot s)
            s (if (neg? i) s (subs s (inc i)))
            r (apply str (map (fn [c] (if (= "$" (str c)) "_" (str c))) s))]
        (swap! simple-class-names assoc sym r)
        r))))

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
(def ^:dynamic *locals*
  "The names the enclosing forms bind (fn and let params, catch bindings, the
   binding vectors of let / loop / for / doseq ... before they expand): a
   local spelled like a class is a local."
  #{})
(def ^:dynamic *num-env*
  "{local long|double} of the enclosing locals whose numeric type is certain
   (U6-7, :static-types): a primitive-hinted fn* parameter (coerced on entry),
   a hinted let/loop local, and a local inferred from its init."
  {})
(def ^:dynamic *def-name*
  "The plain name of the var whose def init is being rewritten (U6-7): a fn
   calling it calls itself."
  nil)
(def ^:dynamic *loop-locals*
  "Names bound by the loop* forms enclosing the form being rewritten (only
   tracked when the host sets :loop-rebind-shadow?)."
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
    (symbol (str marker-prefix i "_" d "__" (name (*plain* local))))))

(defn- parse-nat [s]
  (when (seq s)
    (loop [k 0 acc 0]
      (if (>= k (count s)) acc
          (let [v (get {"0" 0 "1" 1 "2" 2 "3" 3 "4" 4 "5" 5 "6" 6 "7" 7 "8" 8 "9" 9} (subs s k (inc k)))]
            (when v (recur (inc k) (+ (* 10 acc) v))))))))

(defn- marker-info
  "[local token-or-nil depth] of a marker symbol, else nil."
  [sym]
  (let [n (name (*plain* sym)) p (count marker-prefix)]
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
  (if (seq bs) [(apply list 'let* bs (or (seq body) [nil]))] body))

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
  (let [x (*plain* x)]
    (and (symbol? x) (nil? (namespace x))
         (or (contains? java-lang-names x)
             (contains? *imports* x)
             (class-prefixed? (name x))))))

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
  (let [x (*plain* x)]
  (if (and (symbol? x) (namespace x)
           (or (contains? core-ns-names (namespace x)) (= *current-ns* (namespace x))
               (contains? *loader-nses* (namespace x))))
    (let [n (name x)
          base (if (and (> (count n) 1) (ends-with? n ".")) (subs n 0 (dec (count n))) n)]
      (if (class-sym? (symbol base)) (symbol n) x))
    x)))

(def special-heads
  (set '[quote var . new try catch finally fn* let* loop* letfn* def do if throw
     recur set! monitor-enter monitor-exit deftype* reify* case* import*]))

(defn canon-head
  "basilisp.core/new -> new: some readers namespace-qualify special forms
   inside syntax-quote."
  [h]
  (let [h (*plain* h)]
    (if (and (symbol? h) (namespace h) (contains? core-ns-names (namespace h))
             (contains? special-heads (symbol (name h))))
      (symbol (name h))
      h)))

(def reader-plumbing
  "clojure.core/ names a reader emits for `, ~@, @ and #': host plumbing that
   stays the host's."
  (set '[deref seq concat list vector hash-map hash-set with-meta apply unquote
     unquote-splicing syntax-quote import* in-ns refer alias load name]))

(defn restock
  "Explicit clojure.core/x references name the booted core; explicit
   references to a stock library booted under another name name that."
  [ctx x]
  (let [host? (:cohost/host (meta x)) x (*plain* x)]
  (if-let [ns (and (symbol? x) (namespace x) (not host?) (namespace x))]
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
    x)))

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

(defn static-sym [c m] (*shim-ref* (str (simple-class-name c) "-" (name (*plain* m)))))
(defn ctor-sym   [c]   (*shim-ref* (str "new-" (simple-class-name c))))
(defn method-sym [m]   (*shim-ref* (str "-" (name (*plain* m)))))
(defn- shim-fn   [nm]  (*shim-ref* nm))

(defn make-ctx
  "shim-names: the set of (unqualified) symbols interned in cohost.shim.
   catch-all: the host symbol a `catch` clause may name to catch everything.
   passthrough?: leave interop the shim lacks for the host to try (jolt has a
   wide java.* shim of its own); false means it becomes a `cohost.shim/missing-*`
   call that throws (and is reported) when reached."
  [{:keys [shim-names catch-all passthrough? core-ns shim-ref loop-variadic-recur? loop-int-init native-int-aliases def-meta strip-inline?
           current-ns imports renames host-type var-form per-arity-impls? python-methods? move-methods? cohost-types? host-method
           record? protocol-var? interned? host-aliases inline-fn resolve-head stock-name rewritten-macro?
           instance-fn missing-dot try-all named-fn*? special-forms binding-style vector-rest-args? plain-symbol expand-macro syntax-quote-source? loop-rebind-shadow?]
    :or {shim-names #{} catch-all 'Exception passthrough? false core-ns "cohost.core"}}]
  {:plain-symbol (or plain-symbol identity)
   :shim-names shim-names
   ;; {shim static name -> the host core's symbol} where the shim's static is
   ;; the host's own fn: emitted instead, so the host's compiler sees (and may
   ;; inline) its own +, first, get ...
   :host-aliases (merge (or host-aliases {})
                        ;; the adapter's own (wrappers whose semantics are the
                        ;; host op's there), marked as the scan marks its
                        (into {} (map (fn [[nm sym]] [nm (with-meta sym {:cohost/host true})]))
                              (host/static-aliases)))
   ;; {stock name -> host symbol or false}: core-alias's cache
   :core-aliases (atom {})
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
   ;; expand-macro: nil, or (fn [var form locals]) -> the expansion of a call
   ;; of the macro var holds, as it wrote it, given an &env of the locals (the
   ;; form itself when var holds none): where the host's compiler hands a
   ;; macro no &env (let-go), the codemod expands it in place
   :expand-macro expand-macro
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
   ;; :with-bindings where the host's bindings are scoped only: push/pop
   ;; pairs become cohost.shim/with-thread-bindings calls (scoped-bindings)
   :scoped-bindings? (= :with-bindings binding-style)
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
   ;; a host whose types are cohost's own (:cohost-types): a (.-f x) field
   ;; read is a member call the shim dispatches, not the host's own access
   :cohost-types? (boolean cohost-types?)
   :syntax-quote-source? (boolean syntax-quote-source?)
   :loop-rebind-shadow? (boolean loop-rebind-shadow?)
   :host-method host-method
   ;; record?: is a form a record instance (a reader literal); protocol-var?:
   ;; (fn [ns-sym name-sym]) is that var a protocol; interned?: (fn [sym]) is
   ;; sym a var of the namespace being compiled -- such a name is never a class
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
   ;; nil, or the adapter's :loop-int-init: a loop* binding started from an
   ;; integer literal starts from (f literal) instead (A5: jank on apt types such a
   ;; local as a C++ int, and recur wraps it at 32 bits)
   :loop-int-init loop-int-init
   ;; nil, or {"Numbers-add" host-+ ...}: where the operands are native longs
   :native-int-aliases native-int-aliases
   :strip-inline? (boolean strip-inline?)
   ;; metadata every `def` name gets on this host (basilisp: {:redef true}, so
   ;; alter-var-root and with-redefs reach compiled references)
   :def-meta def-meta
   :misses (atom {})})

(defn- known?
  "Is the shim var (named by its unqualified name) present?"
  [ctx nm] (contains? (:shim-names ctx) (symbol nm)))

(defn- static-name [c m] (str (simple-class-name c) "-" (name (*plain* m))))
(defn- ctor-name [c] (str "new-" (simple-class-name c)))
(defn- method-name [m] (str "-" (name (*plain* m))))

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
    ;; (jank's reader keeps ^Class as :tag (quote Class))
    (let [m (if (let [t (:tag m)] (or (symbol? t) (string? t) (and (seq? t) (= 'quote (first t)) (symbol? (second t)))))
              (dissoc m :tag)
              m)
          m (dissoc m :rettag ::inferred)
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
      ;; identical?, not =: = is metadata-blind, so a strip whose only
      ;; change is metadata inside a meta value (an :arglists param's
      ;; :tag) compares equal to the original and would be dropped --
      ;; the JVM compiler then reads ^long in :arglists as a primitive
      ;; interface the coerced fn does not have.
      (cond (identical? m (meta out)) out
            ;; left none: bare (a let-go symbol given any, even none, is a
            ;; wrapper no symbol equals)
            (nil? m) (*plain* (with-meta out {}))
            :else (with-meta out m)))
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

(defn- native-int-form?
  "A source form that is a native long under *native-ints*: one of them, an
   integer literal, or a stock + - * inc dec of such forms."
  [x]
  (cond (integer? x) true
        (symbol? x) (contains? *native-ints* (*plain* x))
        (seq? x) (let [h (first x)]
                   (and (symbol? h)
                        (not (contains? *locals* (*plain* h)))
                        (contains? #{nil "clojure.core" "cohost.core"} (namespace h))
                        (contains? #{"+" "-" "*" "inc" "dec"} (name h))
                        (seq (rest x))
                        (every? native-int-form? (rest x))))
        :else false))

(defn- rw-static [ctx c m args]
  (let [args' (rw-all ctx args)
        nm (static-name c m)]
    (cond (and (seq *native-ints*) (get (:native-int-aliases ctx) nm) (seq args) (every? native-int-form? args))
          (apply list (get (:native-int-aliases ctx) nm) args')
          (known? ctx nm) (or ;; U6-8: the adapter's own expansion of a Math static at
                              ;; the call head (nil: keep the shim call)
                              (when-let [mf (get (host/math-aliases) nm)] (mf args'))
                              ;; U6-7: the RT collection statics on the host's own vector
                              (when-let [vf (get (host/vector-aliases) nm)] (vf args'))
                              (apply list (or (get (:host-aliases ctx) nm) (static-sym c m)) args'))
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
        (seq? x) (let [h (*plain* (first x))]
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
  ;; xs: the target and the arguments as written -- none at all in a
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
        m (*plain* m)
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
                    (and (= c (:catch-all ctx)) (symbol? b) (starts-with? (name (*plain* b)) "e__stock"))))
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
                                    ;; (an empty catch body is nil: ClojureWasm's let* takes no empty one)
                                    (apply list 'let* [b e] (or (seq (binding [*locals* (conj *locals* b)] (doall (rw-all ctx cb)))) [nil]))]))
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

(def ^:private primitive-hints #{'long 'double 'int 'boolean})

(defn- primitive-hint
  "long, double, int, or boolean when tag is that fn* primitive hint.
   The JVM reader stores ^long as the symbol long; jank stores (quote long)."
  [tag]
  (let [n (cond
            (symbol? tag) tag
            (and (seq? tag) (= 'quote (first tag)) (symbol? (second tag)) (nil? (next (next tag))))
            (second tag)
            :else nil)]
    (when (and (symbol? n) (nil? (namespace n)) (contains? primitive-hints n)) n)))

(defn- branch?
  "A form to walk into: a collection, or any seq. jank's coll? is false for a
   lazy seq (the JVM's is true), and the rewritten bodies are lazy seqs (map),
   so a walk that tested coll? alone stopped at them."
  [x]
  (or (coll? x) (seq? x)))

(defn- mentions-sym?
  "True when sym occurs anywhere in form (lazy seqs included; see branch?)."
  [sym form]
  (or (= sym form)
      (and (branch? form) (boolean (some #(mentions-sym? sym %) (seq form))))))

(defn- arity-loops?
  "True when this arity's body has loop, loop*, or recur outside a nested fn.
   A quoted form and a nested fn/fn* keep their own recur targets."
  [body]
  (letfn [(hit? [x]
            (cond
              (not (branch? x)) false
              (seq? x)
              (let [h (first x)
                    n (when (symbol? h) (symbol (name (*plain* h))))]
                (cond
                  (or (= 'quote h) (= 'clojure.core/syntax-quote h) (= 'fn* n) (= 'fn n)) false
                  (or (= 'loop n) (= 'loop* n) (= 'recur n)) true
                  :else (boolean (some hit? x))))
              :else (boolean (some hit? x))))]
    (boolean (some hit? body))))

(defn- kind-test
  "The form testing whether sym is already of the hinted kind (long or
   double): the host's own test (:prim-kind-test, inlined by jank), else the
   shim's."
  [hint sym]
  (or (when-let [pkt (host/prim-kind-test)] (pkt hint sym))
      (list (shim-fn (if (= 'double hint) "double-kind?" "long-kind?")) sym)))

(defn- coerce-form
  "The JVM's coercion of a primitive fn* parameter, in front of any native
   conversion. jank's long throws on a real and its double throws on an
   integer, and either throws a native error on a non-number that no catch
   of an object can see: a non-number becomes the exception the JVM raises."
  [hint sym]
  (if (= 'boolean hint)
    (list 'boolean sym)
    (let [method (if (= 'double hint) "doubleValue" (if (= 'int hint) "intValue" "longValue"))
          convert (list 'if (list 'number? sym)
                        (list hint sym)
                        (list 'if (list 'nil? sym)
                              (list 'throw (list (shim-fn "new-NullPointerException")
                                                 (str "Cannot invoke \"java.lang.Number." method "()\" because the argument is null")))
                              (list 'throw (list (shim-fn "new-ClassCastException")
                                                 "class java.lang.Object cannot be cast to class java.lang.Number"))))]
      ;; a value already of the hinted kind passes on the host's own kind
      ;; test (the shim's, bound once to the host core's integer? /
      ;; double?), with no stock-core conversion call in front of every
      ;; call: hinted code must not be slower than unhinted (Step 5 H1)
      (if (= 'int hint)
        convert
        (list 'if (kind-test hint sym)
              sym
              convert)))))

(defn- fn-special?
  "True when this host's `fn` is a special form the stock macro cannot
   replace (policy host-overrides :special). cljrs is that host. Every other
   host leaves `fn` for the stock macro, which is what keeps :pre/:post and
   a destructured parameter."
  []
  (boolean (when (host/installed?)
             (let [special (:special (get policy/host-overrides (host/host-name)))]
               (and special (contains? special 'fn))))))

(defn- fn-form-arities
  "The arities of an `fn` form: ([params] body...) or ([params] body...)+
   after an optional name."
  [form]
  (let [more (rest form)
        more (if (symbol? (first more)) (rest more) more)]
    (if (vector? (first more)) [more] more)))

(defn- fn-form-coercible?
  "Safe to rewrite this `fn` as `fn*` on a host whose `fn` is a special form:
   a primitive hint is present, every parameter of every arity is a plain
   symbol, and no arity opens with a condition map (the same test the stock
   `fn` macro uses: a map followed by more body). A destructuring parameter
   or a :pre/:post map stays as written."
  [form]
  (let [arities (fn-form-arities form)]
    (and (seq arities)
         (every? (fn [[params & body]]
                   (and (vector? params)
                        (every? symbol? params)
                        (not (and (next body) (map? (first body))))))
                 arities)
         (boolean (some (fn [[params]]
                          (some #(and (symbol? %) (primitive-hint (:tag (meta %)))) params))
                        arities)))))

(defn- bare-sym
  "The parameter's name with no metadata. The hint stays on the parameter
   only; a let binding or a call argument that still carried it would be a
   hinted local, which jank compiles as a native conversion."
  [p]
  (let [s (*plain* p)]
    (if (meta s) (with-meta s nil) s)))

(defn- fn-level-recur?
  "True when `body` has a recur whose target is the arity itself: one outside
   any loop, loop*, fn or fn* (and outside a quoted form)."
  [body]
  (letfn [(hit? [x]
            (cond
              (not (branch? x)) false
              (seq? x)
              (let [h (first x)
                    n (when (symbol? h) (symbol (name (*plain* h))))]
                (cond
                  (or (= 'quote h) (= 'clojure.core/syntax-quote h)
                      (= 'fn* n) (= 'fn n) (= 'loop n) (= 'loop* n)) false
                  (= 'recur n) true
                  :else (boolean (some hit? x))))
              :else (boolean (some hit? x))))]
    (boolean (some hit? body))))

(defn- return-hint
  "The primitive return hint of one arity: its params vector's tag (^long [x]),
   else the fn's (the fn form's :rettag, which defn sets from the name's tag on
   the JVM and on jank, or the name's own tag)."
  [fn-hint params]
  (or (primitive-hint (:tag (meta params))) fn-hint))

(declare infer-num loop-recurs core-op infer-here?)

(def ^:private numeric-ops
  #{"+" "-" "*" "/" "inc" "dec" "<" ">" "<=" ">=" "==" "zero?" "pos?" "neg?" "quot" "rem" "mod" "max" "min"})

(defn- numeric-params
  "U6-7: {param type} of the unhinted plain parameters the raw body uses as a
   direct operand of a numeric core fn (outside nested fns and quoted forms):
   double when, in one such call, a sibling operand is a double (a literal, or
   a form or local certainly double: let/loop locals are followed in order,
   the params themselves unknown), else long. A guess only: the arity runs it
   under a guard on the arguments' kinds (guarded-arity)."
  [params body]
  (let [ps (set (keep #(when (and (symbol? %) (not= '& (*plain* %)) (nil? (primitive-hint (:tag (meta %)))))
                         (*plain* (bare-sym %)))
                      params))
        kinds (volatile! {})
        env0 (apply dissoc *num-env* ps)]
    (letfn [(walk [env x]
              (cond
                (not (branch? x)) nil
                (seq? x)
                (let [h (first x) n (when (symbol? h) (name (*plain* h)))]
                  (cond
                    (contains? #{"quote" "fn" "fn*" "letfn*"} n) nil
                    (and (contains? #{"let" "let*" "loop" "loop*"} n) (vector? (second x)))
                    (let [env' (reduce (fn [e [b v]]
                                         (walk e v)
                                         (if (symbol? b)
                                           (let [sym (*plain* (bare-sym b))
                                                 t (binding [*locals* (conj *locals* sym)] (infer-num e v))]
                                             (if t (assoc e sym t) (dissoc e sym)))
                                           e))
                                       env (partition 2 (second x)))]
                      (run! #(walk env' %) (drop 2 x)))
                    :else
                    (do
                      (when (contains? numeric-ops (core-op h))
                        (let [args (vec (rest x))
                              ts (mapv #(infer-num env %) args)]
                          (doseq [[i a] (map-indexed vector args)]
                            (when (and (symbol? a) (contains? ps (*plain* a)))
                              (let [dbl (some (fn [j] (and (not= i j) (= 'double (nth ts j)))) (range (count args)))]
                                (vswap! kinds update (*plain* a) #(if (or dbl (= % 'double)) 'double 'long)))))))
                      (run! #(walk env %) x))))
                :else (run! #(walk env %) (seq x))))]
      (run! #(walk env0 %) body)
      @kinds)))

(defn- emit-arity
  "One fn* arity. A primitive hint is coerced on every host, so the value
   matches the JVM: a parameter's on entry, a return's on exit (the JVM's
   longCast / doubleCast; with a recur to the arity itself the body runs in a
   loop* so the coercion stays outside the recur target).
   When the host compiles native types (:native-hints), every parameter is a
   plain symbol and the arity loops or calls itself, a hinted arity hands the
   coerced values to one inner fn* whose parameters carry the native tags; the
   outer parameters stay untyped, so the
   host never converts an argument it has not coerced. A single-arity fn named
   `nm` names the inner fn* the same, so its self-calls are the host's native
   named recursion; the return hint becomes the inner fn*'s :rettag."
  ([params body'] (emit-arity nil false nil params body' nil))
  ([nm single? ret-hint params body'] (emit-arity nm single? ret-hint params body' nil))
  ([nm single? ret-hint params body' typed]
   (let [hints (mapv (fn [p] (when (symbol? p) (primitive-hint (:tag (meta p))))) params)]
     (if-not (or (some some? hints) ret-hint)
       (if-let [[ptypes tbody] typed]
           ;; U6-7: the params guessed numeric, under a guard on their kinds:
           ;; the typed body binds them through the host's native conversion
           ;; (its locals infer from them), anything else runs the body as is
           (let [casts (host/native-local-casts)
                 guard (apply list 'and (map (fn [[p t]] (kind-test t p)) ptypes))
                 binds (vec (mapcat (fn [[p t]] [p (list (get casts t) p)]) ptypes))]
             (apply list (rw-params params)
                    [(list 'if guard
                           (apply list 'let* binds tbody)
                           (if (next body') (apply list 'do body') (first body')))]))
           (apply list (rw-params params) body'))
       (let [body' (or (seq body') '(nil))
             binds (into [] (mapcat (fn [p h]
                                      (when h (let [s (bare-sym p)] [s (coerce-form h s)])))
                                    params hints))
             spells (host/native-hints)
             names (into [] (comp (filter symbol?) (map bare-sym) (remove #(= '& %))) params)
             ;; H0 (apt 0.1-noble, U6-5): a hinted leaf called from elsewhere
             ;; loses (call-long 1.39-1.42x, call-double 1.24-1.26x: the
             ;; typed entry converts), and under cohost the inner fn* per
             ;; call costs more (2e6 calls of a hinted leaf on the series:
             ;; 415 ms through it, 75 ms without); a loop over the hinted
             ;; value wins (0.12-0.13x), and so does a fn that calls itself,
             ;; whose self-calls stay inside the typed inner fn* (fib 32 in
             ;; this shape 24-25 ms vs 27-28 on apt; 9 vs 25 on the series)
             self-calls? (and single? nm (mentions-sym? (bare-sym nm) body'))
             native? (and spells
                          (or (arity-loops? body') self-calls?)
                          (every? #(and (symbol? %) (not= '& (*plain* %))) params)
                          (some (fn [h] (when h (get spells h))) hints))
             forms (if native?
                     (let [ips (mapv (fn [p h]
                                       (if-let [sp (and h (get spells h))]
                                         (with-meta (bare-sym p) {:tag (list 'quote sp)})
                                         (carry-meta p p)))
                                     params hints)
                           rsp (when ret-hint (get spells ret-hint))
                           inner (apply list 'fn* (concat (when (and single? nm) [(bare-sym nm)])
                                                          [(cons (carry-meta ips params) body')]))
                           inner (if rsp (with-meta inner {:rettag (list 'quote rsp)}) inner)]
                       [(apply list inner (map bare-sym params))])
                     body')
             forms (if-not ret-hint
                     forms
                     (let [r (gensym "ret__")
                           v (if (and (not native?) (fn-level-recur? forms))
                               (apply list 'loop* (vec (mapcat (fn [n] [n n]) names)) forms)
                               (if (next forms) (apply list 'do forms) (first forms)))]
                       [(list 'let* [r v] (coerce-form ret-hint r))]))]
         (apply list (rw-params params) (if (seq binds) [(apply list 'let* binds forms)] forms)))))))

(defn- rw-fn* [ctx form]
  (let [more (rest form)
        [nm more] (if (symbol? (first more)) [(carry-meta (first more) (first more)) (rest more)] [nil more])
        single? (vector? (first more))
        arities (if single? [more] more)
        ;; the fn's return hint: defn's :rettag on the form (the name's tag,
        ;; JVM and jank alike), or the name's own tag
        fn-ret (or (primitive-hint (:rettag (meta form)))
                   (when (symbol? (second form)) (primitive-hint (:tag (meta (second form))))))
        one-arity? (= 1 (count arities))
        names (into (if nm #{nm} #{})
                    (mapcat (fn [[params]] (filter #(and (symbol? %) (not= '& %)) params)) arities))
        arities' (binding [*locals* (into *locals* names)
                           *native-ints* (apply disj *native-ints* (map *plain* names))]
                   (doall (map (fn [[params & body]]
                                 (let [ps0 (filter symbol? params)
                                       ps (map *plain* ps0)
                                       tags (hint-tags *local-tags* ps0)
                                       classes (class-tags *local-classes* (map (fn [p] [p nil]) ps))
                                       [bs depths] (markers-for ps tags classes)
                                       ;; U6-7: the params shadow outer typed locals; a
                                       ;; ^long / ^double one is that type (coerced on entry)
                                       fenv (reduce (fn [e p]
                                                      (let [h (primitive-hint (:tag (meta p)))
                                                            e (dissoc e (*plain* (bare-sym p)))]
                                                        (if (#{'long 'double} h) (assoc e (*plain* (bare-sym p)) h) e)))
                                                    (if nm (dissoc *num-env* (*plain* (bare-sym nm))) *num-env*)
                                                    ps0)
                                       body' (binding [*local-tags* tags *local-classes* classes *marker-depth* depths
                                                       *num-env* fenv]
                                               (doall (rw-all ctx body)))
                                       ;; U6-7: a second rewrite with the numeric params typed,
                                       ;; for a guarded arity (not when a hint or a cast is missing)
                                       casts (host/native-local-casts)
                                       ;; not for a fn that calls itself (its name or its
                                       ;; def's var): jank 0.1 binds a var self-call with a
                                       ;; native argument to the fn being defined, so a
                                       ;; redefinition mid-recursion is missed (battery:
                                       ;; "redefining a fn during its own recursion")
                                       ptypes (when (and (infer-here?) (host/native-hints) casts
                                                         (not (some #(and % (mentions-sym? % body))
                                                                    [(when nm (*plain* (bare-sym nm))) *def-name*]))
                                                         (not (some #(= '& (*plain* %)) ps0))
                                                         (not (some #(primitive-hint (:tag (meta %))) ps0)))
                                                (not-empty (into {} (filter (fn [[_ t]] (get casts t)))
                                                                 (numeric-params params body))))
                                       tbody (when ptypes
                                               (binding [*local-tags* tags *local-classes* classes *marker-depth* depths
                                                         *num-env* (merge fenv ptypes)]
                                                 (doall (rw-all ctx body))))
                                       body' (with-markers bs body')
                                       body' (if (:loop-variadic-recur? ctx) (loop-variadic-recur params body') body')
                                       rest-arg (second (drop-while #(not= '& %) params))
                                       body' (if (and (:vector-rest-args? ctx) (symbol? rest-arg))
                                               [(apply list 'let* [rest-arg (list (shim-fn "RT-seq") rest-arg)] body')]
                                               body')]
                                   (emit-arity nm one-arity? (return-hint fn-ret params) params body'
                                               (when (and ptypes (empty? bs) (not (:loop-variadic-recur? ctx)))
                                                 [ptypes tbody]))))
                               arities)))]
    (if (and nm (not (:named-fn*? ctx)))
      (list 'letfn* [nm (apply list (carry-meta 'fn* (first form)) (if single? (first arities') arities'))] nm)
      (apply list (carry-meta 'fn* (first form)) (concat (when nm [nm]) (if single? (first arities') arities'))))))

(defn- rw-bindings [ctx bvec]
  (carry-meta (vec (mapcat (fn [[b v]] [(if (symbol? b) (carry-meta b b) (rw ctx b)) (rw ctx v)])
                           (partition 2 bvec)))
              bvec))

(defn- loop-rebind
  "(loop* [a init ...] body) where an enclosing loop* binds a ->
   (loop* [a__N init ...] (let* [a a__N] body)): jank 0.1's C++ codegen fails
   on the shadowing loop* (`use of undeclared identifier 'sNN'`) and compiles
   the let* one. recur is positional, so the loop is the same. nil when no
   binding shadows."
  [bvec body]
  (let [pairs (partition 2 bvec)
        hit (fn [b] (and (symbol? b) (contains? *loop-locals* (*plain* b))))]
    (when (some (comp hit first) pairs)
      (let [fresh (map (fn [[b _]] (when (hit b) (with-meta (gensym (str (name (*plain* b)) "__")) (meta b)))) pairs)]
        (list 'loop*
              (vec (mapcat (fn [[b v] g] [(or g b) v]) pairs fresh))
              (apply list 'let*
                     (vec (mapcat (fn [[b _] g] (when g [(*plain* b) g])) pairs fresh))
                     (or (seq body) [nil])))))))

(declare rw-let*')
(defn- hinted-local-init
  "A ^long / ^double / ^int / ^boolean let*/loop* local's init: the JVM's
   coercion of the bound value (coerce-form), through the host's native
   conversion when it has one (:native-local-casts), so the host types the
   local natively. An inferred local (U6-7) holds that type already: the
   conversion only, no coercion."
  ([hint sym] (hinted-local-init hint sym false))
  ([hint sym inferred?]
   (let [c (if inferred? sym (coerce-form hint sym))]
     (if-let [cast (get (host/native-local-casts) hint)] (list cast c) c))))

;; ---------------------------------------------------------------------------
;; U6-7: static numeric types (adapter :static-types). Sound only: a local is
;; typed when its value's type is certain from the source, so emitting the
;; host's native conversion changes nothing Clojure would compute.

(defn- infer-here?
  "U6-7: static types are inferred in user code only: not in the stock core
   cohost boots (cohost.core) nor in a clojure.* library it loads, whose
   behavior the batteries pin and whose fns would all grow a guarded twin."
  []
  (and (host/static-types)
       (let [cur (str *current-ns*)]
         ;; cohost.user, where cohost evaluates a program, is user code
         (or (= cur "cohost.user")
             (not (or (starts-with? cur "cohost.") (starts-with? cur "clojure.")))))))

(defn- core-op
  "The name of a clojure.core fn this call head names (unqualified or
   clojure.core/), unless a local shadows it."
  [h]
  (when (symbol? h)
    (let [h (*plain* h)]
      (when (and (contains? #{nil "clojure.core"} (namespace h))
                 (not (and (nil? (namespace h)) (contains? *locals* h))))
        (name h)))))

(defn- infer-num
  "long, double, or nil: x's numeric type when it is certain under env
   ({local type})."
  [env x]
  (cond
    (int? x) 'long
    (double? x) 'double
    (symbol? x) (when (nil? (namespace x)) (get env (*plain* x)))
    (seq? x)
    (let [op (core-op (first x))
          args (rest x)
          ts (map #(infer-num env %) args)]
      (case op
        ("+" "-" "*") (when (and (seq args) (every? some? ts))
                        (if (some #{'double} ts) 'double 'long))
        ("inc" "dec") (when (= 1 (count args)) (first ts))
        "/" (when (and (seq args) (every? #{'double} ts)) 'double)
        "count" (when (= 1 (count args)) 'long)
        "if" (let [[_ a b] args
                   ta (infer-num env a) tb (when (some? b) (infer-num env b))]
               (when (and ta (= ta tb)) ta))
        "do" (when (seq args) (infer-num env (last args)))
nil))
    :else nil))

(defn- loop-recurs
  "The argument lists of the recurs whose target is this loop: outside any
   nested loop, fn or quoted form."
  [body]
  (letfn [(walk [x]
            (cond
              (not (branch? x)) []
              (seq? x)
              (let [h (first x) n (when (symbol? h) (name (*plain* h)))]
                (cond
                  (contains? #{"quote" "loop" "loop*" "fn" "fn*" "letfn*"} n) []
                  (= "recur" n) [(vec (rest x))]
                  :else (vec (mapcat walk x))))
              :else (vec (mapcat walk (seq x)))))]
    (vec (mapcat walk body))))

(defn- recur-sites
  "[env args] for each recur whose target is this loop (as loop-recurs), env
   being env extended by the let* bindings between the loop and the recur
   (each inferred in order; a binding shadows)."
  [env body]
  (letfn [(walk [env x]
            (cond
              (not (branch? x)) []
              (seq? x)
              (let [h (first x) n (when (symbol? h) (name (*plain* h)))]
                (cond
                  (contains? #{"quote" "loop" "loop*" "fn" "fn*" "letfn*"} n) []
                  (= "recur" n) [[env (vec (rest x))]]
                  (and (= "let*" n) (vector? (second x)))
                  (let [[env' acc] (reduce (fn [[e acc] [b v]]
                                             (let [acc (into acc (walk e v))]
                                               (if (symbol? b)
                                                 (let [sym (*plain* (bare-sym b))
                                                       t (binding [*locals* (conj *locals* sym)] (infer-num e v))]
                                                   [(if t (assoc e sym t) (dissoc e sym)) acc])
                                                 [e acc])))
                                           [env []] (partition 2 (second x)))]
                    (into acc (mapcat #(walk env' %) (drop 2 x))))
                  :else (vec (mapcat #(walk env %) x))))
              :else (vec (mapcat #(walk env %) (seq x)))))]
    (vec (mapcat #(walk env %) body))))

(defn- infer-bindings
  "[types env']: for a let*/loop*'s [sym init ...], each unhinted symbol
   binding's certain type (nil when not), and the env its body sees. loop*:
   a local stays typed only while every recur argument in its position has
   the same type (iterated to a fixpoint)."
  [h bvec body env]
  (let [pairs (partition 2 bvec)
        step (fn [types]
               (loop [ps pairs ts types i 0 env env acc []]
                 (if-let [[b v] (first ps)]
                   (let [sym (when (symbol? b) (*plain* (bare-sym b)))
                         hint (when (symbol? b) (primitive-hint (:tag (meta b))))
                         ;; the names bound before this one shadow core fns in its init
                         t (cond (#{'long 'double} hint) hint
                                 hint nil
                                 sym (when (or (nil? types) (nth types i))
                                       (binding [*locals* (into *locals* (keep (fn [[b _]] (when (symbol? b) (*plain* (bare-sym b))))
                                                                               (take i pairs)))]
                                         (infer-num env v))))
                         env (if sym (if t (assoc env sym t) (dissoc env sym)) env)]
                     (recur (rest ps) ts (inc i) env (conj acc t)))
                   [acc env])))
        [ts env'] (step nil)]
    (if (= h 'loop*)
      (do
        (loop [ts ts env' env']
          (let [names (keep (fn [[b _]] (when (symbol? b) (*plain* (bare-sym b)))) pairs)
                rs (binding [*locals* (into *locals* names)] (recur-sites env' body))
                ts' (binding [*locals* (into *locals* names)]
                      (vec (map-indexed (fn [i t] (when (and t (every? (fn [[e args]] (= t (infer-num e (nth args i nil)))) rs)) t)) ts)))]
            (if (= ts' ts) [ts env'] (let [[ts2 e2] (step ts')] (recur ts2 e2))))))
      [ts env'])))


(defn- with-hinted-locals
  "A rewritten let*/loop* whose source bindings carry primitive hints: each
   hinted local is bound to its coerced init (hinted-local-init). let*: right
   after its own binding, as a rebinding of the same name. loop*: the inits run
   first in a let* (in order, each seeing the ones before), then the loop*
   binds every local from them (a hinted one coerced), so recur still targets
   the loop with its own arity."
  [h bvec0 out]
  (let [hints (mapv (fn [[b _]] (when (symbol? b) (primitive-hint (:tag (meta b))))) (partition 2 bvec0))]
    (if-not (and (#{'let* 'loop*} h) (some some? hints))
      out
      (let [[_ bvec' & body] out
            pairs (partition 2 bvec')]
        (if (= h 'let*)
          ;; the rewritten vector may hold markers ([b v m nil]): walk it and
          ;; rebind a hinted local right after its own binding
          ;; by position, not by name: a name bound twice ((let [x 1 x (str x)]))
          ;; carries each binding's own hint; a marker pair matches no source one
          (let [src (vec (map (fn [[b _] hint] [(when (symbol? b) (*plain* (bare-sym b))) hint (when (symbol? b) (::inferred (meta b)))])
                              (partition 2 bvec0) hints))
                bvec'' (loop [ps (seq pairs) i 0 acc []]
                         (if-let [[b v] (first ps)]
                           (let [[nm hint inf] (get src i)
                                 own? (and (< i (count src)) (symbol? b) (= nm (*plain* (bare-sym b))))]
                             (recur (next ps) (if own? (inc i) i)
                                    (if (and own? hint)
                                      (let [s (bare-sym b)] (conj acc b v s (hinted-local-init hint s inf)))
                                      (conj acc b v))))
                           acc))]
            (apply list h bvec'' body))
          (let [syms (map (fn [[b _]] (bare-sym b)) pairs)
                infs (map (fn [[b _]] (when (symbol? b) (::inferred (meta b)))) (partition 2 bvec0))
                pre (vec (mapcat (fn [[b v]] [b v]) pairs))
                lbs (vec (mapcat (fn [s hint inf] [s (if hint (hinted-local-init hint s inf) s)]) syms hints infs))]
            (list 'let* pre (apply list h lbs body))))))))

(defn- rw-let* [ctx [h bvec & body]]
  (if-let [form' (and (= h 'loop*) (:loop-rebind-shadow? ctx) (loop-rebind bvec body))]
    (rw-let* ctx form')
    (rw-let*' ctx h bvec body)))

(defn- rw-let*' [ctx h bvec body]
  (let [;; U6-7: the certain numeric types of the bindings (adapter
        ;; :static-types); an inferred one is marked like a hint so
        ;; with-hinted-locals gives it the host's native conversion
        [types body-env] (if (and (infer-here?) (#{'let* 'loop*} h) (vector? bvec))
                           (infer-bindings h bvec body *num-env*)
                           [nil *num-env*])
        bvec (if (some some? types)
               (carry-meta (vec (mapcat (fn [[b v] t]
                                          [(if (and t (symbol? b) (not (primitive-hint (:tag (meta b))))
                                                    (get (host/native-local-casts) t))
                                             (vary-meta b assoc :tag t ::inferred true)
                                             b)
                                           v])
                                        (partition 2 bvec) types))
                           bvec)
               bvec)
        syms0 (filter symbol? (take-nth 2 bvec))
        syms (map *plain* syms0)
        tags (hint-tags *local-tags* syms0)
        classes (class-tags *local-classes* (filter (comp symbol? first) (partition 2 bvec)))
        [bs depths] (markers-for syms tags classes)]
    (binding [*locals* (into *locals* syms)
              *local-tags* tags *local-classes* classes *marker-depth* depths
              *loop-locals* (if (and (= h 'loop*) (:loop-rebind-shadow? ctx)) (into *loop-locals* syms) *loop-locals*)]
      (let [int-init? (fn [[b v]] (and (= h 'loop*) (:loop-int-init ctx) (integer? v) (symbol? b) (nil? (:tag (meta b)))))
            pairs (partition 2 bvec)
            ;; inits see the names bound before them: drop every name this form binds,
            ;; then add the literal-started loop locals for the body
            bvec' (binding [*native-ints* (apply disj *native-ints* syms)] (rw-bindings ctx bvec))
            bvec' (if-let [f (and (= h 'loop*) (:loop-int-init ctx))]
                    (carry-meta (vec (mapcat (fn [[b v] [b0 v0]] [b (if (int-init? [b0 v0]) (f v) v)])
                                             (partition 2 bvec') pairs))
                                bvec')
                    bvec')
            natives (into (apply disj *native-ints* syms)
                          (keep (fn [p] (when (int-init? p) (*plain* (first p))))) pairs)
            ;; an empty body is nil: ClojureWasm's let*, loop* and letfn* take
            ;; no empty one
            body' (binding [*native-ints* natives *num-env* body-env] (or (seq (doall (rw-all ctx body))) [nil]))]
        (if (and (= h 'let*) (seq bs))
          ;; each marker right after its local, so the inits after it see it
          ;; too (a loop*'s bindings are recur's arity: its body gets them)
          (let [ms (into {} (map (fn [[m _]] [(first (marker-info m)) m]) (partition 2 bs)))
                bvec'' (vec (mapcat (fn [[b v]] (if-let [m (and (symbol? b) (get ms (*plain* b)))] [b v m nil] [b v]))
                                    (partition 2 bvec')))]
            (with-hinted-locals h bvec (apply list h (carry-meta bvec'' bvec') body')))
          (with-hinted-locals h bvec (apply list h bvec' (with-markers bs body'))))))))

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

(defn- plain-bindings?
  "A let/loop binding vector the stock macro does not destructure: even
   length and every binding form a symbol. Those expand to (let* bindings
   body...) / (loop* bindings body...) with the vector unchanged."
  [b]
  (and (vector? b)
       (even? (count b))
       (every? symbol? (take-nth 2 b))))

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

(defn- fn*-inside?
  "True when a rewritten fn* body holds a nested fn/fn* form (a closure)
   anywhere outside a quote. jank's compile-module codegen mishandles a
   self-named fn* whose body holds closures: the generated C++ calls the
   fn with an undeclared capture (cohost.spec.alpha's op-explain and
   re-gen, found by the snapshot build; the JIT path never hits it). A
   named fn* with no closure inside compiles on both paths."
  [form]
  (cond (seq? form) (and (not= 'quote (first form))
                         (or (#{'fn 'fn*} (first form))
                             (boolean (some fn*-inside? form))))
        (coll? form) (boolean (some fn*-inside? form))
        :else false))
(defn- anonymous-fn-init?
  "An anonymous (fn ...) or (fn* ...): no name of its own."
  [x]
  (and (seq? x) (symbol? (first x))
       (contains? #{"fn" "fn*"} (name (*plain* (first x))))
       (not (symbol? (second x)))))

(defn- source-fn-inside?
  "fn*-inside? on a source form, before expansion: a nested form headed by a
   symbol named fn or fn* in any namespace (clojure.core/fn from a
   syntax-quote, cohost.core/fn), or by letfn / reify, which expand to one."
  [form]
  (cond (seq? form) (let [h (first form)]
                      (and (not (and (symbol? h) (= "quote" (name h))))
                           (or (and (symbol? h) (contains? #{"fn" "fn*" "letfn" "letfn*" "reify" "reify*"} (name (*plain* h))))
                               (boolean (some source-fn-inside? form)))))
        (coll? form) (boolean (some source-fn-inside? form))
        :else false))

(defn- self-named-init
  "A def whose init is an anonymous fn gets the var's name as the fn's own
   name, as jank's defn does, so its self-calls are the host's named
   recursion rather than calls through the var: on a :defn-fn-name? host
   only (the key is the only gate: off it, Clojure calls through the var,
   hinted or not, so a redefinition is seen), with M-9's guards. Done before
   the rewrite, so the fn's arities know their name: a hinted arity's
   self-calls reach its native entry (emit-arity's inner fn*). rw-def's own
   naming (M-9) then finds
   the fn named."
  [nm more]
  (let [init (last more)]
    (if (and (host/defn-fn-name?) (symbol? nm) (anonymous-fn-init? init)
             ;; M-9's guards: a dotted name is a class or constructor
             ;; pseudo-name jank's codegen rejects on a fn*, and a self-named
             ;; fn* holding closures breaks jank's compile-module codegen
             ;; (see fn*-inside?)
             (not (some #{\.} (str nm)))
             ;; on the source: a closure written fn, fn*, clojure.core/fn or
             ;; a macro that makes one (letfn, reify ...) -- any seq whose
             ;; head names fn or fn*, as the rewritten form's fn*-inside?
             ;; would see after expansion
             (not (some source-fn-inside? (rest init))))
      (concat (butlast more)
              [(with-meta (apply list (first init) (bare-sym nm) (rest init)) (meta init))])
      more)))

(defn- rw-def [ctx [_ nm & more]]
  (let [;; ^:redef and ^:dynamic keep every call through the var, as under
        ;; Clojure's direct linking (U6-6)
        var-call? (let [m (meta nm)] (or (:redef m) (:dynamic m)))
        more (if var-call? more (self-named-init nm more))
        nm (carry-meta nm nm)
        nm (if (some #(contains? (meta nm) %) code-meta-keys)
             (vary-meta nm (fn [m] (reduce (fn [m k] (if (contains? m k) (update m k #(rw ctx %)) m)) m code-meta-keys)))
             nm)
        nm (if-let [dm (:def-meta ctx)] (vary-meta nm merge dm) nm)
        more' (binding [*def-name* (when (symbol? nm) (*plain* (bare-sym nm)))] (doall (rw-all ctx more)))
        ;; a host whose defn names its fn (jank's own does): carry the
        ;; def's name onto an unnamed fn* value, so self-recursion is a
        ;; direct call instead of a var deref and a dynamic call. The
        ;; semantic price is pinned by a battery row: an in-flight
        ;; recursion no longer sees a redefinition of the var.
        more' (if (and (host/defn-fn-name?)
                       (not var-call?)
                       ;; a dotted name is a class or a constructor
                       ;; pseudo-name (deftype's Box.), not a fn name:
                       ;; jank's codegen rejects it on a fn*
                       (not (some #{\.} (str nm)))
                       (seq more')
                       (seq? (last more'))
                       (= 'fn* (first (last more')))
                       (not (symbol? (second (last more'))))
                       ;; and no closure inside: a self-named fn*
                       ;; holding closures breaks jank's compile-module
                       ;; codegen (see fn*-inside?)
                       (not (some fn*-inside? (rest (last more')))))
                (concat (butlast more')
                        [(apply list 'fn* (vary-meta nm (constantly nil)) (rest (last more')))])
                more')]
    (apply list 'def nm more')))

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
  (let [s (*plain* s)]
  (cond (contains? *imports* s) (get *imports* s)
        (contains? class-canon s) (get class-canon s)
        (neg? (last-dot (name s))) (symbol (str "java.lang." (name s)))
        :else s)))

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
  (let [h (*plain* h)]
  (when (and (symbol? h) (contains? spec-heads (symbol (name h)))
             (or (nil? (namespace h)) (contains? core-ns-names (namespace h))))
    (symbol (name h)))))

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
  (let [n (name (*plain* s)) i (last-dot n)]
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
          forms (map (fn [ht] (apply list h (cond ;; on a :cohost-types host a class
                                                  ;; has no value but its token: quoted,
                                                  ;; as rw-symbol quotes a class symbol
                                                  ;; in any other value position
                                                  (and (symbol? ht) (:cohost-types? ctx)
                                                       (symbol? t) (not (user-name? ctx t))
                                                       (or (class-sym? t) (namespace t)))
                                                  (list 'quote ht)
                                                  (and (symbol? ht) (nil? (namespace ht))
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

(defn- core-alias
  "The host core's own symbol for a call head h naming a booted stock core fn
   the adapter lists in :core-aliases (its semantics are the stock fn's on
   that host), when the host core has a fn of that name: emitted instead of
   the booted var, so the host's compiler sees its own op (jank inlines
   clojure.core/not). nil otherwise."
  [ctx h]
  (let [names (host/core-aliases)]
    (when (seq names)
      (when-let [n (stock-name ctx h)]
        (when (contains? names (symbol (name n)))
          (let [c (:core-aliases ctx)
                hit (get @c n ::none)]
            (if (not= ::none hit)
              (when hit hit)
              (let [a ((:try-all ctx)
                       (fn []
                         (let [hv (host/resolve-core (symbol (name n)))
                               r (when hv (host/var-root hv))]
                           (when (and (fn? r) (not (host/macro-var? hv)))
                             (with-meta (symbol (str (host/core-ns-sym)) (name n)) {:cohost/host true})))))]
                (swap! c assoc n (or a false))
                a))))))))

(defn- call-alias
  "U6-7: the adapter's :vector-aliases entry for a call of a booted stock core
   fn (a symbol key: nth, count, get, assoc), given the head the call would
   otherwise use and the rewritten args: a form that takes the host's own op
   on the host's own collection and the booted fn otherwise, or nil."
  [ctx h args']
  (when-let [n (stock-name ctx h)]
    (when-let [f (get (host/vector-aliases) (symbol (name n)))]
      (f (rw ctx h) args'))))

(defn- rewritten-macro?
  "Does h name a macro whose expansion the codemod rewrites (a stock or user
   macro, not the host's own)?"
  [ctx h]
  (when-let [v (head-var ctx h)] ((:rewritten-macro? ctx) v)))

(defn- codemod-expands?
  "True when rewrite itself applies h's macro. A clojure.core macro is left
   for the host (expand-macro returns the call unchanged)."
  [ctx h]
  (when-let [v (and (rewritten-macro? ctx h) (head-var ctx h))]
    (not= "clojure.core" (str (:ns (meta v))))))

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

(defn- sq-source-wrapper?
  "(clojure.core/with-meta x (clojure.core/hash-map :jank/source m)),
   the shape jank's reader gives a syntax-quoted form, when the meta
   map it builds holds :jank/source alone."
  [form]
  (and (seq? form) (= 3 (count form))
       (= 'clojure.core/with-meta (first form))
       (let [m (nth form 2)]
         (and (seq? m) (= 3 (count m))
              (= 'clojure.core/hash-map (first m))
              (= :jank/source (second m))))))

(defn- rw-list [ctx form]
  (if (empty? form)
    form
    (let [h (declass (canon-head (*plain* (first form))))
          args (rest form)
          hn (when (and (symbol? h) (nil? (namespace h))) (name h))
          ;; (memoized by hand: a delay made in a clojurust fn compiled to its
          ;; IR throws)
          inst-c (let [v (volatile! nil) done? (volatile! false)]
                   (fn [] (when-not @done? (vreset! v (rw ctx (second form))) (vreset! done? true)) @v))
          out
          (cond
            (= h 'var) (if (symbol? (second form)) (list 'var (restock ctx (second form))) form)
            (= h 'quote) (if (seq *kw-unrenames*) (list 'quote (unrename-data (second form))) form)
            ;; jolt's reader keeps ` as a marker the analyzer expands in *ns*;
            ;; the template is data -- the macro's output gets rewritten instead.
            (= h 'clojure.core/syntax-quote) form
            ;; a host whose reader wraps every syntax-quoted form in a
            ;; :jank/source with-meta (the cap says so): the wrapper is
            ;; source bookkeeping, not program -- drop it and rewrite
            ;; what it wrapped. &form metadata the stock macros copy is
            ;; real meta on the form itself, never this shape.
            (and (:syntax-quote-source? ctx) (sq-source-wrapper? form)) (rw ctx (second form))
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
            ;; cljrs's fn is a special form (policy :special), so the stock
            ;; macro never runs and a ^long on a plain parameter would be
            ;; ignored. Coerce only that shape. A :pre/:post map or a
            ;; destructured parameter stays as written, and every other host
            ;; leaves fn for the stock macro.
            (and (fn-special?) (symbol? h) (= "fn" (name (*plain* h)))
                 (not (contains? *locals* (*plain* h))) (fn-form-coercible? form))
            (rw-fn* ctx (cons 'fn* (rest form)))
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
                 (let [c (inst-c)] (and (seq? c) (= 'quote (first c)) (symbol? (second c)))))
            (let [c (inst-c)
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
            (if (and (starts-with? hn ".-") (> (count hn) 2) (not (:cohost-types? ctx)))
                ;; `(.-x o)` is strictly a field on the JVM: the host's own access
                (apply list h (rw-all ctx args))
                ;; on a :cohost-types host a field read is a member call like
                ;; any other: the shim's dispatcher reaches the descriptor
                (rw-method ctx (symbol (subs hn (if (starts-with? hn ".-") 2 1))) args (fn [xs'] (apply list h xs'))))
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
            ;; (let [sym init ...] body) and (loop [sym init ...] body). The
            ;; stock macro's expansion is (let* bindings body...) / (loop*
            ;; bindings body...) with that vector. Destructuring stays on
            ;; the macro. A clojure.core let is left for the host. Basilisp's
            ;; let is not that expansion: emitting let* fails clojure.spec's
            ;; registry row, so :lpy keeps the macro.
            #?(:lpy false
               :default (and (= hn "let") (plain-bindings? (first args)) (codemod-expands? ctx h)))
            (rw-let* ctx (apply list 'let* args))
            #?(:lpy false
               :default (and (= hn "loop") (plain-bindings? (first args)) (codemod-expands? ctx h)))
            (rw-let* ctx (apply list 'loop* args))
            ;; a macro whose expansion the codemod rewrites (a stock or user
            ;; macro, not the host's): its arguments stay as written, and are
            ;; rewritten once, in the expansion, as the JVM compiler sees them
            ;; after macroexpansion: inlining at the arity they end at
            ;; ((-> 10 (- 1 2))), assert and is reading the forms, and the
            ;; locals of &env (typed ones through their markers)
            (rewritten-macro? ctx h)
            (if-let [ex (:expand-macro ctx)]
                (let [x (ex (head-var ctx h) form *locals*)]
                  (if (identical? x form) (apply list (macro-head ctx h) args) (rw ctx x)))
                (apply list (macro-head ctx h) args))
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
                        (let [args' (rw-all ctx args)]
                          (or (call-alias ctx h args')
                              (apply list (or (core-alias ctx h) (rw ctx h)) args'))))))]
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

;; push/pop pairs of thread bindings, as the codemod writes them: the stock fns
;; (push-thread-bindings m) and the shim's Var-pushThreadBindings
(def ^:private push-heads #{"push-thread-bindings" "Var-pushThreadBindings"})
(def ^:private pop-heads #{"pop-thread-bindings" "Var-popThreadBindings"})
(def ^:private binding-call-nses #{nil "cohost.shim" "cohost.core" "clojure.core"})

(defn- binding-call? [heads x]
  (and (seq? x) (symbol? (first x)) (contains? heads (name (first x)))
       (contains? binding-call-nses (namespace (first x)))))

(defn- popping-try-body
  "(try body... (finally (pop-thread-bindings))) with no catch -> [body...], else nil."
  [x]
  (when (and (seq? x) (= 'try (first x)))
    (let [fin (last x)]
      (when (and (seq? fin) (= 'finally (first fin)) (= 2 (count fin)) (binding-call? pop-heads (second fin))
                 (not-any? #(and (seq? %) (= 'catch (first %))) (rest x)))
        (vec (butlast (rest x)))))))

(defn- scoped-bindings
  "form with each push of thread bindings that is followed by a try whose one
   finally pops them written as (cohost.shim/with-thread-bindings m (fn* []
   body...)): for a host whose bindings are scoped only (:binding-style
   :with-bindings). A try body cannot recur out of itself, so the fn changes
   nothing it means. Quoted forms stay as they are."
  [form]
  (letfn [(walk [x]
            (cond
              (seq? x)
              (if (or (empty? x) (= 'quote (first x)))
                x
                (let [xs (map walk x)
                      out (loop [xs (seq xs) out []]
                            (if-not xs
                              out
                              (let [a (first xs)
                                    body (when (and (binding-call? push-heads a) (= 2 (count a)))
                                           (popping-try-body (second xs)))]
                                (if body
                                  (recur (nnext xs) (conj out (list 'cohost.shim/with-thread-bindings (second a)
                                                                    (apply list 'fn* [] body))))
                                  (recur (next xs) (conj out a))))))]
                  (with-meta (apply list out) (meta x))))
              (vector? x) (with-meta (mapv walk x) (meta x))
              (map? x) (with-meta (into {} (map (fn [[k v]] [(walk k) (walk v)]) (seq x))) (meta x))
              (set? x) (with-meta (into #{} (map walk x)) (meta x))
              :else x))]
    (walk form)))

(defn rewrite
  "The codemod: form -> form the host can compile against cohost.shim.
   opts :locals -- the local names in scope (a macro expansion's, from its
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
                   (rw ctx form))
             out (if (:scoped-bindings? ctx) (scoped-bindings out) out)]
         (if-let [sf (:special-forms ctx)] (host-special-forms sf out) out))))))

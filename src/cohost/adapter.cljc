(ns cohost.adapter
  "The contract between cohost and a clojure-like host.

   cohost boots the stock clojure/core.clj (and, by the same machinery,
   other JVM-Clojure source) on a host that is not the JVM. Everything it needs
   from the host is a named capability in the map a host passes to `install!`:
   how to read and eval forms in a namespace, how to touch vars and namespaces,
   the host's constructors for the few runtime types clojure.lang names
   (LazySeq, Delay, Atom, ExceptionInfo), and the type predicates behind the
   JVM class tokens. The rest of the library calls the accessor fns defined
   here and never the host directly.

   A host adapter is one namespace (see cohost.hosts.*) that calls

     (cohost.adapter/install! {:name :jolt :core-ns 'clojure.core ...})

   Capabilities with a generic default (`defaults`) may be omitted; `install!`
   reports the required ones that are missing. Var handles are opaque: the
   library only passes back to the adapter what the adapter handed out."
  #?(:cljs (:refer-clojure :exclude [iter])))

;; ---------------------------------------------------------------------------
;; the contract, as data

(def contract-order
  "[[capability [arglist doc]] ...], in the order a new host should implement."
  [   ;; identity
   :name            [[] "the host's keyword, e.g. :jolt"]
   :core-ns         [[] "symbol of the host's own core namespace"]
   :catch-all       [[] "symbol a `catch` clause names to catch everything"]
   ;; source and eval
   :slurp           [['path] "file contents as a string"]
   :spit            [['path 's] "write s to the file at path"]
   :read-forms      [['src 'cb] "read every top-level form of src, (cb form) each, in the caller's *ns*"]
   :read-string     [['s '& 'opts] "read one form from a string, in the caller's *ns*; the 2-arity takes read-string's opts map (:eof, :read-cond, :features) and honors *data-readers* / *default-data-reader-fn* as read-string does"]
   :with-ns         [['ns-sym 'thunk] "call thunk with *ns* set to ns-sym"]
   :ensure-bare-ns! [['ns-sym] "create ns-sym referring nothing from the host core"]
   :eval            [['form] "eval form in the current *ns*"]
   :error-message   [['e] "a message for a host error, root cause included"]
   ;; namespaces and vars (handles are opaque)
   :ns-interns      [['ns-sym] "{sym handle} of the vars interned in ns-sym"]
   :interned-var    [['ns-sym 'sym] "the handle of the var sym interned in ns-sym (not one referred), or nil: one lookup, where :ns-interns builds the whole map; default: a lookup in :ns-interns"]
   :ns-public-names [['ns-sym] "#{sym} of the public vars of ns-sym"]
   :intern!         [['ns-sym 'sym 'val] "intern a var"]
   :refer-all!      [['into-ns 'from-ns] "refer every public of from-ns into into-ns"]
   :refer-from-core! [['into-ns 'sym] "make sym in into-ns the host core's own var"]
   :refer!          [['into-ns 'from-ns 'opts] "refer from-ns's publics into into-ns; opts :only :exclude :rename"]
   :alias!          [['into-ns 'alias 'target] "alias target (a namespace symbol) as alias in into-ns"]
   :set-current-ns! [['ns-sym] "make ns-sym current for the rest of the caller's with-ns extent (reads and evals)"]
   :require-host!   [['lib] "the host's own require of lib (no stock source for it); throws when absent"]
   :core-extras     [[] "{sym value}: core names the host's core lacks and the adapter supplies (cljs: extend)"]
   :resolve-core    [['sym] "the host core's public var handle for sym, or nil"]
   :var-get         [['h] "current value (the thread binding when there is one)"]
   :var-raw-root    [['h] "the root value, ignoring thread bindings"]
   :var-meta        [['h] "metadata map"]
   :alter-var-meta! [['h 'f '& 'args] "alter metadata"]
   :alter-var-root! [['h 'f] "replace the root value with (f old)"]
   :macro-var?      [['h] "does the host treat h as a macro?"]
   :mark-macro!     [['h] "make the host treat h as a macro"]
   :var-ns          [['h] "the var's namespace"]
   :var-sym         [['h] "the var's name"]
   :var?            [['x] "is x a var handle?"]
   :namespace?      [['x] "is x a namespace object?"]
   :dynamic-var?    [['h] "is the var (a handle) dynamic? (the JVM runtime's own dynamic vars carry no :dynamic metadata)"]
   :set-dynamic!    [['h] "mark the var dynamic (Var.setDynamic) -> the handle"]
   :var-bound?      [['h] "does the var have a root value? (Var.isBound)"]
   :var-thread-bound? [['h] "is the var bound in this thread's binding frames?"]
   :push-thread-bindings! [['bindings] "Var.pushThreadBindings: {handle value} -> establish bindings (a frame)"]
   :pop-thread-bindings! [[] "Var.popThreadBindings: undo the last frame"]
   :thread-bindings [[] "Var.getThreadBindings where the host core has no get-thread-bindings: {var value} of this thread's bindings, the innermost value of each (default {})"]
   :var-set!        [['h 'v] "Var.set where the host core has no var-set: set the value of the innermost binding of a var bound in this thread (default: alter-var-root!)"]
   :current-ns      [[] "the symbol of the namespace evaluated code currently runs in (*ns*)"]
   :macroexpand-1   [['form] "the host's one-step macroexpansion of form in the current namespace"]
   :macro-def-style [[] ":meta when `(def ^:macro x (fn [&form &env ...]))` + mark-macro! makes a macro; :defmacro when only a defmacro form does"]
   :macro-wrap-style [[] ":root when a macro's fn can be replaced after definition (alter-var-root); :source when its body must be wrapped at definition"]
   :rewrite-output-ref [[] "for :source wrapping: the symbol that names cohost.boot/rewrite-output in evaluated code"]
   :shim-ref        [[] "nil, or a fn from a shim var's name to the symbol evaluated code uses for it"]
   :variadic-recur-style [[] ":jvm when `recur` in a variadic arity binds the rest param to the trailing argument; :repack when the host packs the trailing arguments into a fresh rest seq (the codemod then loops such arities)"]
   :def-meta        [[] "nil, or metadata every `def` name gets so alter-var-root / with-redefs reach compiled references (basilisp: {:redef true})"]
   :inline-meta     [[] ":keep when the host ignores a var's :inline fn or is the JVM; :strip when it honors it (basilisp) — the stock inline fns build forms holding quoted JVM interop"]
   :rewrite-inline? [[] "true when the host's compiler ignores a var's :inline fn: the codemod applies core.clj's own inline expansions at rewrite time, as the JVM compiler does (with :inline-meta :keep); default false"]
   :empty-queue     [[] "the host's empty persistent queue (clojure.lang.PersistentQueue/EMPTY), or nil"]
   :host-class      [['token] "the host's own class or interface a JVM class token names, or nil: instance? also asks it"]
   :host-instance?  [['token 'x] "is x, an object that is not the host's own data, an instance of the JVM class token on this host? (default: instance? of :host-class)"]
   :protocol-impl-style [[] ":clj when extend-protocol / extend-type take (m ([x] ...) ([x y] ...)); :per-arity when they take one clause per arity (basilisp)"]
   :type-method-style [[] ":jvm when deftype / reify methods overload by arity and take any parameter form; :protocols (ClojureScript) when a JVM interface method is one of the host's protocol methods (:host-method gives [protocol method], and the method moves there); :python (basilisp) when a type's methods are Python methods: one definition per name (several arities of a JVM interface method merge into one variadic member), plain-symbol parameters (a destructuring parameter moves into a let), and a protocol method signature's receiver named this (the host counts the method's arity from it)"]
   :host-method     [['token 'method] "on a :protocols host: [protocol method] implementing method of the JVM interface token, or nil to keep it. On a :python host: the host's name for method of the JVM interface token when a deftype / reify implements it (clojure.lang.ILookup valAt -> val-at; java.lang.Object toString -> __str__, a head-less dunder method), or nil to keep it"]
   :char-code       [['c] "a char's code point ((int \\a) is 97 on the JVM; where chars are one-character strings, the code of that character)"]
   :java-statics    [[] "{'Class/member fn}: the host's own java.lang statics the shim uses in place of its generic definitions (the JVM and jolt: Character/isLetter and friends, exactly)"]
   :meta            [['x] "x's metadata map, or nil (the host's meta; basilisp's own meta hands back a reify object's meta method unbound, so its adapter calls it)"]
   :host-type       [['token] "the host type (form) an extend / extend-type / extend-protocol position names for a JVM class token, a vector of them, or nil to keep the class symbol"]
   :user-type?      [['x] "is x an instance of a type user code defined — deftype, defrecord, reify, proxy — rather than the host's own data? (.m x args) in source then reaches x's own member before the shim's method; default false"]
   :env-locals      [['env] "the local names in a macro's &env, for the rewrite of its expansion; default: the keys of a map env (the JVM's)"]
   :host-aliases?   [[] "true when the codemod may emit the host core's own symbol for a shim static whose value is that fn (RT-first as first), which the host's compiler can inline; false where that compiler inlines a core fn by expanding a template unhygienically in the caller's namespace (basilisp: (+ x 1) inside cohost.core). Default true"]
   :natives         [[] "{qualified-sym impl}: the host's own implementation of a stock fn, opted into one at a time; cohost.core (or the stock library's namespace) gets impl instead of evaluating the stock definition, and a name with no entry boots the stock one. 'clojure.core/frequencies or 'clojure.string/join; impl is a fn (the adapter's source names the host's own: frequencies) or :host (the host core's var of that name, clojure.core only). Default {}"]
   :chunks          [[] "the host's chunked seqs, or nil: {:chunked-seq? :chunk-first :chunk-rest :chunk-next :chunk-buffer :chunk-append :chunk :chunk-cons}, plus :chunk? and :chunk-reduce when the host's reduce cannot walk one of its chunks (the JVM's ArrayChunk). The stock chunked branches (map, filter, concat, doseq, for, reduce1) then run over the host's own chunks; with nil, no host seq is chunked to stock code. Default: the host core's fns of those names, when it has them all"]
   :host-data?      [['x] "a cheap test, asked at every (.m x args) before the type is looked up: true only when x is certainly the host's own data, never a user type (jolt: its transients, atoms, fns, strings, numbers ...); default: never true"]
   :invoke-member   [['x 'name 'args 'absent] "x's member called name (a string, as written in the source): the field when args is empty and name is a field, else the method applied to args; absent when x has no such member"]
   :interop-passthrough? [[] "true when the host has a java.* surface of its own (the JVM, jolt): interop the shim lacks is left to the host instead of becoming a throwing cohost.shim/missing-* call"]
   :protocol-var?   [['ns-sym 'name-sym] "is the var ns-sym/name-sym a protocol on this host? (default: its root is a map with a protocol's keys)"]
   ;; runtime types clojure.lang names
   :lazy-seq        [['thunk] "a LazySeq over thunk"]
   :delay           [['thunk] "a Delay over thunk"]
   :atom            [['x] "an atom (2-arity: with metadata)"]
   :ex-info         [['msg 'map] "ex-info (3-arity: with cause)"]
   :exception?      [['x] "is x a host exception (what a JVM Throwable is here)?"]
   :exception-class [['e] "JVM-ish class token for a host-native exception"]
   :new-exception   [['klass 'msg 'cause] "a host exception of the JVM class klass (a full class symbol), message msg and cause (each nil for none), as the shim raises one: the host's own class where it has one, or an object of its own that prints and reports as that class; nil (the default) when it has neither, and the shim raises an ex-info carrying {:cohost/class klass}"]
   :ex-info?        [['x] "is x an ExceptionInfo as user code sees one? (default: a host exception whose ex-data is not just the shim's class marker)"]
   :exception-message [['e] "Throwable.getMessage: e's message, nil when it has none (default: ex-message)"]
   :exception-cause [['e] "Throwable.getCause (default: ex-cause)"]
   ;; host facts
   :nano-time       [[] "System/nanoTime: a monotonic clock, in nanoseconds"]
   :current-time-millis [[] "System/currentTimeMillis"]
   :math-ceil       [['x] "Math/ceil of a double"]
   :math-floor      [['x] "Math/floor of a double"]
   :math-sqrt       [['x] "Math/sqrt of a double"]
   :parse-long      [['s] "long or nil"]
   :parse-double    [['s] "the host's parser for a numeric string the shim has checked against the JVM's grammar -> a double"]
   :nan?            [['x] "is x NaN?"]
   :infinite?       [['x] "is x an infinity?"]
   :format          [['fmt '& 'args] "java.lang.String/format as the JVM's java.util.Formatter does it (the JVM, jolt), or nil (the default): the shim's own Formatter"]
   :sort-array!     [['arr 'cmp] "sort a host array in place, return it"]
   :array?          [['x] "is x a host array, what to-array makes? (default: the host core's array?; basilisp: a Python list)"]
   :close!          [['x] ".close"]
   :index-of        [['s 'sub 'from] "index of sub in s from from, or -1"]
   :random          [[] "a double in [0,1)"]
   :math            [[] "{sym fn} of java.lang.Math statics by their JVM names ('sin, 'nextUp ...), as the host computes them"]
   :upper-case      [['s] "String.toUpperCase"]
   :lower-case      [['s] "String.toLowerCase"]
   :re-find-at      [['re 's 'from] "the first match of re in s at or after index from: [start end [group0 group1 ...]] (nil for an unmatched group), or nil"]
   :re-match-full   [['re 's] "re matching all of s: [0 (count s) groups], or nil"]
   :re-group-count  [['re] "the number of capturing groups in re"]
   :re-source       [['re] "Pattern.pattern(): re's source string (default: (str re))"]
   :string-builder  [[] "a fresh mutable string builder that the host's str prints as its contents"]
   :sb-append!      [['sb 'x] "append (str x) to sb; return sb"]
   :sb-string       [['sb] "sb's contents"]
   :sb?             [['x] "is x a string builder from :string-builder?"]
   :edn-read-string [['opts 's] "clojure.edn/read-string: opts :readers :default :eof"]
   :iterator        [['coll] "the host's own java.util.Iterator over coll, or nil (the default) when it has none for coll: the shim then walks (seq coll) with a stand-in"]
   :iterator?       [['x] "is x a host java.util.Iterator (one :iterator returned, or a user type's)? (default false)"]
   :iterator-has-next? [['it] "Iterator.hasNext of a host iterator"]
   :iterator-next!  [['it] "Iterator.next of a host iterator"]
   :native-method   [['x 'name] "on a host without :interop-passthrough?: a fn of [x & args] that calls x's own method name (a string, as written in the source), or nil when x has none (the default). A method the shim lacks reaches it at run time."]
   ;; type predicates behind the class tokens
   :transient?      [['x] "is x a transient collection?"]
   :lazy-seq?       [['x] "is x the host's lazy seq (clojure.lang.LazySeq)?"]
   :atom?           [['x] "is x the host's atom?"]
   :volatile?       [['x] "is x a volatile?"]
   :delay?          [['x] "is x a delay?"]
   :reduced?        [['x] "is x a reduced wrapper?"]
   :deref?          [['x] "anything deref works on"]
   :pending?        [['x] "delay, lazy seq, future, promise"]
   :char?           [['x] "is x a char? (where chars are one-character strings: such a string)"]
   :regex?          [['x] "is x a compiled regex?"]
   :uuid?           [['x] "is x a UUID?"]
   :bigint?         [['x] "is x an integer of the host's arbitrary-precision type, distinct from its long (clojure.lang.BigInt)?"]
   :ratio?          [['x] "is x a ratio?"]
   :decimal?        [['x] "is x a BigDecimal?"]
   :indexed?        [['x] "does x take nth in constant time (clojure.lang.Indexed)?"]
   :counted?        [['x] "does x count in constant time (clojure.lang.Counted)?"]
   :reversible?     [['x] "does x take rseq (clojure.lang.Reversible)?"]
   :sorted?         [['x] "is x a sorted collection (clojure.lang.Sorted)?"]
   :sorted-comparator [['x] "the comparator a sorted collection was built with (default: compare)"]
   :multifn?        [['x] "is x the host's own multimethod?"]
   ;; hooks
   :after-eval      [['form 'ns-sym] "called after each form the boot evals (optional)"]])

(def contract
  "capability -> [arglist doc]"
  (into {} (map vec (partition 2 contract-order))))

;; ---------------------------------------------------------------------------
;; generic pieces: stand-ins a host need not provide

(defrecord Iter [state])

(def ^:dynamic *raw-macro-output*
  "True while stock macroexpand-1 runs: a stock macro's expansion comes back as
   the macro wrote it, not rewritten for the host's compiler."
  false)

(defrecord SB [state])
(defn- generic-sb "A StringBuilder stand-in (the host's str does not print it)." [] (->SB (atom [])))
(defn- generic-sb? [x] (instance? SB x))
(defn- generic-sb-append! [sb x] (swap! (:state sb) conj (str x)) sb)
(defn- generic-sb-str [sb] (apply str @(:state sb)))

(defrecord MList [state])
(defn mutable-list "A java.util.ArrayList stand-in." [] (->MList (atom [])))
(defn mlist? [x] (instance? MList x))

(defn seqable?* [x] (or (nil? x) (coll? x) (string? x) (seq? x)))

(def ^:private +inf (* 1e308 10.0))

(defn- generic-index-of [s sub from]
  (let [n (count sub) m (count s)]
    (loop [i (max 0 from)]
      (cond (> (+ i n) m) -1
            (= (subs s i (+ i n)) sub) i
            :else (recur (inc i))))))

(defn- generic-error-message [e]
  (let [root (loop [x e n 0] (if-let [c (and (< n 8) (ex-cause x))] (recur c (inc n)) x))
        trunc (fn [s n] (if (> (count s) n) (str (subs s 0 n) " ...") s))
        m (trunc (or (ex-message e) (str e)) 200)
        rm (when-not (identical? root e) (trunc (or (ex-message root) (str root)) 400))]
    (if rm (str rm "  (outer: " m ")") m)))

(defn- unsupported [k]
  (fn [& _] (throw (ex-info (str "cohost: this host provides no " k) {:cohost/unsupported k}))))

(declare cap)

(defn- member-call [x nm]
  (let [r ((cap :invoke-member) x nm [] ::absent)]
    (if (= ::absent r)
      (throw (ex-info (str "class " (type x) " cannot be cast to class java.util.Iterator") {:cohost/class 'java.lang.ClassCastException}))
      r)))

(def defaults
  "Generic implementations, in plain Clojure where the dialect has the pieces."
  (merge
   {:slurp #?(:cljs (unsupported :slurp) :default (fn [path] (slurp path)))
    :spit #?(:cljs (unsupported :spit) :default (fn [path s] (spit path s)))
    :read-string #?(:cljs (unsupported :read-string) :default (fn ([s] (read-string s)) ([s opts] (read-string opts s))))
    :with-ns #?(:cljs (unsupported :with-ns) :default (fn [ns-sym thunk] (binding [*ns* (the-ns ns-sym)] (thunk))))
    :eval #?(:cljs (unsupported :eval) :default (fn [form] (eval form)))
    :error-message generic-error-message
    :ns-interns #?(:cljs (unsupported :ns-interns) :default (fn [ns-sym] (ns-interns ns-sym)))
    :interned-var (fn [ns-sym sym] (get ((cap :ns-interns) ns-sym) sym))
    :ns-public-names #?(:cljs (unsupported :ns-public-names) :default (fn [ns-sym] (set (keys (ns-publics ns-sym)))))
    :intern! #?(:cljs (unsupported :intern!) :default (fn [ns-sym sym val] (intern ns-sym sym val)))
    :refer-all! #?(:cljs (unsupported :refer-all!) :default (fn [into-ns from-ns] (binding [*ns* (the-ns into-ns)] (refer from-ns))))
    :var-get #?(:cljs (fn [h] (deref h)) :default (fn [h] (var-get h)))
    :var-raw-root #?(:cljs (fn [h] (deref h)) :default (fn [h] (var-get h)))
    :var-meta (fn [h] (meta h))
    :alter-var-meta! #?(:cljs (unsupported :alter-var-meta!) :default (fn [h f & args] (apply alter-meta! h f args)))
    :alter-var-root! #?(:cljs (unsupported :alter-var-root!) :default (fn [h f] (alter-var-root h f)))
    :macro-var? (fn [h] (true? (:macro (meta h))))
    :mark-macro! #?(:cljs (unsupported :mark-macro!) :default (fn [h] (alter-meta! h assoc :macro true) h))
    :var-ns (fn [h] (:ns (meta h)))
    :var-sym (fn [h] (:name (meta h)))
    :var? (fn [x] (var? x))
    :namespace? (fn [_] false)
    :dynamic-var? (fn [h] (true? (:dynamic (meta h))))
    :set-dynamic! #?(:cljs (unsupported :set-dynamic!) :default (fn [h] (alter-meta! h assoc :dynamic true) h))
    :var-bound? #?(:cljs (fn [_] true) :default (fn [h] (try (var-get h) true (catch #?(:clj Throwable :default Exception) _ false))))
    :var-thread-bound? (fn [_] false)
    :push-thread-bindings! #?(:cljs (unsupported :push-thread-bindings!) :default (fn [m] (push-thread-bindings m)))
    :pop-thread-bindings! #?(:cljs (unsupported :pop-thread-bindings!) :default (fn [] (pop-thread-bindings)))
    :thread-bindings (fn [] {})
    :var-set! (fn [h v] ((cap :alter-var-root!) h (constantly v)) v)
    :current-ns #?(:cljs (unsupported :current-ns) :default (fn [] (symbol (str (ns-name *ns*)))))
    :macroexpand-1 #?(:cljs (unsupported :macroexpand-1) :default (fn [form] (macroexpand-1 form)))
    :refer! #?(:cljs (unsupported :refer!)
               :default (fn [into-ns from-ns {:keys [only exclude rename]}]
                          (binding [*ns* (the-ns into-ns)]
                            (apply refer from-ns (concat (when only [:only only]) (when exclude [:exclude exclude])
                                                         (when rename [:rename rename]))))))
    ;; resolved, not named: a host may have no alias fn (basilisp adds aliases on the namespace)
    :alias! #?(:cljs (unsupported :alias!) :default (fn [into-ns a target] (binding [*ns* (the-ns into-ns)] ((resolve 'alias) a target))))
    :set-current-ns! #?(:cljs (unsupported :set-current-ns!) :default (fn [ns-sym] (set! *ns* (the-ns ns-sym))))
    :require-host! #?(:cljs (unsupported :require-host!) :default (fn [lib] (require lib)))
    :core-extras {}
    :host-type nil
    :user-type? (fn [_] false)
    :host-data? (fn [_] false)
    :natives {}
    :host-aliases? true
    :env-locals (fn [env] (when (map? env) (keys env)))
    :chunks (fn []
              (let [fs (into {} (for [n '[chunked-seq? chunk-first chunk-rest chunk-next chunk-buffer chunk-append chunk chunk-cons]]
                                  [(keyword (name n)) (try (some-> ((cap :resolve-core) n) ((cap :var-get)))
                                                           (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) _ nil))]))]
                (when (every? fn? (vals fs)) fs)))
    :invoke-member (fn [_ _ _ absent] absent)
    :interop-passthrough? false
    :protocol-var? (fn [ns-sym name-sym]
                     (let [h (try (get ((cap :ns-interns) ns-sym) name-sym)
                                  (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) _ nil))
                           v (when h ((cap :var-get) h))]
                       (boolean (and (map? v) (some #(contains? v %) [:on :on-interface :interface :sigs :method-map])))))
    :protocol-impl-style :clj
    :type-method-style :jvm
    :host-method (fn [_ _] nil)
    :char-code (fn [c] (int c))
    :meta (fn [x] (meta x))
    :java-statics {}
    :host-class (fn [_] nil)
    :host-instance? nil
    :math {}
    :upper-case (unsupported :upper-case)
    :lower-case (unsupported :lower-case)
    :re-find-at (unsupported :re-find-at)
    :re-match-full (unsupported :re-match-full)
    :re-group-count (unsupported :re-group-count)
    :string-builder generic-sb
    :sb-append! generic-sb-append!
    :sb-string generic-sb-str
    :sb? generic-sb?
    :edn-read-string (unsupported :edn-read-string)
    :macro-def-style :meta
    :macro-wrap-style :root
    :rewrite-output-ref 'cohost.boot/rewrite-output
    :shim-ref nil
    :variadic-recur-style :jvm
    :def-meta nil
    :inline-meta :keep
    :rewrite-inline? false
    :empty-queue (fn [] nil)
    :atom (fn ([x] (atom x)) ([x m] (atom x :meta m)))
    :ex-info (fn ([msg m] (apply ex-info [msg m]))
               ([msg m cause] (if (nil? cause) (apply ex-info [msg m]) (apply ex-info [msg m cause]))))
    :exception-class (fn [_] 'RuntimeException)
    :nano-time (unsupported :nano-time)
    :current-time-millis (unsupported :current-time-millis)
    :math-ceil (unsupported :math-ceil)
    :math-floor (unsupported :math-floor)
    :math-sqrt (unsupported :math-sqrt)
    :parse-long (unsupported :parse-long)
    :parse-double (unsupported :parse-double)
    :nan? (fn [x] (and (number? x) (not= x x)))
    :infinite? (fn [x] (and (number? x) (or (= x +inf) (= x (- +inf)))))
    :format nil
    :re-source (fn [re] (str re))
    :iterator (fn [_] nil)
    :iterator? (fn [_] false)
    ;; an object of a type user code defined as a java.util.Iterator: its own members
    :iterator-has-next? (fn [it] (boolean (member-call it "hasNext")))
    :iterator-next! (fn [it] (member-call it "next"))
    :native-method (fn [_ _] nil)
    :new-exception (fn [_ _ _] nil)
    :ex-info? (fn [x] (and ((cap :exception?) x)
                           (let [d (ex-data x)] (and (some? d) (not (contains? d :cohost/class))))))
    :exception-message (fn [e] (ex-message e))
    :exception-cause (fn [e] (ex-cause e))
    :sort-array! (unsupported :sort-array!)
    :array? nil
    :close! (fn [_] nil)
    :index-of generic-index-of
    :random (fn [] (rand))
    ;; (:transient? :lazy-seq? :atom? are required: answering false would
    ;; mis-type every value of the host's own kind)
    :volatile? (fn [_] false)
    :delay? (fn [_] false)
    :reduced? (fn [x] (reduced? x))
    :deref? (fn [_] false)
    :pending? (fn [_] false)
    :char? (fn [_] false)
    :regex? (fn [_] false)
    :uuid? (fn [_] false)
    :bigint? (fn [_] false)
    :ratio? (fn [_] false)
    :decimal? (fn [_] false)
    :indexed? (fn [x] (vector? x))
    :counted? (fn [x] (coll? x))
    :reversible? (fn [x] (vector? x))
    :sorted? (fn [_] false)
    :sorted-comparator (fn [_] compare)
    :multifn? (fn [_] false)
    :after-eval (fn [_ _] nil)}))

(def required
  "Capabilities every host must provide (no sensible generic default)."
  [:name :core-ns :catch-all :read-forms :ensure-bare-ns! :refer-from-core! :resolve-core
   :lazy-seq :delay :exception? :transient? :lazy-seq? :atom?])

;; ---------------------------------------------------------------------------
;; registry

(defonce ^:private installed (atom nil))

(defn installed? [] (some? @installed))

(defn missing-capabilities
  "Required capabilities absent from an adapter map."
  [m]
  (vec (remove #(contains? m %) required)))

(defn unknown-capabilities [m]
  (vec (remove #(contains? contract %) (keys m))))

(defn install!
  "Install a host adapter map (merged over `defaults`). Returns the merged map.
   Throws when a required capability is missing."
  [m]
  (let [missing (missing-capabilities m)
        unknown (unknown-capabilities m)]
    (when (seq missing)
      (throw (ex-info (str "cohost: adapter " (:name m) " lacks required capabilities " (pr-str missing))
                      {:missing missing})))
    ;; a misspelled key would leave the generic default silently in place
    (when (seq unknown)
      (throw (ex-info (str "cohost: adapter " (:name m) " names capabilities the contract does not: " (pr-str unknown))
                      {:unknown unknown})))
    (reset! installed (merge defaults m))
    @installed))

(defn current [] @installed)

;; every accessor below runs through cap, many of them per element: one lookup
(defn- cap [k]
  (let [v (get @installed k ::absent)]
    (if (= ::absent v)
      (if (nil? @installed)
        (throw (ex-info "cohost: no host adapter installed (require cohost.hosts.auto or a cohost.hosts.* namespace first)" {}))
        (throw (ex-info (str "cohost: adapter provides no " k) {:capability k})))
      v)))

;; ---------------------------------------------------------------------------
;; accessors: the rest of the library calls these

(defn capability
  "The installed adapter's capability k itself, looked up once by a caller
   that asks it per element (cohost.shim's predicates)."
  [k] (cap k))

(defn host-name [] (cap :name))
(defn core-ns-sym [] (cap :core-ns))
(defn catch-all-sym [] (cap :catch-all))
(defn macro-def-style [] (cap :macro-def-style))
(defn macro-wrap-style [] (cap :macro-wrap-style))
(defn rewrite-output-ref [] (cap :rewrite-output-ref))
(defn shim-ref [] (get @installed :shim-ref))
(defn loop-variadic-recur? [] (= :repack (cap :variadic-recur-style)))
(defn host-type-fn [] (get @installed :host-type))
(defn user-type? [x] ((cap :user-type?) x))
(defn invoke-member [x nm args absent] ((cap :invoke-member) x nm args absent))
(defn interop-passthrough? [] (boolean (cap :interop-passthrough?)))
(defn protocol-var? [ns-sym name-sym] (boolean ((cap :protocol-var?) ns-sym name-sym)))
(defn protocol-impl-style [] (cap :protocol-impl-style))
(defn type-method-style [] (cap :type-method-style))
(defn host-method [token m] ((cap :host-method) token m))
(defn char-code [c] ((cap :char-code) c))
(defn meta* [x] ((cap :meta) x))
(defn java-statics [] (get @installed :java-statics {}))
(defn host-class [token] ((cap :host-class) token))
(def ^:private host-classes (atom {}))
(defn host-instance?
  "The host's own instance? answer for a JVM class token, or nil."
  [token x]
  (if-let [f (get @installed :host-instance?)]
    (f token x)
    (let [m @host-classes
          k (if (contains? m token)
              (get m token)
              (let [k (try (host-class token) (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) _ nil))]
                (swap! host-classes assoc token k)
                k))]
      (when k (instance? k x)))))
(defn core-extras [] (get @installed :core-extras {}))
(defn refer! [into-ns from-ns opts] ((cap :refer!) into-ns from-ns opts))
(defn alias! [into-ns a target] ((cap :alias!) into-ns a target))
(defn set-current-ns! [ns-sym] ((cap :set-current-ns!) ns-sym))
(defn require-host! [lib] ((cap :require-host!) lib))
(defn math-fns [] (get @installed :math {}))
(defn upper-case [s] ((cap :upper-case) s))
(defn lower-case [s] ((cap :lower-case) s))
(defn re-find-at [re s from] ((cap :re-find-at) re s from))
(defn re-match-full [re s] ((cap :re-match-full) re s))
(defn re-group-count [re] ((cap :re-group-count) re))
(defn string-builder [] ((cap :string-builder)))
(defn sb-append! [sb x] ((cap :sb-append!) sb x))
(defn sb-str [sb] ((cap :sb-string) sb))
(defn sb? [x] ((cap :sb?) x))
(defn edn-read-string [opts s] ((cap :edn-read-string) opts s))
(defn def-meta [] (get @installed :def-meta))
(defn strip-inline? [] (= :strip (cap :inline-meta)))
(defn rewrite-inline? [] (boolean (cap :rewrite-inline?)))
(defn chunks [] ((cap :chunks)))
(defn natives [] (or (get @installed :natives) {}))
(defn host-aliases? [] (boolean (cap :host-aliases?)))
(defn env-locals "The local symbols of a macro's &env." [env]
  (try (filter symbol? ((cap :env-locals) env))
       (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) _ nil)))
(defn empty-queue [] ((cap :empty-queue)))

(defn slurp-file [path] ((cap :slurp) path))
(defn spit-file [path s] ((cap :spit) path s))
(defn read-forms [src cb] ((cap :read-forms) src cb))
(defn read-string* ([s] ((cap :read-string) s)) ([s opts] ((cap :read-string) s opts)))
(defn with-ns [ns-sym thunk] ((cap :with-ns) ns-sym thunk))
(defn ensure-bare-ns! [ns-sym] ((cap :ensure-bare-ns!) ns-sym))
(defn eval-form [form] ((cap :eval) form))
(defn error-message [e] ((cap :error-message) e))
(defn after-eval [form ns-sym] ((cap :after-eval) form ns-sym))

(defn ns-interns-map [ns-sym] ((cap :ns-interns) ns-sym))
(defn interned-var [ns-sym sym] ((cap :interned-var) ns-sym sym))
(defn ns-public-names [ns-sym] ((cap :ns-public-names) ns-sym))
(defn intern! [ns-sym sym val] ((cap :intern!) ns-sym sym val))
(defn refer-all! [into-ns from-ns] ((cap :refer-all!) into-ns from-ns))
(defn refer-from-core! [into-ns sym] ((cap :refer-from-core!) into-ns sym))
(defn resolve-core [sym] ((cap :resolve-core) sym))
(defn var-root [h] ((cap :var-get) h))
(defn var-raw-root [h] ((cap :var-raw-root) h))
(defn var-meta [h] ((cap :var-meta) h))
(defn alter-var-meta! [h f & args] (apply (cap :alter-var-meta!) h f args))
(defn set-var-root! [h f] ((cap :alter-var-root!) h f))
(defn macro-var? [h] ((cap :macro-var?) h))
(defn mark-macro! [h] ((cap :mark-macro!) h))
(defn var-ns [h] ((cap :var-ns) h))
(defn var-sym [h] ((cap :var-sym) h))
(defn var?* [x] ((cap :var?) x))
(defn namespace? [x] ((cap :namespace?) x))
(defn dynamic-var? [h] ((cap :dynamic-var?) h))
(defn set-dynamic! [h] ((cap :set-dynamic!) h))
(defn var-bound? [h] ((cap :var-bound?) h))
(defn var-thread-bound? [h] ((cap :var-thread-bound?) h))
(defn push-thread-bindings! [m] ((cap :push-thread-bindings!) m))
(defn pop-thread-bindings! [] ((cap :pop-thread-bindings!)))
(defn thread-bindings [] ((cap :thread-bindings)))
(defn var-set! [h v] ((cap :var-set!) h v))
(defn current-ns [] ((cap :current-ns)))
(defn macroexpand-1* [form] ((cap :macroexpand-1) form))

(defn lazy-seq* [thunk] ((cap :lazy-seq) thunk))
(defn delay* [thunk] ((cap :delay) thunk))
(defn atom* ([x] ((cap :atom) x)) ([x m] ((cap :atom) x m)))
(defn ex-info* ([msg m] ((cap :ex-info) msg m)) ([msg m cause] ((cap :ex-info) msg m cause)))
(defn- full-class
  "'IllegalArgumentException -> 'java.lang.IllegalArgumentException"
  [klass]
  (if (some #(= "." (str %)) (name klass)) klass (symbol (str "java.lang." (name klass)))))
(defn ex*
  "The exception the JVM class klass stands for, as the shim raises it: the
   host's own (:new-exception) where it has one, else an ex-info carrying
   {:cohost/class klass} (an ex-info to the host, not to the booted core)."
  ([klass msg] (ex* klass msg nil))
  ([klass msg cause]
   (let [klass (full-class klass)
         msg (when (some? msg) (str msg))]
     (or ((cap :new-exception) klass msg cause)
         (ex-info* msg {:cohost/class klass} cause)))))
(defn exception? [x] ((cap :exception?) x))
(defn exception-class [e] ((cap :exception-class) e))
(defn ex-info? "An ExceptionInfo as user code sees one (not one of the shim's stand-ins)." [x] (boolean ((cap :ex-info?) x)))
(defn exception-message [e] ((cap :exception-message) e))
(defn exception-cause [e] ((cap :exception-cause) e))

;; java.util.Iterator: the host's own where it has one, else the stand-in
(defn iter "A java.util.Iterator over coll." [coll] (or ((cap :iterator) coll) (->Iter (atom (seq coll)))))
(defn iter? [x] (or (instance? Iter x) (boolean ((cap :iterator?) x))))
(defn iter-has-next? [it] (if (instance? Iter it) (some? @(:state it)) (boolean ((cap :iterator-has-next?) it))))
(defn iter-next! [it]
  (if (instance? Iter it)
    (let [s @(:state it)]
      (when (nil? s) (throw (ex* 'java.util.NoSuchElementException nil)))
      (reset! (:state it) (next s))
      (first s))
    ((cap :iterator-next!) it)))

(defn nano-time [] ((cap :nano-time)))
(defn current-time-millis [] ((cap :current-time-millis)))
(defn math-ceil [x] ((cap :math-ceil) x))
(defn math-floor [x] ((cap :math-floor) x))
(defn math-sqrt [x] ((cap :math-sqrt) x))
(defn parse-long* [s] ((cap :parse-long) s))
(defn parse-double* [s] ((cap :parse-double) s))
(defn nan? [x] ((cap :nan?) x))
(defn infinite?* [x] ((cap :infinite?) x))
(defn format-fn "The host's String.format, or nil: the shim's Formatter." [] (get @installed :format))
(defn re-source [re] ((cap :re-source) re))
(defn native-method [x nm] ((cap :native-method) x nm))
(defn sort-array! [arr cmp] ((cap :sort-array!) arr cmp))
(defn array-fn "The adapter's array test, or nil: the host core's array?." [] (get @installed :array?))
(defn close! [x] ((cap :close!) x))
(defn index-of [s sub from] ((cap :index-of) s sub from))
(defn last-index-of* "The last index of sub in s, or -1." [s sub]
  (loop [i -1] (let [j (index-of s sub (inc i))] (if (neg? j) i (recur j)))))
(defn random [] ((cap :random)))

(defn transient? [x] ((cap :transient?) x))
(defn lazy-seq? [x] ((cap :lazy-seq?) x))
(defn atom? [x] ((cap :atom?) x))
(defn volatile?* [x] ((cap :volatile?) x))
(defn delay?* [x] ((cap :delay?) x))
(defn reduced?* [x] ((cap :reduced?) x))
(defn deref? [x] ((cap :deref?) x))
(defn pending? [x] ((cap :pending?) x))
(defn char?* [x] ((cap :char?) x))
(defn regex? [x] ((cap :regex?) x))
(defn uuid?* [x] ((cap :uuid?) x))
(defn bigint?* [x] ((cap :bigint?) x))
(defn ratio?* [x] ((cap :ratio?) x))
(defn decimal?* [x] ((cap :decimal?) x))
(defn indexed?* [x] ((cap :indexed?) x))
(defn counted?* [x] ((cap :counted?) x))
(defn reversible?* [x] ((cap :reversible?) x))
(defn sorted?* [x] ((cap :sorted?) x))
(defn sorted-comparator [x] ((cap :sorted-comparator) x))
(defn multifn? [x] ((cap :multifn?) x))
(defn host-fn? [x] (fn? x))
(defn ifn?* [x] (ifn? x))

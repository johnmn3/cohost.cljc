;; The contract between cohost and a clojure-like host.
;;
;; cohost boots the stock clojure/core.clj (and, by the same machinery,
;; other JVM-Clojure source) on a host that is not the JVM. Everything it needs
;; from the host is a named capability in the map a host passes to `install!`:
;; how to read and eval forms in a namespace, how to touch vars and namespaces,
;; the host's constructors for the few runtime types clojure.lang names
;; (LazySeq, Delay, Atom, ExceptionInfo), and the type predicates behind the
;; JVM class tokens. The rest of the library calls the accessor fns defined
;; here and never the host directly.
;;
;; A host adapter is one namespace (see cohost.hosts.*) that calls
;;
;;   (cohost.adapter/install! {:name :jolt :core-ns 'clojure.core ...})
;;
;; Capabilities with a generic default (`defaults`) may be omitted; `install!`
;; reports the required ones that are missing. Var handles are opaque: the
;; library only passes back to the adapter what the adapter handed out.
(ns cohost.adapter
  (:require [cohost.scan :as scan])
  ;; names defined here that the host's core also has (let-go warns of each)
  #?(:cljs (:refer-clojure :exclude [iter])
     :lg (:refer-clojure :exclude [upper-case lower-case lazy-seq* delay* close! index-of lazy-seq?])))

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
   :host-read-string [['s] "read one form for the host's own core to run (the bench's host side): the host's own reader (default: :read-string)"]
   :plain-symbol    [['x] "x itself, or the bare symbol when x is a symbol carrying metadata on a host whose symbols hold none of their own (let-go: with-meta wraps a symbol in a value no symbol equals); default: x"]
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
   :add-module-path! [['path] "a directory the host's require searches from now on (jank: a compiled program has no module path); the default does nothing"]
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
   :binding-style   [[] ":push-pop (the default): bindings are pushed and popped; :with-bindings: the host's bindings are scoped only (clojurust's with-bindings*), so the codemod writes each pushThreadBindings ... (try ... (finally popThreadBindings)) as one cohost.shim/with-thread-bindings call"]
   :with-thread-bindings! [['bindings 'thunk] "(thunk) inside the frame bindings ({handle value}) establishes; the frame ends when it returns or throws (default: push, try, pop)"]
   :thread-bindings [[] "Var.getThreadBindings where the host core has no get-thread-bindings: {var value} of this thread's bindings, the innermost value of each (default {})"]
   :var-set!        [['h 'v] "Var.set where the host core has no var-set: set the value of the innermost binding of a var bound in this thread (default: alter-var-root!)"]
   :current-ns      [[] "the symbol of the namespace evaluated code currently runs in (*ns*)"]
   :macroexpand-1   [['form] "the host's one-step macroexpansion of form in the current namespace"]
   :macro-def-style [[] ":meta when `(def ^:macro x (fn [&form &env ...]))` + mark-macro! makes a macro; :defmacro when only a defmacro form does"]
   :macro-wrap-style [[] ":root when a macro's fn can be replaced after definition (alter-var-root); :source when its body must be wrapped at definition"]
   :code-form       [['form] "nil (the default), or a fn making a macro's expansion code the host's compiler takes back: applied to every expansion the codemod rewrites (ClojureWasm re-analyses no array seq, which is what a fn's & rest args are there)"]
   :loop-int-init   [['n] "nil (the default), or a fn of an integer literal -> the form a loop* binding started from that literal starts from instead. jank on unpatched apt: (cpp/long n), since jank types a literal-initialized loop local as a C++ int and recur converts back into it, wrapping at 32 bits (draft jank-21; A5). The series build (12-21) needs none: nil there"]
   :multimethod-cache? [[] "nil (the default): a stock multimethod looks its method up through its state atom's per-dispatch-value cache on every call; true: through a one-entry cache of (state, hierarchy) -> {dispatch value method} checked first, with no atom swap (cohost.shim mf-fast-method); or a fn [cell state-atom dv] -> the cached method or nil, the host's own one-call hit check of that cell ([state-value hierarchy-ref hierarchy-value {dv method}]), nil falling to mf-fast-method (jank's, a cpp/raw call; other hosts off until measured)"]
   :sort-host-compare? [[] "nil (the default): sort and a sorted coll's compare call the stock compare through the shim's comparator wrapper; true: when the comparator is the stock core's compare (whose body is the host's compare), the host's compare itself (X3-2; jank does this unconditionally, X4-1)"]
   :snapshot-emit   [['event] "nil (the default). When boot! runs with {:record? true}, each recorded event is passed to this fn if it is one. Off, and nil, do not call it"]
   :rewrite-output-ref [[] "for :source wrapping: the symbol that names cohost.boot/rewrite-output in evaluated code"]
   :shim-ref        [[] "nil, or a fn from a shim var's name to the symbol evaluated code uses for it"]
   :special-forms   [[] "nil (the default), or {fn* host-name ...}: the host's names for the special forms the codemod's output uses where they are not the JVM's (Phel: {fn* fn, let* let, loop* loop, letfn* phel.core/letfn}); letfn*'s [name fn ...] pairs become letfn's (name arities) specs. A call of a stock macro named like one of the values is written qualified (a special form wins over a var); an entry of a name to itself marks another such special form (Phel's defonce)"]
   :rest-args       [[] ":seq when a fn's & rest argument is a seq, or nil when there is none (the JVM's; default); :vector when it is the host's vector (Phel): the codemod makes each variadic arity's rest argument a seq first"]
   :named-fn*?      [[] "true when (fn* name [params] body) names the fn for its body (the JVM's; default); false when the host's fn* takes no name (ClojureWasm): the codemod binds the name with letfn* instead"]
   :variadic-recur-style [[] ":jvm when `recur` in a variadic arity binds the rest param to the trailing argument; :repack when the host packs the trailing arguments into a fresh rest seq (the codemod then loops such arities)"]
   :def-meta        [[] "nil, or metadata every `def` name gets so alter-var-root / with-redefs reach compiled references (basilisp: {:redef true})"]
   :inline-meta     [[] ":keep when the host ignores a var's :inline fn or is the JVM; :strip when it honors it (basilisp) -- the stock inline fns build forms holding quoted JVM interop"]
   :rewrite-inline? [[] "true when the host's compiler ignores a var's :inline fn: the codemod applies core.clj's own inline expansions at rewrite time, as the JVM compiler does (with :inline-meta :keep); default false"]
   :syntax-quote-source? [[] "true when the host's reader expands syntax-quote into (clojure.core/with-meta x (clojure.core/hash-map :jank/source ...)) wrappers, one per quoted form: the codemod drops those wrappers from the forms cohost evaluates -- the meta is the host's source bookkeeping, not the program's, and building it costs a hash-map per quoted symbol on every expansion; default false"]
   :loop-rebind-shadow? [[] "true when the host's compiler fails on a loop* binding a name an enclosing loop* binds (jank 0.1: C++ codegen, use of undeclared identifier 'sNN'): the codemod binds such a name under a fresh one and rebinds it with let* at the top of the body (recur is positional, so the loop is the same); default false"]
   :empty-queue     [[] "the host's empty persistent queue (clojure.lang.PersistentQueue/EMPTY), or nil"]
   :host-class      [['token] "the host's own class or interface a JVM class token names, or nil: instance? also asks it"]
   :host-instance?  [['token 'x] "is x, an object that is not the host's own data, an instance of the JVM class token on this host? (default: instance? of :host-class)"]
   :class-instance? [['c 'x] "(instance? c x) for a class value c that is no JVM class token: a type user code defined, a host class (default: the host's instance?)"]
   :protocol-impl-style [[] ":clj when extend-protocol / extend-type take (m ([x] ...) ([x y] ...)); :per-arity when they take one clause per arity (basilisp)"]
   :type-method-style [[] ":jvm when deftype / reify methods overload by arity and take any parameter form; :protocols (ClojureScript) when a JVM interface method is one of the host's protocol methods (:host-method gives [protocol method], and the method moves there); :python (basilisp) when a type's methods are Python methods: one definition per name (several arities of a JVM interface method merge into one variadic member), plain-symbol parameters (a destructuring parameter moves into a let), and a protocol method signature's receiver named this (the host counts the method's arity from it); :cohost-types (jank) when the host has no user types of its own: deftype / defrecord / defprotocol / reify are the adapter's own macros and build descriptor maps the adapter's caps dispatch on (:new-instance, :instance-desc, :instance-field), and a method is a plain fn whose first argument is the instance"]
   :host-method     [['token 'method] "on a :protocols host: [protocol method] implementing method of the JVM interface token, or nil to keep it. On a :python host: the host's name for method of the JVM interface token when a deftype / reify implements it (clojure.lang.ILookup valAt -> val-at; java.lang.Object toString -> __str__, a head-less dunder method), or nil to keep it"]
   :char-code       [['c] "a char's code point ((int \\a) is 97 on the JVM; where chars are one-character strings, the code of that character)"]
   :java-statics    [[] "{'Class/member fn}: the host's own java.lang statics the shim uses in place of its generic definitions (the JVM and jolt: Character/isLetter and friends, exactly)"]
   :meta            [['x] "x's metadata map, or nil (the host's meta; basilisp's own meta hands back a reify object's meta method unbound, so its adapter calls it)"]
   :expand-macro    [['var 'form 'locals] "nil (the default), or a fn: the expansion of a call (form) of the cohost macro var holds, as the macro wrote it, with an &env of the locals; form itself when var holds none (let-go, whose compiler hands a macro no &env: the codemod expands it in place)"]
   :host-type       [['token] "the host type (form) an extend / extend-type / extend-protocol position names for a JVM class token, a vector of them, or nil to keep the class symbol"]
   :user-type?      [['x] "is x an instance of a type user code defined -- deftype, defrecord, reify, proxy -- rather than the host's own data? (.m x args) in source then reaches x's own member before the shim's method; default false"]
   :new-instance    [['desc 'fields] "a new instance of the user type desc (a descriptor map) with these field values, on a :cohost-types host; the shim's type dispatch keys off :instance-desc, not this"]
   :instance-desc   [['x] "the descriptor (a map) of the cohost type x is an instance of, on a :cohost-types host, or nil; the descriptor carries the type's own fns for the operations the host's fns cannot do on it (:count :assoc :dissoc :conj :nth :empty :seq :get :deref :compare :reduce)"]
   :instance-field  [['x 'i] "field i of a cohost type instance, on a :cohost-types host"]
   :cohost-inst?    [['x] "true when x is an instance of a cohost type, on a :cohost-types host: the bare tag test, without returning the descriptor (:instance-desc); default false"]
   :with-meta       [['x 'm] "x with its metadata replaced by m: the host's with-meta (the default), or the type's own on a :cohost-types host, whose instances the host's with-meta cannot touch"]
   :env-locals      [['env] "the local names in a macro's &env, for the rewrite of its expansion; default: the keys of a map env (the JVM's)"]
   :host-aliases?   [[] "true when the codemod may emit the host core's own symbol for a shim static whose value is that fn (RT-first as first), which the host's compiler can inline; false where that compiler inlines a core fn by expanding a template unhygienically in the caller's namespace (basilisp: (+ x 1) inside cohost.core). Default true"]
   :int-op-aliases  [[] "{shim static name (\"Numbers-add\", \"Numbers-minus\", \"Numbers-multiply\") -> (fn [args checked]) returning the form a call of two untyped operands (locals or integer literals, which it may name twice) becomes; checked is the shim static's symbol}: where the host's own integer ops wrap and the shim's are a checked call (A5, jank on apt), the host's own promoting op inline, with the shim's checked call only when two integers overflow (A5-3). Default {}"]
   :math-aliases    [[] "{shim static name (a string, \"Math-sqrt\") -> (fn [args]) returning the form a call (Math/sqrt x) becomes on this host, or nil to keep the shim call}: the host's own math op at the call head, where its value is the JVM's for every argument (a probe per op; ops whose semantics differ keep the shim wrapper). Default {}"]
   :static-aliases  [[] "{shim static name (a string) -> the host core's symbol}: statics the host-aliases? scan cannot match because the shim's value is a wrapper with its own body, where that wrapper's semantics are the host op's on this host (jank: Numbers-minus is jank's -). Merged over the scan's aliases. Default {}"]
   :core-aliases    [[] "#{stock core name}: calls of these booted stock core fns are emitted as the host core's fn of that name, whose semantics are the stock fn's on this host, so the host's compiler sees (and may inline) its own op; the stock fn stays booted for every other use (apply, a value, with-redefs of the booted var). Default #{}"]
   :core-value-aliases [[] "{stock core name -> a host symbol}: where a booted stock core fn appears as a VALUE (an argument, (map inc xs), (filter odd? xs), a let binding's init), the codemod emits that host fn instead, whose semantics are the stock fn's on every input on this host (a probe per name: dev/research/u4/edges.sh). Price, the same as :core-aliases': a with-redefs or alter-var-root of the booted core var is not seen by a value read the codemod already emitted, and the value is not identical? to the booted var's fn. Default {}"]
   :core-expansions [[] "{stock core name -> (fn [stock-head args] form-or-nil)}: the form a call of that booted stock core fn becomes at the call head on this host (args rewritten; stock-head the rewritten head, for the case the host op does not cover), nil to keep the call: a guard around the host's own op where its semantics differ from the stock fn's on some inputs (jank: odd?/even? of a non-integer). Default {}"]
   :natives         [[] "{qualified-sym impl}: the host's own implementation of a stock fn, opted into one at a time; cohost.core (or the stock library's namespace) gets impl instead of evaluating the stock definition, and a name with no entry boots the stock one. 'clojure.core/frequencies or 'clojure.string/join; impl is a fn (the adapter's source names the host's own: frequencies) or :host (the host core's var of that name, clojure.core only). Default {}"]
   :prim-kind-test  [[] "nil (the default), or a fn of [hint sym] returning the form that tests whether sym is already of the hinted kind, which the codemod emits in place of the shim's kind test (cohost.shim/long-kind? / double-kind?). jank's returns a cpp tag test, inlined by its compiler. nil keeps the shim call."]
   :defn-fn-name?   [[] "true when a def whose value is an unnamed fn* carries the def's name onto the fn* -- (def f (fn* f ...)) -- so self-recursion is a direct call, not a var deref (jank's own defn does this). Default false: the JVM's defn leaves the fn unnamed and recursion goes through the var."]
   :native-hints    [[] "nil (the default), or {hint-symbol host-type-symbol}: the primitive hints (long, double, int, boolean) this host compiles as native types. Positions: a fn* parameter (an arity that loops or calls itself: the coerced value goes to an inner fn* whose parameter carries the type; a leaf called from elsewhere stays untyped, H0 measured it losing on jank), a return (the inner fn*'s :rettag, the type the host spells for the arity's return hint), and a let*/loop* local through :native-local-casts. Every position is coerced as the JVM coerces on every host; nil leaves every other host's rewritten forms without native tags."]
   :static-types    [[] "nil (the default), :sound or :unit (U6-7): the codemod infers numeric types from the source and passes them to the host at the positions :native-hints takes. :sound types only what is certain: a let*/loop* local from a numeric literal, typed arithmetic (+ - * inc dec; / of doubles), count, an if whose branches agree, a primitive-hinted parameter or local; a loop* local only while every recur argument in its position has its type; an inferred local gets the host's native conversion (:native-local-casts) of a value already of that type, so nothing Clojure computes changes. And a guarded typed arity: an unhinted arity whose plain parameters are direct operands of numeric core fns runs (if (and (<kind test> p) ...) (let* [p (<conversion> p) ...] <the body, rewritten again with p typed>) <the body as written>), the kind double when, in one such call, a sibling operand is certainly double, else long; a non-number argument runs the body as written. Not for an arity that calls itself (its fn's name or its def's var): on apt jank a native-typed self-recursion measured 1.8x slower than M-9's named boxed one, and a var self-call with a native argument misses a redefinition mid-recursion. :unit (U6-9 (b)) adds, on top of :sound, a defn's parameter typed from call-site evidence within one compilation unit (one eval-str: the program text cohost compiles together; cohost.rewrite/unit-param-kinds): when every call site of a defn that is not ^:dynamic or ^:redef, defined once in the unit and never used as a value (an argument, apply, var, a mention inside a macro the walk does not read) passes a certainly-long (or certainly-double) value in a position, that parameter enters the fn through the host's native conversion (:native-local-casts) and is a native local in the body. A host at :sound gets :unit when the run opts in ((cohost.rewrite/static-types! :unit); the bench's COHOST_STATIC_TYPES=unit). :unit is UNSOUND outside the unit, exactly: a caller the unit did not contain (a later eval, the REPL, a redefinition of a caller) that passes another kind gets the host's native conversion instead of Clojure's arithmetic: on jank a real passed to a long parameter or a non-number to either raises a native error no catch reaches, where Clojure computes with the real or throws ClassCastException; a bigint passed to a long parameter is range-checked to a long. Inside the unit it is sound: a kind is recorded only from literals, hints, count, typed arithmetic (a double operand makes + - * / a double), loop locals whose every recur keeps the kind, and (map/mapv/filter... (fn [i] ...) (range n)) elements. Inferred in user code only (not cohost.core nor a clojure.* library)."]
   :vector-literal  [[] "nil (the default), or {element-count fn-symbol}: a vector literal in code of that many elements, with no metadata but its reader position, is emitted as a call of the fn on its rewritten elements (jank on apt: one-block small vectors, A7-1). The fn must return the same vector the literal would"]
   :native-long-inits [[] "nil (the default), or a set of core fn names (unqualified symbols) whose call always returns a long (jank on apt: #{count}): under :native-int-aliases, a let* local started from such a call gets the host's native conversion (:native-local-casts 'long) at its init and is a native long in its body; a let* local started from arithmetic over native longs is one as it is; never a loop* local, whose recur may pass anything (U6-10)"]
   :direct-var-calls [['var-form 'args] "nil (the default), or a fn of [(var f) [args...]] -> the call form, or nil to keep (f args...): A7-4, a call to a fn var of the namespace being compiled (an unqualified head that is not a local, a macro or ^:dynamic) reads the var's root and calls the fn's fixed arity directly, not through the var's deref and a dynamic call. The var is read at every call, so a redefinition, with-redefs or alter-var-root is seen at once (nothing is cached; patch 24's call_fast, adapter-side). jank: (cpp/cohost_dcall.callN (var f) args...), N <= 6."]
   :native-self-calls? [[] "true when a fn* whose parameters carry native tags passes them natively on a self-call and honors its :rettag (jank with the series' patch 20: a native entry); false or nil when the host boxes every typed argument at the call and unboxes it at entry (apt jank 0.1: convert<long>::into_object / from_object per argument). With nil, a hinted fn that calls itself recurses through an untagged inner fn* when every self-call argument is certainly of its parameter's type (U6-11 (C)); with true, a fn hinted only on its return speculates a typed inner fn* for long arguments (U6-11 (D)). Default nil"]
   :cpp-twins?      [[] "true when the host compiles C++ in a (cpp/raw \"...\") form (jank): a self-recursive arity whose parameters are all ^long and whose body is in the numeric subset (long literals, the params, + - * inc dec, comparisons, not, zero? pos? neg?, if, self-calls) gets a C++ twin in a cpp/raw block before its def, called under the long guard; its + - * are checked (__builtin_*_overflow), and an overflow or a non-long argument runs the arity as written (U6-12). Default nil"]
   :native-local-casts [[] "nil (the default), or {hint-symbol conversion-fn-symbol}: for a ^long / ^double let* or loop* local, the host's conversion whose result the host types natively (jank: cpp/jank.runtime.to_int); the codemod emits (conversion <the JVM's coercion of the init>) as the binding's init. nil binds the coerced value only."]
   :chunks          [[] "the host's chunked seqs, or nil: {:chunked-seq? :chunk-first :chunk-rest :chunk-next :chunk-buffer :chunk-append :chunk :chunk-cons}, plus :chunk? and :chunk-reduce when the host's reduce cannot walk one of its chunks (the JVM's ArrayChunk); :chunk-reduce keeps IChunk.reduce's contract: a reduced result returned unopened. The stock chunked branches (map, filter, concat, doseq, for, reduce1) then run over the host's own chunks; with nil, no host seq is chunked to stock code. Default: the host core's fns of those names, when it has them all"]
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
   :exception-string [['e] "Throwable.toString of a host exception as the JVM writes it (the class's full name, then \": message\" when it has one), or nil (the default) when the host's str already does"]
   :double-str      [['d] "Double.toString of a double as the JVM writes it (3.0E10, 1.0E-4, 0.001), or nil (the default) when the host's str already does"]
   :exception-message [['e] "Throwable.getMessage: e's message, nil when it has none (default: ex-message)"]
   :exception-cause [['e] "Throwable.getCause (default: ex-cause)"]
   ;; host facts
   :nano-time       [[] "System/nanoTime: a monotonic clock, in nanoseconds"]
   :current-time-millis [[] "System/currentTimeMillis"]
   :exit            [['code] "System/exit: end the process with the exit code, standard output flushed; the default knows every host"]
   :math-ceil       [['x] "Math/ceil of a double"]
   :math-floor      [['x] "Math/floor of a double"]
   :math-sqrt       [['x] "Math/sqrt of a double"]
   :parse-long      [['s] "long or nil"]
   :parse-double    [['s] "the host's parser for a numeric string the shim has checked against the JVM's grammar -> a double"]
   :nan?            [['x] "is x NaN?"]
   :infinite?       [['x] "is x an infinity?"]
   :format          [['fmt '& 'args] "java.lang.String/format as the JVM's java.util.Formatter does it (the JVM, jolt), or nil (the default): the shim's own Formatter"]
   :to-array        [['coll] "a host array of coll's items, as to-array makes one: an aset of it changes it for every holder (default: the host core's to-array; Phel: an ArrayObject, its arrays being values)"]
   :sort-array!     [['arr 'cmp] "sort a host array in place, return it"]
   :array?          [['x] "is x a host array, what to-array makes? (default: the host core's array?; basilisp: a Python list)"]
   :close!          [['x] ".close"]
   :index-of        [['s 'sub 'from] "index of sub in s from from, or -1"]
   :string-chars    [['s] "s as nth reads it in constant time: s itself (the default), or a vector of its characters where nth of a string walks it (clojurust)"]
   :random          [[] "a double in [0,1)"]
   :math            [[] "{sym fn} of java.lang.Math statics by their JVM names ('sin, 'nextUp ...), as the host computes them"]
   :upper-case      [['s] "String.toUpperCase"]
   :lower-case      [['s] "String.toLowerCase"]
   :re-find-at      [['re 's 'from] "the first match of re in s at or after index from: [start end [group0 group1 ...]] (nil for an unmatched group), or nil"]
   :re-match-full   [['re 's] "re matching all of s: [0 (count s) groups], or nil"]
   :re-group-count  [['re] "the number of capturing groups in re"]
   :re-source       [['re] "Pattern.pattern(): re's source string (default: (str re))"]
   :re-pattern      [['s] "Pattern.compile(s): the host's regex of the JVM source string s (default: the host's re-pattern)"]
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

;; jank has no records: there a stand-in is a map tagged with its name
(defn standin [tag m] (assoc m :cohost/standin tag))
(defn standin? [tag x] (and (map? x) (= tag (get x :cohost/standin))))

#?(:jank (defn ->Iter [state] (standin 'Iter {:state state})) :default (defrecord Iter [state]))

(def ^:dynamic *raw-macro-output*
  "True while stock macroexpand-1 runs: a stock macro's expansion comes back as
   the macro wrote it, not rewritten for the host's compiler."
  false)

#?(:jank (defn ->SB [state] (standin 'SB {:state state})) :default (defrecord SB [state]))
(defn- generic-sb "A StringBuilder stand-in (the host's str does not print it)." [] (->SB (atom [])))
;; (on Phel a record is a struct whose name is its constructor fn: its PHP
;; class is asked, cohost\adapter\SB for SB)
(defn- generic-sb? [x] #?(:phel (php/is_a x "cohost\\adapter\\SB") :jank (standin? 'SB x) :default (instance? SB x)))
(defn- generic-sb-append! [sb x] (swap! (:state sb) conj (str x)) sb)
(defn- generic-sb-str [sb] (apply str @(:state sb)))

#?(:jank (defn ->MList [state] (standin 'MList {:state state})) :default (defrecord MList [state]))
(defn mutable-list "A java.util.ArrayList stand-in." [] (->MList (atom [])))
(defn mlist? [x] #?(:phel (php/is_a x "cohost\\adapter\\MList") :jank (standin? 'MList x) :default (instance? MList x)))

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

#?(:jank (def ^:private native-exception (gensym "native-exception")))
;; a jank compile error (jank::error_ref, a C++ value) and its message
#?(:jank (cpp/raw "#include <jank/error.hpp>
namespace cohost_err {
  inline jank::runtime::object_ref message(jank::error_ref const e)
  { return jank::runtime::make_box(e->message); }
}"))
(defn try-all
  "(try (thunk) (catch <anything> e (handler e))); handler defaults to nil.
   cohost's own code catches everything through this, so each host's catch-all
   is written once, here: ClojureScript's and jank's is :default, basilisp's
   Exception, every other host's Throwable (the JVM, jolt, ClojureWasm, let-go
   and Phel catch nothing else; ClojureWasm reads (catch :default e) as an
   ex-info whose :type is :default). A fn, not a macro: the ClojureScript build
   expands cohost's own macros on the JVM, and jank passes a macro no &env to
   tell them apart. (Code cohost evaluates gets the adapter's :catch-all.)"
  ([thunk] (try-all thunk (fn [_] nil)))
  ([thunk handler]
   #?(:jank
      ;; jank's (catch :default e) binds e to nil: a thrown jank value is caught
      ;; as an object_ref; a native C++ exception reaches the handler as an ex-info
      (let [r (try (thunk)
                   (catch cpp/jank.runtime.object_ref e (handler e))
                   (catch cpp/jank.error_ref e
                     (handler (ex-info (cpp/cohost_err.message e) {:cohost/class 'clojure.lang.Compiler$CompilerException})))
                   (catch :default _ native-exception))]
        (if (identical? r native-exception)
          (handler (ex-info "a native C++ exception" {:cohost/native true}))
          r))
      :default
      (try (thunk)
           (catch #?(:cljs :default :lpy Exception :default Throwable) e
             (handler e))))))

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
    :read-string #?(:cljs (unsupported :read-string) :phel (unsupported :read-string) :default (fn ([s] (read-string s)) ([s opts] (read-string opts s))))
    :with-ns #?(:cljs (unsupported :with-ns) :phel (unsupported :with-ns) :default (fn [ns-sym thunk] (binding [*ns* (the-ns ns-sym)] (thunk))))
    :eval #?(:cljs (unsupported :eval) :default (fn [form] (eval form)))
    :error-message generic-error-message
    :ns-interns #?(:cljs (unsupported :ns-interns) :default (fn [ns-sym] (ns-interns ns-sym)))
    :interned-var (fn [ns-sym sym] (get ((cap :ns-interns) ns-sym) sym))
    :ns-public-names #?(:cljs (unsupported :ns-public-names) :default (fn [ns-sym] (set (keys (ns-publics ns-sym)))))
    :intern! #?(:cljs (unsupported :intern!) :default (fn [ns-sym sym val] (intern ns-sym sym val)))
    :refer-all! #?(:cljs (unsupported :refer-all!) :phel (unsupported :refer-all!) :default (fn [into-ns from-ns] (binding [*ns* (the-ns into-ns)] (refer from-ns))))
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
    :var-bound? #?(:cljs (fn [_] true) :default (fn [h] (try-all (fn [] (var-get h) true) (fn [_] false))))
    :var-thread-bound? (fn [_] false)
    :push-thread-bindings! #?(:cljs (unsupported :push-thread-bindings!) :phel (unsupported :push-thread-bindings!) :lg (unsupported :push-thread-bindings!) :cljrs (unsupported :push-thread-bindings!) :default (fn [m] (push-thread-bindings m)))
    :pop-thread-bindings! #?(:cljs (unsupported :pop-thread-bindings!) :phel (unsupported :pop-thread-bindings!) :lg (unsupported :pop-thread-bindings!) :cljrs (unsupported :pop-thread-bindings!) :default (fn [] (pop-thread-bindings)))
    :binding-style :push-pop
    :with-thread-bindings! (fn [m thunk]
                             ((cap :push-thread-bindings!) m)
                             (try (thunk) (finally ((cap :pop-thread-bindings!)))))
    :thread-bindings (fn [] {})
    :var-set! (fn [h v] ((cap :alter-var-root!) h (constantly v)) v)
    :current-ns #?(:cljs (unsupported :current-ns) :phel (unsupported :current-ns) :default (fn [] (symbol (str (ns-name *ns*)))))
    :macroexpand-1 #?(:cljs (unsupported :macroexpand-1) :cljrs (unsupported :macroexpand-1) :default (fn [form] (macroexpand-1 form)))
    :refer! #?(:cljs (unsupported :refer!) :phel (unsupported :refer!)
               :default (fn [into-ns from-ns {:keys [only exclude rename]}]
                          (binding [*ns* (the-ns into-ns)]
                            (apply refer from-ns (concat (when only [:only only]) (when exclude [:exclude exclude])
                                                         (when rename [:rename rename]))))))
    ;; resolved, not named: a host may have no alias fn (basilisp adds aliases on the namespace)
    :alias! #?(:cljs (unsupported :alias!) :phel (unsupported :alias!) :default (fn [into-ns a target] (binding [*ns* (the-ns into-ns)] ((resolve 'alias) a target))))
    :set-current-ns! #?(:jolt (fn [ns-sym]
                                ;; `jolt build` loads namespaces with *ns* unbound
                                ;; (a snapshot's replayed ns directive): its root then
                                (if (thread-bound? #'*ns*)
                                  (set! *ns* (the-ns ns-sym))
                                  (alter-var-root #'*ns* (constantly (the-ns ns-sym)))))
                         :cljs (unsupported :set-current-ns!) :phel (unsupported :set-current-ns!) :jank (fn [ns-sym] (in-ns ns-sym)) :default (fn [ns-sym] (set! *ns* (the-ns ns-sym))))
    :require-host! #?(:cljs (unsupported :require-host!) :phel (unsupported :require-host!) :default (fn [lib] (require lib)))
    :add-module-path! (fn [_path] nil)
    :core-extras {}
    :host-type nil
    :expand-macro nil
    :user-type? (fn [_] false)
    :new-instance (unsupported :new-instance)
    :instance-desc (fn [_] nil)
    :cohost-inst? (fn [_] false)
    :instance-field (fn [_ _] nil)
    :with-meta (fn [x m] (with-meta x m))
    :host-data? (fn [_] false)
    :natives {}
    :native-hints nil
    :prim-kind-test nil
    :defn-fn-name? false
    :native-local-casts nil
    :native-long-inits nil
    :vector-literal nil
    :native-self-calls? nil
    :direct-var-calls nil
    :cpp-twins? nil
    :static-types nil
    :host-aliases? true
    :static-aliases {}
    :math-aliases {}
    :int-op-aliases {}
    :core-aliases #{}
    :core-value-aliases {}
    :core-expansions {}
    :env-locals (fn [env] (when (map? env) (keys env)))
    :chunks (fn []
              (let [fs (into {} (map (fn [n] [(keyword (name n)) (try-all (fn [] (some-> ((cap :resolve-core) n) ((cap :var-get)))))])
                                  '[chunked-seq? chunk-first chunk-rest chunk-next chunk-buffer chunk-append chunk chunk-cons]))]
                (when (every? fn? (vals fs)) fs)))
    :invoke-member (fn [_ _ _ absent] absent)
    :interop-passthrough? false
    :class-instance? (fn [c x] (instance? c x))
    :plain-symbol (fn [x] x)
    :protocol-var? (fn [ns-sym name-sym]
                     (let [h (try-all (fn [] (get ((cap :ns-interns) ns-sym) name-sym)))
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
    :code-form nil
    :snapshot-emit nil
    :multimethod-cache? nil
    :loop-int-init nil
    :sort-host-compare? nil
    :shim-ref nil
    :variadic-recur-style :jvm
    :named-fn*? true
    :rest-args :seq
    :special-forms nil
    :def-meta nil
    :inline-meta :keep
    :rewrite-inline? false
    :syntax-quote-source? false
    :loop-rebind-shadow? false
    :empty-queue (fn [] nil)
    :atom (fn ([x] (atom x)) ([x m] (atom x :meta m)))
    :ex-info (fn ([msg m] (apply ex-info [msg m]))
               ([msg m cause] (if (nil? cause) (apply ex-info [msg m]) (apply ex-info [msg m cause]))))
    :exception-class (fn [_] 'RuntimeException)
    :nano-time (unsupported :nano-time)
    :current-time-millis (unsupported :current-time-millis)
    :exit (fn [code]
            #?(:cljs (.exit js/process code)
               :lpy (python/exit code)
               :phel (php/exit code)
               :lg (os/exit code)
               ;; jank 0.1's flush throws "TODO: port flesh"; std::exit flushes stdout
               :jank (cpp/std.exit code)
               :default (do (flush) (System/exit code))))
    :math-ceil (unsupported :math-ceil)
    :math-floor (unsupported :math-floor)
    :math-sqrt (unsupported :math-sqrt)
    :parse-long (unsupported :parse-long)
    :parse-double (unsupported :parse-double)
    :nan? (fn [x] (and (number? x) (not= x x)))
    :infinite? (fn [x] (and (number? x) (or (= x +inf) (= x (- +inf)))))
    :format nil
    :re-source (fn [re] (str re))
    :re-pattern (fn [s] (re-pattern s))
    :iterator (fn [_] nil)
    :iterator? (fn [_] false)
    ;; an object of a type user code defined as a java.util.Iterator: its own members
    :iterator-has-next? (fn [it] (boolean (member-call it "hasNext")))
    :iterator-next! (fn [it] (member-call it "next"))
    :native-method (fn [_ _] nil)
    :new-exception (fn [_ _ _] nil)
    :ex-info? (fn [x] (and ((cap :exception?) x)
                           (let [d (ex-data x)] (and (some? d) (not (contains? d :cohost/class))))))
    :exception-string nil
    :double-str nil
    :exception-message (fn [e] (ex-message e))
    :exception-cause (fn [e] (ex-cause e))
    :to-array (fn [coll] (to-array coll))
    :sort-array! (unsupported :sort-array!)
    :array? nil
    :close! (fn [_] nil)
    :index-of generic-index-of
    :string-chars identity
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

#?(:phel (def ^:private installed (atom nil))
   :default (defonce ^:private installed (atom nil)))

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
  (let [m @installed v (get m k ::absent)]
    (if (= ::absent v)
      (cond (nil? m)
            (throw (ex-info "cohost: no host adapter installed (require cohost.hosts.auto or a cohost.hosts.* namespace first)" {}))
            ;; Phel's get answers its not-found for a key whose value is nil
            (contains? m k) nil
            :else (throw (ex-info (str "cohost: adapter provides no " k) {:capability k})))
      v)))

;; ---------------------------------------------------------------------------
;; accessors: the rest of the library calls these

(defn capability
  "The installed adapter's capability k itself, looked up once by a caller
   that asks it per element (cohost.shim's predicates)."
  [k] (cap k))

;; a namespace's name and a namespace by name, where namespaces are objects;
;; Phel's are their names (and it has neither fn)
(defn ns-name* [n] #?(:phel (symbol (str n)) :default (ns-name n)))
;; (clojurust's the-ns calls a find-ns its IR does not intercept: a fn holding
;; a `.` form is never lowered)
(defn the-ns* [x] #?(:phel x :cljs (find-ns x) :cljrs (if false (. nil tree-walked) (the-ns x)) :default (the-ns x)))

(defn host-name [] (cap :name))
(defn core-ns-sym [] (cap :core-ns))
(defn catch-all-sym [] (cap :catch-all))
(defn macro-def-style [] (cap :macro-def-style))
(defn macro-wrap-style [] (cap :macro-wrap-style))
(defn rewrite-output-ref [] (cap :rewrite-output-ref))
(defn code-form "A macro's rewritten expansion as code the host takes (adapter :code-form)." [form]
  (if-let [f (get @installed :code-form)] (f form) form))
(defn snapshot-emit
  "The installed :snapshot-emit fn, or nil. Boot record mode calls it with
   one event when it is a fn. The default capability is nil."
  []
  (get @installed :snapshot-emit))
(defn shim-ref [] (get @installed :shim-ref))
(defn loop-variadic-recur? [] (= :repack (cap :variadic-recur-style)))
(defn named-fn*? [] (boolean (cap :named-fn*?)))
(defn special-forms [] (get @installed :special-forms))
(defn vector-rest-args? [] (= :vector (cap :rest-args)))
(defn host-type-fn [] (get @installed :host-type))
(defn expand-macro-fn [] (get @installed :expand-macro))
(defn user-type? [x] ((cap :user-type?) x))
(defn new-instance [desc fields] ((cap :new-instance) desc fields))
(defn instance-desc [x] ((cap :instance-desc) x))
(defn instance-field [x i] ((cap :instance-field) x i))
(defn with-meta* [x m] ((cap :with-meta) x m))
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
(defn class-instance? [c x] ((cap :class-instance?) c x))
(defn host-instance?
  "The host's own instance? answer for a JVM class token, or nil."
  [token x]
  (if-let [f (get @installed :host-instance?)]
    (f token x)
    (let [m @host-classes
          k (if (contains? m token)
              (get m token)
              (let [k (try-all (fn [] (host-class token)))]
                (swap! host-classes assoc token k)
                k))]
      (when k (instance? k x)))))
(defn core-extras [] (get @installed :core-extras {}))
(defn refer! [into-ns from-ns opts] ((cap :refer!) into-ns from-ns opts))
(defn alias! [into-ns a target] ((cap :alias!) into-ns a target))
(defn set-current-ns! [ns-sym] ((cap :set-current-ns!) ns-sym))
(defn require-host! [lib] ((cap :require-host!) lib))
(defn add-module-path! [path] ((cap :add-module-path!) path))
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
(defn syntax-quote-source? [] (boolean (cap :syntax-quote-source?)))
(defn loop-rebind-shadow? [] (boolean (cap :loop-rebind-shadow?)))
(defn chunks [] ((cap :chunks)))
(defn natives [] (or (get @installed :natives) {}))
(defn native-hints
  "nil, or {hint-symbol host-type-symbol} of the primitive fn* parameter hints
   this host compiles as native types. nil (the default, and an uninstalled
   adapter) emits no native tag."
  []
  (when-let [m (current)] (:native-hints m)))
(defn static-types
  "nil, :sound or :unit: how far the codemod infers numeric types it then
   passes to the host as hints (U6-7)."
  []
  (when-let [m (current)] (:static-types m)))
(defn defn-fn-name? [] (boolean (cap :defn-fn-name?)))
(defn prim-kind-test
  "nil, or a fn of [hint sym] -> the form testing whether sym is already of
   the hinted kind, emitted in place of the shim's kind test. nil (the
   default, and an uninstalled adapter) keeps the shim call."
  []
  (when-let [m (current)] (:prim-kind-test m)))
(defn direct-var-calls
  "A7-4: nil, or the host's fn [(var f) args] -> a direct call form or nil."
  []
  (when-let [m (current)] (:direct-var-calls m)))
(defn cpp-twins?
  "U6-12: does the host compile C++ twins from cpp/raw?"
  []
  (boolean (when-let [m (current)] (:cpp-twins? m))))

(defn native-self-calls?
  "U6-11: does the host pass native-tagged arguments natively on a self-call?"
  []
  (boolean (when-let [m (current)] (:native-self-calls? m))))

(defn native-local-casts
  "nil, or {hint-symbol conversion-fn-symbol}: the host conversion a ^long /
   ^double let*/loop* local's coerced init goes through so the host types the
   local natively."
  []
  (when-let [m (current)] (:native-local-casts m)))
(defn host-aliases? [] (boolean (cap :host-aliases?)))
(defn loop-int-init "The adapter's :loop-int-init fn, or nil." [] (get @installed :loop-int-init))
(defn native-long-inits "The adapter's :native-long-inits set, or nil (U6-10)." [] (get @installed :native-long-inits))
(defn vector-literal "The adapter's :vector-literal map, or nil (A7-1)." [] (get @installed :vector-literal))
(defn static-aliases [] (or (cap :static-aliases) {}))
(defn math-aliases [] (or (cap :math-aliases) {}))
(defn int-op-aliases [] (or (cap :int-op-aliases) {}))
(defn core-aliases [] (or (cap :core-aliases) #{}))
(defn core-value-aliases [] (or (cap :core-value-aliases) {}))
(defn core-expansions [] (or (cap :core-expansions) {}))
(defn env-locals "The local symbols of a macro's &env." [env]
  (try-all (fn [] (filter symbol? ((cap :env-locals) env)))))
(defn empty-queue [] ((cap :empty-queue)))

(defn slurp-file [path] ((cap :slurp) path))
(defn spit-file [path s] ((cap :spit) path s))
(defn read-forms
  "cb of each top-level form of src. cohost.scan resolves its reader
   conditionals before the host's reader sees the text, by this target's
   ranking of the platform keys (cohost.scan/rankings; :clj, then :default,
   on every target today), so every host takes the same branch whatever its
   own reader would. path (when src is a file's) is where it came from: while
   a form is read, cohost.scan/provenance says which key's code it is."
  ([src cb] (read-forms src cb nil))
  ([src cb path]
   (let [[text spans] (if (neg? ((cap :index-of) src "#?" 0))
                        [src []]
                        (scan/resolve-conditionals+spans src (scan/ranking (:name (current)))))]
     (scan/with-source text spans path
       (fn [tick] ((cap :read-forms) text (fn [form] (tick) (cb form))))))))
(defn read-string* ([s] ((cap :read-string) s)) ([s opts] ((cap :read-string) s opts)))
(defn plain-symbol "x, a name taken from a form: the bare symbol (adapter :plain-symbol)." [x] ((cap :plain-symbol) x))
(defn host-read-string [s] (if-let [f (get @installed :host-read-string)] (f s) (read-string* s)))
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
(defn with-thread-bindings! [m thunk] ((cap :with-thread-bindings!) m thunk))
(defn binding-style [] (cap :binding-style))
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
(defn- stand-in-iter? [x] #?(:phel (php/is_a x "cohost\\adapter\\Iter") :jank (standin? 'Iter x) :default (instance? Iter x)))
(defn iter? [x] (or (stand-in-iter? x) (boolean ((cap :iterator?) x))))
(defn iter-has-next? [it] (if (stand-in-iter? it) (some? @(:state it)) (boolean ((cap :iterator-has-next?) it))))
(defn iter-next! [it]
  (if (stand-in-iter? it)
    (let [s @(:state it)]
      (when (nil? s) (throw (ex* 'java.util.NoSuchElementException nil)))
      (reset! (:state it) (next s))
      (first s))
    ((cap :iterator-next!) it)))

(defn nano-time [] ((cap :nano-time)))
(defn current-time-millis [] ((cap :current-time-millis)))
(defn exit [code] ((cap :exit) code))
(defn math-ceil [x] ((cap :math-ceil) x))
(defn math-floor [x] ((cap :math-floor) x))
(defn math-sqrt [x] ((cap :math-sqrt) x))
(defn parse-long* [s] ((cap :parse-long) s))
(defn parse-double* [s] ((cap :parse-double) s))
(defn nan? [x] ((cap :nan?) x))
(defn infinite?* [x] ((cap :infinite?) x))
(defn format-fn "The host's String.format, or nil: the shim's Formatter." [] (get @installed :format))
(defn re-source [re] ((cap :re-source) re))
(defn re-pattern* [s] ((cap :re-pattern) s))
(defn native-method [x nm] ((cap :native-method) x nm))
(defn sort-array! [arr cmp] ((cap :sort-array!) arr cmp))
(defn host-array "A host array of coll's items (adapter :to-array)." [coll] ((cap :to-array) coll))
(defn array-fn "The adapter's array test, or nil: the host core's array?." [] (get @installed :array?))
(defn close! [x] ((cap :close!) x))
(defn index-of [s sub from] ((cap :index-of) s sub from))
(defn string-chars [s] ((cap :string-chars) s))
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

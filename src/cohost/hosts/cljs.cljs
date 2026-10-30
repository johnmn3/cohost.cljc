(ns cohost.hosts.cljs
  "ClojureScript as a host, self-hosted: forms are compiled with cljs.js in
   this same JavaScript runtime (Node or a browser). What differs from the
   other hosts:

   - there are no macro vars: a stock macro is compiled into the namespace
     cohost.core$macros, and the analyzer is told, by name, which
     namespaces use it (:use-macros) and which fns the macros may call (:uses);
   - a macro's fn cannot be swapped after definition, so the boot wraps each
     macro's body at definition (:macro-wrap-style :source);
   - evaluated code cannot see the compiled shim namespace through the
     analyzer, so it names shim vars as JavaScript property paths (:shim-ref).

   Var handles are maps {:ns :name :macro?}; metadata the boot adds is kept
   in a side table."
  (:require [cohost.adapter :as adapter]
            [cljs.js :as cljs]
            [cljs.analyzer :as ana]
            [cljs.env :as env]
            [cljs.tools.reader :as r]
            [cljs.tools.reader.edn :as edn]
            [cljs.tools.reader.reader-types :as rt]
            [cljs.reader]
            [clojure.string :as string]
            [goog.object :as gobj]
            [goog.string :as gstring]
            [goog.string.StringBuffer])
  (:refer-clojure :exclude [ns-interns]))

(def st (cljs/empty-state))
(def ^:dynamic *ns-sym* 'cljs.user)
(defonce meta-store (atom {}))

(defn- macros-ns [ns-sym] (symbol (str ns-sym "$macros")))
(defn- defs [ns-sym] (get-in @st [::ana/namespaces ns-sym :defs]))
(def core-names (set (keys (defs 'cljs.core))))
(def core-macro-names
  (if (exists? js/cljs.core$macros)
    (set (map (comp symbol demunge) (js-keys js/cljs.core$macros)))
    #{}))

(defn- ns-obj [ns-sym]
  (apply gobj/getValueByKeys js/goog.global (.split (munge (str ns-sym)) ".")))

(defn- js-path [ns-sym nm]
  (gobj/get (ns-obj ns-sym) (munge (str nm))))

(defn- js-set-path! [ns-sym nm v]
  (gobj/set (ns-obj ns-sym) (munge (str nm)) v))

(defn- alias? [def-entry] (true? (::alias def-entry)))

(defn- macro-defs
  "The macros of ns-sym: its $macros namespace's defs minus the aliases below."
  [ns-sym]
  (into {} (remove (comp alias? val) (defs (macros-ns ns-sym)))))

(defn- forward-property!
  "obj[k] reads from[k]; assigning obj[k] (mark-macro!) turns it back into a
   plain property holding the assigned value."
  [obj from k]
  (js/Object.defineProperty
   obj k
   #js {:get (fn [] (gobj/get from k))
        :set (fn [v] (js/Object.defineProperty obj k #js {:value v :writable true :configurable true :enumerable true}))
        :configurable true :enumerable true}))

(defn- alias-into-macros!
  "Inside a $macros namespace the self-hosted analyzer resolves `ns/x` as
   `ns$macros/x` (analyze-symbol's rule for a macros namespace that requires
   itself), so a stock macro's syntax-quoted `cohost.core/str` compiles to
   `cohost.core$macros.str`. Every definition of ns-sym is therefore
   also reachable as a forwarding property of the $macros object, with an
   analyzer :defs entry (a copy of the def's, so arity checks still apply)
   marked as an alias. A macro of the same name owns the slot instead."
  [ns-sym sym]
  (let [mns (macros-ns ns-sym)
        e (get-in @st [::ana/namespaces mns :defs sym])]
    (when (or (nil? e) (alias? e))
      (forward-property! (ns-obj mns) (ns-obj ns-sym) (munge (str sym)))
      (swap! st assoc-in [::ana/namespaces mns :defs sym]
             (assoc (get-in @st [::ana/namespaces ns-sym :defs sym] {:meta {}})
                    :name (symbol (str mns) (str sym)) ::alias true)))))

(defn- js-eval! [form ns-sym]
  (let [res (atom nil)]
    ;; a stock macro's body may use a private fn of its own namespace, as on the JVM
    (binding [ana/*private-var-access-nowarn* true]
      (cljs/eval st form {:eval cljs/js-eval :ns ns-sym :context :expr :def-emits-var true
                          :load (fn [_ cb] (cb {:lang :clj :source ""}))}
                 (fn [r] (reset! res r))))
    (when-let [e (:error @res)] (throw e))
    (:value @res)))

(defn- register-uses!
  "After an eval in ns-sym: every def whose analyzer entry changed is aliased
   into the $macros namespace and registered as a use there; every new macro
   is registered as a macro use on both sides."
  [ns-sym before-defs before-macros]
  (let [mns (macros-ns ns-sym)]
    (doseq [[sym e] (defs ns-sym) :when (not (identical? e (get before-defs sym)))]
      (swap! st assoc-in [::ana/namespaces mns :uses sym] ns-sym)
      (alias-into-macros! ns-sym sym))
    (doseq [sym (remove before-macros (keys (macro-defs ns-sym)))]
      (swap! st assoc-in [::ana/namespaces ns-sym :use-macros sym] ns-sym)
      (swap! st assoc-in [::ana/namespaces mns :use-macros sym] ns-sym))))

(defn- eval-form [form]
  (let [ns-sym *ns-sym*
        mns (macros-ns ns-sym)
        before-defs (defs ns-sym)
        before-macros (set (keys (macro-defs ns-sym)))
        defmacro? (and (seq? form) (= 'defmacro (first form)))
        v (js-eval! form (if defmacro? mns ns-sym))]
    (register-uses! ns-sym before-defs before-macros)
    v))

(defn- resolve-symbol*
  "Syntax-quote resolution: a macro the namespace uses resolves to the
   namespace that defines it (as a core macro does in cljs.core); everything
   else as the analyzer resolves it."
  [sym]
  (if-let [mns (and (nil? (namespace sym))
                    (get-in @st [::ana/namespaces *ns-sym* :use-macros sym]))]
    (symbol (str mns) (str sym))
    (ana/resolve-symbol sym)))

(defn- with-reader-env [thunk]
  (binding [ana/*cljs-ns* *ns-sym*
            ;; ::kw reads through (ns-name *ns*)
            *ns* (Namespace. (js-obj) *ns-sym*)
            env/*compiler* st
            r/resolve-symbol resolve-symbol*
            ;; ::kw resolves through the alias map's nil entry
            r/*alias-map* (merge (get-in @st [::ana/namespaces *ns-sym* :requires]) {nil *ns-sym*})]
    (thunk)))

(def ^:private eof
  "A sentinel object: a keyword literal is a fresh object on every evaluation in
   an :optimizations :none build, so (identical? f ::eof) never matches."
  (js-obj))

(def ^:private host-methods
  "JVM interface methods that are protocol methods here: [protocol method]."
  '{clojure.lang.IDeref {deref [cljs.core/IDeref -deref]}
    clojure.lang.IFn {invoke [cljs.core/IFn -invoke] applyTo [Object applyTo]}
    clojure.lang.ILookup {valAt [cljs.core/ILookup -lookup]}
    clojure.lang.Counted {count [cljs.core/ICounted -count]}
    clojure.lang.Indexed {nth [cljs.core/IIndexed -nth] count [cljs.core/ICounted -count]}
    clojure.lang.Seqable {seq [cljs.core/ISeqable -seq]}
    clojure.lang.IPending {isRealized [cljs.core/IPending -realized?]}
    clojure.lang.IMeta {meta [cljs.core/IMeta -meta]}
    clojure.lang.IObj {withMeta [cljs.core/IWithMeta -with-meta] meta [cljs.core/IMeta -meta]}
    clojure.lang.IReduceInit {reduce [cljs.core/IReduce -reduce]}
    clojure.lang.IReduce {reduce [cljs.core/IReduce -reduce]}
    clojure.lang.IHashEq {hasheq [cljs.core/IHash -hash]}
    clojure.lang.Named {getName [cljs.core/INamed -name] getNamespace [cljs.core/INamed -namespace]}
    java.lang.Comparable {compareTo [cljs.core/IComparable -compare]}
    java.lang.Iterable {iterator [cljs.core/IIterable -iterator]}
    java.lang.Object {equals [cljs.core/IEquiv -equiv] hashCode [cljs.core/IHash -hash]}
    java.util.Iterator {hasNext [Object hasNext] next [Object next] remove [Object remove]}})

(defn- protocol-method
  "x's implementation of a protocol method named k (munged) taking n arguments,
   the receiver included: cljs names it <ns>$<Protocol>$<k>$arity$<n> on the
   type's prototype."
  [x k n]
  (let [suffix (str "$" k "$arity$" n)]
    (loop [o (.getPrototypeOf js/Object x)]
      (when (and (some? o) (not (identical? o (.-prototype js/Object))))
        (if-let [hit (some (fn [key] (when (gstring/endsWith key suffix) key)) (js-keys o))]
          (gobj/get o hit)
          (recur (.getPrototypeOf js/Object o)))))))

(defn- read-string-opts
  "read-string as the JVM reads: :eof (else the end is an error), #? only with
   :read-cond (:allow or :preserve, :features beside :cljs), and the stock
   core's *data-readers* and *default-data-reader-fn* in effect."
  [s opts]
  (let [drs (js-path 'cohost.core '*data-readers*)
        dflt (js-path 'cohost.core '*default-data-reader-fn*)
        ropts (cond-> {}
                (contains? opts :eof) (assoc :eof (:eof opts))
                (:read-cond opts) (assoc :read-cond (:read-cond opts) :features (conj (set (:features opts)) :cljs)))]
    (with-reader-env
      #(binding [r/*data-readers* (merge r/*data-readers* (when (map? drs) drs))
                 r/*default-data-reader-fn* (if (fn? dflt) dflt r/*default-data-reader-fn*)]
         (r/read ropts (rt/string-push-back-reader s))))))

;; Character's classes by Unicode property, as the JVM's tables have them
(defn- code-str [c] (if (number? c) (.fromCodePoint js/String c) c))
(defn- unicode-class? [cls] (let [re (js/RegExp. (str "^" cls "$") "u")] (fn [c] (.test re (code-str c)))))

(defn- read-forms
  "Each form is read in the namespace current at that moment: a library's
   (ns ...) form switches it for the forms after it."
  [src cb]
  (let [rdr (rt/string-push-back-reader src)]
    (loop []
      (let [f (with-reader-env #(r/read {:eof eof :read-cond :allow :features #{:cljs}} rdr))]
        (when-not (identical? f eof) (cb f) (recur))))))

(def ^:private emitted-bare
  "Names cljs.core's own type macros write unqualified into the code they
   emit, so they resolve in the namespace the deftype / defrecord / reify is
   in: an Object method's `(this-as this ...)`, the protocols a record
   implements, its iterator type, and defprotocol's `(set! *unchecked-if* ..)`
   (the analyzer's flag; unresolved, it warns and sets a var of the
   namespace). None is a clojure.core name."
  '#{this-as IRecord ICloneable IHash IEquiv IMeta IWithMeta ILookup IKVReduce
     IPrintWithWriter IIterable ICounted ICollection IAssociative IMap ISeqable
     RecordIter *unchecked-if*})

(defn- ensure-bare-ns! [ns-sym]
  ;; everything but defmacro (it is how a macro comes to exist here) and the
  ;; names cljs.core's type macros emit bare
  ;; once: goog.provide throws for a namespace declared before (a second
  ;; (ns x) in a session, in-ns back to x)
  (when-not (and (some? (get-in @st [::ana/namespaces ns-sym])) (some? (ns-obj ns-sym)))
    (let [excl (vec (apply disj (into core-names core-macro-names) 'defmacro emitted-bare))
          mns (macros-ns ns-sym)]
      (js-eval! (list 'ns ns-sym (list :refer-clojure :exclude excl) (list :require-macros [ns-sym])) 'cljs.user)
      (js-eval! (list 'ns mns (list :refer-clojure :exclude excl)) 'cljs.user)))
  ns-sym)

;; tagged, so a map of user code that has these keys is no var
(defn- handle [ns-sym nm macro?] {:ns ns-sym :name nm :macro? macro? ::handle true})
(defn- handle? [x] (and (map? x) (true? (::handle x))))

;; `(var x)` in evaluated code is a cljs.core/Var object (sym = ns/name); the
;; adapter accepts it wherever it accepts one of its own handles.
(defn- var-obj? [x] (instance? Var x))
(defn- as-handle [h]
  (if (var-obj? h)
    (let [s (.-sym h)] (handle (symbol (namespace s)) (symbol (name s)) false))
    h))
(defn- slot [h] (let [h (as-handle h)] [(:ns h) (:name h)]))

;; thread bindings: ClojureScript has one thread and no binding stack of its
;; own for arbitrary vars, so a frame saves each var's current value and the
;; binding overwrites the var's slot; popping restores the frame.
(defonce ^:private binding-frames (atom []))

(defn- push-thread-bindings! [m]
  (let [frame (into {} (for [[v _] m :let [[ns nm :as k] (slot v)]] [k (js-path ns nm)]))]
    (swap! binding-frames conj frame)
    (doseq [[v val] m :let [[ns nm] (slot v)]] (js-set-path! ns nm val))))

(defn- pop-thread-bindings! []
  (when-let [frame (peek @binding-frames)]
    (swap! binding-frames pop)
    (doseq [[[ns nm] old] frame] (js-set-path! ns nm old))))

;; get-thread-bindings: each var a frame binds, at its current (innermost) value
(defn- thread-bindings []
  (into {} (for [[ns nm :as k] (distinct (mapcat keys @binding-frames))]
             [(Var. (fn [] (js-path ns nm)) (symbol (str ns) (str nm)) nil) (js-path ns nm)])))

(defn- thread-bound? [h]
  (let [k (slot h)] (boolean (some #(contains? % k) @binding-frames))))

(defn- raw-root
  "The value underneath every binding frame: the oldest frame that saved it."
  [h]
  (let [[ns nm :as k] (slot h)]
    (if-let [frame (some #(when (contains? % k) %) @binding-frames)]
      (get frame k)
      (js-path ns nm))))

(defn- macroexpand-1* [form]
  (binding [env/*compiler* st ana/*cljs-ns* *ns-sym*]
    (ana/macroexpand-1 (assoc (ana/empty-env) :ns (ana/get-namespace *ns-sym*)) form)))

(defn- ns-interns [ns-sym]
  (merge (into {} (for [sym (keys (defs ns-sym))] [sym (handle ns-sym sym false)]))
         (into {} (for [sym (keys (macro-defs ns-sym))] [sym (handle (macros-ns ns-sym) sym true)]))))

(defn- interned-var
  "(get (ns-interns ns-sym) sym), without building the map: the macro wins."
  [ns-sym sym]
  (let [m (get (defs (macros-ns ns-sym)) sym)]
    (if (and m (not (alias? m)))
      (handle (macros-ns ns-sym) sym true)
      (when (contains? (defs ns-sym) sym) (handle ns-sym sym false)))))

(defn- var-meta [h]
  (let [h (as-handle h)]
    (merge (:meta (get-in @st [::ana/namespaces (:ns h) :defs (:name h)]))
           (get @meta-store [(:ns h) (:name h)])
           {:ns (:ns h) :name (:name h) :macro (:macro? h)})))

(defn- intern! [ns-sym sym val]
  (js-set-path! ns-sym sym val)
  (swap! st assoc-in [::ana/namespaces ns-sym :defs sym] {:name (symbol (str ns-sym) (str sym)) :meta {}})
  (swap! st assoc-in [::ana/namespaces (macros-ns ns-sym) :uses sym] ns-sym)
  (alias-into-macros! ns-sym sym)
  (handle ns-sym sym false))

(defn- mark-macro!
  "Move an interned fn to the namespace's macros side and register its use.
   The analyzer finds a macro by its property on the $macros namespace object
   (find-macros-ns / findInternedVar); a :defs entry is deliberately NOT
   added: check-macro-arity consults one when present and, lacking
   :method-params, rejects every call."
  [h]
  (if (:macro? h)
    h
    (let [h (as-handle h)
          ns-sym (:ns h) mns (macros-ns ns-sym) sym (:name h)
          f (js-path ns-sym sym)]
      (js-set-path! mns sym f)                 ; replaces the forwarding alias
      (swap! st update-in [::ana/namespaces ns-sym :defs] dissoc sym)
      (swap! st update-in [::ana/namespaces mns :defs] dissoc sym)
      (swap! st update-in [::ana/namespaces mns :uses] dissoc sym)
      (swap! st assoc-in [::ana/namespaces ns-sym :use-macros sym] ns-sym)
      (swap! st assoc-in [::ana/namespaces mns :use-macros sym] ns-sym)
      (handle mns sym true))))

(defn- refer-from-core!
  "A referred host var is visible in into-ns and in its $macros namespace (a
   stock macro's body names `*ns*` and friends unqualified)."
  [into-ns sym]
  (doseq [ns [into-ns (macros-ns into-ns)]]
    (if (contains? core-names sym)
      (swap! st assoc-in [::ana/namespaces ns :uses sym] 'cljs.core)
      (swap! st assoc-in [::ana/namespaces ns :use-macros sym] 'cljs.core))))

(defn- refer-all!
  "into-ns and its $macros namespace (where its own macros' bodies compile)
   both see from-ns's defs and macros, and what from-ns itself refers (host
   vars and host macros)."
  [into-ns from-ns]
  (doseq [ns [into-ns (macros-ns into-ns)]]
    (doseq [sym (keys (defs from-ns))]
      (swap! st assoc-in [::ana/namespaces ns :uses sym] from-ns))
    (doseq [sym (keys (macro-defs from-ns))]
      (swap! st assoc-in [::ana/namespaces ns :use-macros sym] from-ns))
    (doseq [[sym target] (get-in @st [::ana/namespaces from-ns :uses])]
      (swap! st assoc-in [::ana/namespaces ns :uses sym] target))
    (doseq [[sym target] (get-in @st [::ana/namespaces from-ns :use-macros])]
      (swap! st assoc-in [::ana/namespaces ns :use-macros sym] target))))

(defn- resolve-core [sym]
  (cond (contains? core-names sym) (handle 'cljs.core sym false)
        (contains? core-macro-names sym) (handle 'cljs.core$macros sym true)
        :else nil))

(defn- exception-class
  "cljs.core throws plain js/Error with a message where the JVM throws a
   class; the message decides the class token."
  [e]
  (let [m (str (.-message e))]
    (cond (some? (.-cohost$class e)) (.-cohost$class e)
          ;; tools.reader's errors: the JVM reader's classes
          (= :reader-exception (:type (ex-data e)))
          (cond (re-find #"^Invalid number" m) 'NumberFormatException
                (re-find #"[Dd]uplicate key" m) 'IllegalArgumentException
                :else 'RuntimeException)
          (instance? js/RangeError e) 'StackOverflowError
          (instance? js/TypeError e) (if (re-find #"null|undefined" m) 'NullPointerException 'ClassCastException)
          (instance? js/SyntaxError e) 'IllegalArgumentException
          (re-find #"^No item \d+ in|^Index out of bounds|^Index \d+ out of bounds" m) 'IndexOutOfBoundsException
          (re-find #"^Assert failed" m) 'AssertionError
          (re-find #"^Divide by zero" m) 'ArithmeticException
          ;; (count 1): the JVM's "count not supported on this type"
          (re-find #"^No protocol method ICounted\.-count" m) 'UnsupportedOperationException
          (re-find #"[Nn]ot supported|cannot be transient|Can't create empty" m) 'UnsupportedOperationException
          (re-find #"^Invalid arity|^Duplicate key|^Vector arg to map conj|^Wrong number of args|is not ISeqable$|^No protocol method|^No matching clause" m) 'IllegalArgumentException
          (re-find #"^Transient used after persistent|^Can't dynamically bind|^Validator rejected reference state|^Can't pop empty" m) 'IllegalStateException
          :else 'RuntimeException)))

(defn- slurp* [path]
  (if (exists? js/require)
    (.readFileSync (js/require "fs") path "utf8")
    (let [x (js/XMLHttpRequest.)]
      (.open x "GET" path false)
      (.send x)
      (if (= 200 (.-status x))
        (.-responseText x)
        (throw (js/Error. (str "No such file: " path " (HTTP " (.-status x) ")")))))))

;; ---------------------------------------------------------------------------
;; the loader: aliases and refers are analyzer state, on the namespace and on
;; its $macros twin (where its macros' bodies compile)

(defn- alias! [into-ns a target]
  (doseq [ns [into-ns (macros-ns into-ns)]]
    (swap! st assoc-in [::ana/namespaces ns :requires a] target)
    (swap! st assoc-in [::ana/namespaces ns :requires target] target)
    (swap! st assoc-in [::ana/namespaces ns :require-macros a] target)
    (swap! st assoc-in [::ana/namespaces ns :require-macros target] target)))

(defn- refer!
  [into-ns from-ns {:keys [only exclude rename]}]
  (let [ex (set exclude) on (when only (set only))
        pick? (fn [sym] (and (not (contains? ex sym)) (or (nil? on) (contains? on sym))))]
    (doseq [ns [into-ns (macros-ns into-ns)]]
      (doseq [sym (keys (defs from-ns)) :when (pick? sym)]
        (if-let [r (get rename sym)]
          (swap! st assoc-in [::ana/namespaces ns :renames r] (symbol (str from-ns) (str sym)))
          (swap! st assoc-in [::ana/namespaces ns :uses sym] from-ns)))
      (doseq [sym (keys (macro-defs from-ns)) :when (pick? sym)]
        (if-let [r (get rename sym)]
          (swap! st assoc-in [::ana/namespaces ns :rename-macros r] (symbol (str from-ns) (str sym)))
          (swap! st assoc-in [::ana/namespaces ns :use-macros sym] from-ns)))
      ;; what from-ns itself refers (host vars and host macros) comes along
      (doseq [[sym target] (get-in @st [::ana/namespaces from-ns :uses]) :when (pick? sym)]
        (swap! st assoc-in [::ana/namespaces ns :uses sym] target))
      (doseq [[sym target] (get-in @st [::ana/namespaces from-ns :use-macros]) :when (pick? sym)]
        (swap! st assoc-in [::ana/namespaces ns :use-macros sym] target)))))

(defn- require-host!
  "Only what this build compiled in can be required at run time."
  [lib]
  (when-not (get-in @st [::ana/namespaces lib])
    (throw (js/Error. (str "Could not locate " lib " on the source roots, and it is not compiled into this build")))))

;; ---------------------------------------------------------------------------
;; protocols: ClojureScript has no run-time extend, so this one does what
;; extend-type compiles to. A base type (nil, default, string ...) hangs the
;; implementation on the method fn under the type's name; a constructor gets
;; a sentinel and one prototype property per method and arity.

(def ^:private base-types
  {nil "null" 'object "object" 'string "string" 'number "number" 'array "array"
   'function "function" 'boolean "boolean" 'symbol "symbol" 'bigint "bigint" 'default "_"})

(defn- protocol-of
  "[ns-sym name-sym {method [arglists]}] of the protocol whose value is p."
  [p]
  (some (fn [[ns-sym {d :defs}]]
          (some (fn [[nm e]]
                  (when (and (:protocol-symbol e) (identical? p (js-path ns-sym nm)))
                    [ns-sym nm (:methods (:protocol-info e))]))
                d))
        (::ana/namespaces @st)))

(defn- protocol-prefix [ns-sym nm]
  (str (.replace (str ns-sym) (js/RegExp. "\\." "g") "$") "$" nm "$"))

(defn- extend*
  "clojure.core/extend: (extend type-or-base proto method-map & more)."
  [t & proto+mmaps]
  (doseq [[p mmap] (partition 2 proto+mmaps)]
    (let [[pns pnm methods] (or (protocol-of p)
                                (throw (js/Error. (str p " is not a protocol"))))
          base (when (or (nil? t) (symbol? t)) (get base-types t))]
      (if base
        (do (gobj/set p base true)
            (doseq [[k f] mmap]
              (gobj/set (js-path pns (symbol (name k))) base f)))
        (let [proto (.-prototype t)
              prefix (protocol-prefix pns pnm)]
          (gobj/set proto (munge prefix) PROTOCOL_SENTINEL)
          (doseq [[k f] mmap
                  :let [m (symbol (name k))]
                  arglist (get methods m)]
            (gobj/set proto (munge (str prefix (munge (name m)) "$arity$" (count arglist))) f)))))))

;; ---------------------------------------------------------------------------
;; run-time namespace reflection: ClojureScript's resolve, ns-interns and
;; ns-publics are macros over quoted literals; clojure.test and clojure.repl
;; call them with values. These answer from the analyzer state this
;; self-hosted compiler keeps, with cljs.core/Var objects.

(defn- ns-sym-of [x] (cond (symbol? x) x (instance? Namespace x) (.-name x) :else (symbol (str x))))

(defn- var-of [ns-sym nm]
  (let [d (get (defs ns-sym) nm)
        ;; one entry of (macro-defs ns-sym), not the whole map
        m (let [e (get (defs (macros-ns ns-sym)) nm)] (when-not (alias? e) e))]
    (when (or d m)
      (let [h (handle (if m (macros-ns ns-sym) ns-sym) nm (boolean m))]
        (Var. (fn [] (js-path (:ns h) nm)) (symbol (str ns-sym) (str nm))
              (assoc (var-meta h) :ns ns-sym :name nm))))))

(defn- rt-ns-resolve
  ([ns sym] (rt-ns-resolve ns nil sym))
  ([ns _env sym]
   (let [n (ns-sym-of ns)
         nss (get-in @st [::ana/namespaces n])]
     (if-let [q (namespace sym)]
       (let [t (or (get-in nss [:requires (symbol q)]) (symbol q))]
         (var-of t (symbol (name sym))))
       (or (var-of n sym)
           (when-let [t (get-in nss [:uses sym])] (var-of t sym))
           (when-let [t (get-in nss [:use-macros sym])] (var-of t sym)))))))

(defn- rt-ns-interns [ns]
  (let [n (ns-sym-of ns)]
    (into {} (keep (fn [nm] (when-let [v (var-of n nm)] [nm v])))
          (distinct (concat (keys (defs n)) (keys (macro-defs n)))))))

(def ^:private reflection
  {'resolve (fn ([sym] (rt-ns-resolve *ns-sym* sym)) ([env sym] (rt-ns-resolve *ns-sym* env sym)))
   'ns-resolve rt-ns-resolve
   'find-var (fn [sym] (rt-ns-resolve (symbol (namespace sym)) (symbol (name sym))))
   'ns-interns rt-ns-interns
   'ns-publics (fn [ns] (into {} (remove (fn [[_ v]] (:private (meta v)))) (rt-ns-interns ns)))
   'ns-name ns-sym-of
   ;; intern: a var (made unbound when absent, or given a root), as the JVM's
   'intern (fn ([ns nm] (let [n (ns-sym-of ns)] (when-not (get (defs n) nm) (intern! n nm js/undefined)) (var-of n nm)))
             ([ns nm v] (let [n (ns-sym-of ns)] (intern! n nm v) (var-of n nm))))
   'the-ns (fn [x] (if (instance? Namespace x) x (Namespace. (js-obj) (ns-sym-of x))))
   'find-ns (fn [s] (when (get-in @st [::ana/namespaces (ns-sym-of s)]) (Namespace. (js-obj) (ns-sym-of s))))
   'all-ns (fn [] (map (fn [s] (Namespace. (js-obj) s)) (remove #(re-find #"[$]macros$" (str %)) (keys (::ana/namespaces @st)))))
   'ns-aliases (fn [ns] (into {} (map (fn [[a t]] [a (Namespace. (js-obj) t)]))
                               (remove (fn [[a t]] (= a t)) (get-in @st [::ana/namespaces (ns-sym-of ns) :requires]))))})

;; JVM class tokens in extend positions: ClojureScript's own types
(def ^:private host-types
  (let [exists (fn [syms] (vec (filter (fn [s] (or (nil? (namespace s)) (= "js" (namespace s))
                                                   (some? (js-path 'cljs.core (symbol (name s))))))
                                       syms)))
        seqs '[cljs.core/List cljs.core/EmptyList cljs.core/LazySeq cljs.core/Cons cljs.core/IndexedSeq cljs.core/RSeq
               cljs.core/ChunkedSeq cljs.core/ChunkedCons cljs.core/Range cljs.core/IntegerRange cljs.core/Repeat
               cljs.core/Cycle cljs.core/Iterate cljs.core/ArrayNodeSeq cljs.core/NodeSeq cljs.core/KeySeq
               cljs.core/ValSeq cljs.core/PersistentArrayMapSeq cljs.core/PersistentTreeMapSeq]
        vecs '[cljs.core/PersistentVector cljs.core/Subvec cljs.core/MapEntry]
        maps '[cljs.core/PersistentArrayMap cljs.core/PersistentHashMap cljs.core/PersistentTreeMap cljs.core/ObjMap]
        sets '[cljs.core/PersistentHashSet cljs.core/PersistentTreeSet]
        lists '[cljs.core/List cljs.core/EmptyList]
        queue '[cljs.core/PersistentQueue]
        colls (vec (concat vecs maps sets seqs queue))
        fns '[function cljs.core/MetaFn]]
    (into {}
          (map (fn [[k v]] [k (exists v)]))
          {'java.lang.Object '[default]
           'java.lang.String '[string]
           'java.lang.CharSequence '[string]
           'java.lang.Character '[string]
           'java.lang.Number '[number] 'java.lang.Long '[number] 'java.lang.Integer '[number] 'java.lang.Double '[number]
           'java.lang.Boolean '[boolean]
           'clojure.lang.Keyword '[cljs.core/Keyword]
           'clojure.lang.Symbol '[cljs.core/Symbol]
           'clojure.lang.Named '[cljs.core/Keyword cljs.core/Symbol]
           'java.util.Set sets 'clojure.lang.IPersistentSet sets 'clojure.lang.APersistentSet sets
           'clojure.lang.PersistentHashSet '[cljs.core/PersistentHashSet]
           'clojure.lang.PersistentTreeSet '[cljs.core/PersistentTreeSet]
           'java.util.Map maps 'clojure.lang.IPersistentMap maps 'clojure.lang.APersistentMap maps
           'clojure.lang.PersistentArrayMap '[cljs.core/PersistentArrayMap]
           'clojure.lang.PersistentHashMap '[cljs.core/PersistentHashMap]
           'clojure.lang.PersistentTreeMap '[cljs.core/PersistentTreeMap]
           'clojure.lang.IPersistentVector vecs 'clojure.lang.APersistentVector vecs
           'clojure.lang.PersistentVector '[cljs.core/PersistentVector]
           'clojure.lang.MapEntry '[cljs.core/MapEntry]
           'clojure.lang.IPersistentList (into lists queue)
           'clojure.lang.PersistentList lists
           'clojure.lang.PersistentQueue queue
           'clojure.lang.LazySeq '[cljs.core/LazySeq]
           'clojure.lang.Cons '[cljs.core/Cons]
           'clojure.lang.ISeq seqs 'clojure.lang.ASeq seqs
           'java.util.List (vec (concat vecs seqs))
           'clojure.lang.Sequential (vec (concat vecs seqs queue))
           'clojure.lang.IPersistentCollection colls 'java.util.Collection (vec (concat vecs sets seqs queue))
           'java.lang.Iterable colls 'clojure.lang.Seqable colls
           'clojure.lang.Associative (vec (concat vecs maps))
           'clojure.lang.Counted (vec (concat vecs maps sets lists queue))
           'clojure.lang.IFn fns 'clojure.lang.Fn fns 'clojure.lang.AFn fns 'clojure.lang.AFunction fns
           'clojure.lang.IDeref '[cljs.core/Atom cljs.core/Delay cljs.core/Volatile cljs.core/Reduced cljs.core/Var]
           'clojure.lang.Atom '[cljs.core/Atom]
           'clojure.lang.Delay '[cljs.core/Delay]
           'clojure.lang.Volatile '[cljs.core/Volatile]
           'clojure.lang.Var '[cljs.core/Var]
           'clojure.lang.ExceptionInfo '[cljs.core/ExceptionInfo]
           'java.lang.Throwable '[js/Error] 'java.lang.Exception '[js/Error] 'java.lang.RuntimeException '[js/Error]
           'java.util.regex.Pattern '[js/RegExp]
           'java.util.UUID '[cljs.core/UUID]
           'java.util.Date '[js/Date]})))

;; instance? of a clojure.lang interface, for an object that is not the
;; host's own data (a reify): the protocol ClojureScript names it with
(defn- host-instance? [token x]
  (case token
    clojure.lang.IObj (satisfies? IWithMeta x)
    clojure.lang.IMeta (satisfies? IMeta x)
    clojure.lang.IFn (ifn? x)
    clojure.lang.ILookup (satisfies? ILookup x)
    clojure.lang.IDeref (satisfies? IDeref x)
    clojure.lang.IPending (satisfies? IPending x)
    clojure.lang.Counted (satisfies? ICounted x)
    clojure.lang.Seqable (satisfies? ISeqable x)
    clojure.lang.IPersistentCollection (satisfies? ICollection x)
    clojure.lang.ISeq (satisfies? ISeq x)
    clojure.lang.Associative (satisfies? IAssociative x)
    clojure.lang.IRecord (satisfies? IRecord x)
    clojure.lang.Sequential (satisfies? ISequential x)
    clojure.lang.IReduceInit (satisfies? IReduce x)
    clojure.lang.IKVReduce (satisfies? IKVReduce x)
    nil))

;; ---------------------------------------------------------------------------
;; regex, java.lang.Math, strings

(defn- global-re [re]
  (js/RegExp. (.-source re) (str (.-flags re) (when-not (.-global re) "g") (when-not (.-hasIndices re) "d"))))

(defn- match-vec [m]
  [(.-index m) (+ (.-index m) (count (aget m 0))) (mapv (fn [g] (when-not (undefined? g) g)) (array-seq m))
   (when-let [ix (.-indices m)] (mapv (fn [p] (when-not (undefined? p) [(aget p 0) (aget p 1)])) (array-seq ix)))])

(defn- re-find-at [re s from]
  (let [g (global-re re)]
    (set! (.-lastIndex g) from)
    (when-let [m (.exec g s)] (match-vec m))))

(defn- re-match-full [re s]
  (when-let [m (.exec (js/RegExp. (str "^(?:" (.-source re) ")$") (str (.replace (.replace (.-flags re) "g" "") "d" "") "d")) s)]
    (match-vec m)))

(defn- re-group-count [re] (dec (.-length (.exec (js/RegExp. (str (.-source re) "|")) ""))))

(defn- dbits [x] (let [dv (js/DataView. (js/ArrayBuffer. 8))] (.setFloat64 dv 0 x) [(.getUint32 dv 0) (.getUint32 dv 4)]))
(defn- from-bits [hi lo] (let [dv (js/DataView. (js/ArrayBuffer. 8))] (.setUint32 dv 0 hi) (.setUint32 dv 4 lo) (.getFloat64 dv 0)))
(defn- get-exponent [x] (- (bit-and (unsigned-bit-shift-right (first (dbits x)) 20) 0x7ff) 1023))
(defn- next-up [x]
  (cond (or (js/isNaN x) (= x js/Infinity)) x
        (zero? x) 4.9E-324
        :else (let [[hi lo] (dbits x)]
                (if (pos? x)
                  (if (= lo 0xffffffff) (from-bits (inc hi) 0) (from-bits hi (inc lo)))
                  (if (zero? lo) (from-bits (dec hi) 0xffffffff) (from-bits hi (dec lo)))))))
(defn- next-down [x] (- (next-up (- x))))
(defn- copy-sign [a b] (let [neg (or (neg? b) (and (zero? b) (neg? (/ 1 b))) (and (js/isNaN b) false))]
                         (if neg (- (js/Math.abs a)) (js/Math.abs a))))

(def ^:private math-fns
  {'sin js/Math.sin 'cos js/Math.cos 'tan js/Math.tan 'asin js/Math.asin 'acos js/Math.acos
   'atan js/Math.atan 'atan2 js/Math.atan2 'exp js/Math.exp 'log js/Math.log 'log10 js/Math.log10
   'sqrt js/Math.sqrt 'cbrt js/Math.cbrt 'pow js/Math.pow 'hypot js/Math.hypot
   'sinh js/Math.sinh 'cosh js/Math.cosh 'tanh js/Math.tanh 'expm1 js/Math.expm1 'log1p js/Math.log1p
   'ceil js/Math.ceil 'floor js/Math.floor 'signum js/Math.sign
   'copySign copy-sign
   'getExponent get-exponent
   'nextUp next-up 'nextDown next-down
   'nextAfter (fn [a b] (cond (or (js/isNaN a) (js/isNaN b)) js/NaN (< a b) (next-up a) (> a b) (next-down a) :else b))
   'ulp (fn [x] (let [e (get-exponent x)]
                  (cond (= e 1024) (if (js/isNaN x) x js/Infinity)
                        (= e -1023) 4.9E-324
                        :else (js/Math.pow 2 (- e 52)))))
   'scalb (fn [d n] (loop [d d n n]
                      (cond (> n 1000) (recur (* d (js/Math.pow 2 1000)) (- n 1000))
                            (< n -1000) (recur (* d (js/Math.pow 2 -1000)) (+ n 1000))
                            :else (* d (js/Math.pow 2 n)))))
   'IEEEremainder (fn [x y] (if (or (zero? y) (not (js/isFinite x)) (js/isNaN y))
                              js/NaN
                              (let [q (/ x y) f (js/Math.floor q) d (- q f)
                                    n (cond (< d 0.5) f (> d 0.5) (inc f) (even? f) f :else (inc f))]
                                (- x (* n y)))))})

(def adapter
  {:name :cljs
   :core-ns 'cljs.core
   ;; cljs.core's own fns standing in for the stock ones (adapter :natives),
   ;; one opt-in each, gated by the battery and the bench's JVM check
   ;; reduce1's (.reduce (chunk-first s) f val), by index as IChunk.reduce
   ;; walks: an IntegerRangeChunk is not reducible, and an ArrayChunk's -reduce
   ;; walks its whole backing array, past its end
   :chunks (fn [] {:chunked-seq? chunked-seq? :chunk-first chunk-first :chunk-rest chunk-rest
                   :chunk-next chunk-next :chunk-buffer chunk-buffer :chunk-append chunk-append
                   :chunk chunk :chunk-cons chunk-cons
                   :chunk? (fn [x] (implements? IChunk x))
                   :chunk-reduce (fn [c f init]
                                   (let [n (count c)]
                                     (loop [i 0 acc init]
                                       (if (< i n)
                                         (let [acc (f acc (nth c i))] (if (reduced? acc) acc (recur (inc i) acc)))
                                         acc))))})
   ;; a macro's &env is the analyzer env
   :env-locals (fn [env] (keys (:locals env)))
   :natives {'clojure.core/frequencies frequencies 'clojure.core/group-by group-by
             'clojure.core/partition partition 'clojure.core/re-seq re-seq
             'clojure.core/sort sort 'clojure.core/sort-by sort-by
             'clojure.string/join string/join}
   :catch-all :default
   :slurp slurp*
   :read-forms read-forms
   ;; the boot's own reads take #? (the 1-arity); read-string's are the JVM's
   :read-string (fn ([s] (with-reader-env #(r/read-string {:read-cond :allow :features #{:cljs}} s)))
                  ([s opts] (read-string-opts s opts)))
   ;; *ns* is a Namespace for code that asks it (clojure.test's run-tests)
   :with-ns (fn [ns-sym thunk] (binding [*ns-sym* ns-sym *ns* (Namespace. (js-obj) ns-sym)] (thunk)))
   :ensure-bare-ns! ensure-bare-ns!
   :eval eval-form
   :ns-interns ns-interns
   :interned-var interned-var
   :ns-public-names (fn [ns-sym]
                      (if (= ns-sym 'cohost.shim)
                        js/cohost.shim.public_names
                        (set (keys (defs ns-sym)))))
   :intern! intern!
   :refer-all! refer-all!
   :refer-from-core! refer-from-core!
   :resolve-core resolve-core
   :var-get (fn [h] (let [[ns nm] (slot h)] (js-path ns nm)))
   :var-raw-root raw-root
   :var-meta var-meta
   :alter-var-meta! (fn [h f & args] (swap! meta-store update (slot h) #(apply f (or % {}) args)) h)
   ;; a var bound in this thread keeps its root in the oldest frame that holds it:
   ;; alter-var-root (with-redefs) changes that root, not the binding's value
   :alter-var-root! (fn [h f]
                      (let [[ns nm :as k] (slot h)
                            i (first (keep-indexed (fn [i fr] (when (contains? fr k) i)) @binding-frames))]
                        (if (some? i)
                          (let [v (f (get-in @binding-frames [i k]))] (swap! binding-frames assoc-in [i k] v) v)
                          (let [v (f (js-path ns nm))] (js-set-path! ns nm v) v))))
   :macro-var? (fn [h] (boolean (:macro? (as-handle h))))
   :mark-macro! mark-macro!
   :var-ns (fn [h] (:ns (as-handle h)))
   :var-sym (fn [h] (:name (as-handle h)))
   :var? (fn [x] (or (handle? x) (var-obj? x)))
   :dynamic-var? (fn [h] (true? (:dynamic (var-meta h))))
   :set-dynamic! (fn [h] (swap! meta-store assoc-in [(slot h) :dynamic] true) h)
   ;; (def x nil) is bound (null); (def x) is not (undefined)
   :var-bound? (fn [h] (let [[ns nm] (slot h)] (not (undefined? (js-path ns nm)))))
   :var-thread-bound? thread-bound?
   :push-thread-bindings! push-thread-bindings!
   :pop-thread-bindings! pop-thread-bindings!
   :thread-bindings thread-bindings
   ;; var-set: the binding's value is the var's slot (the frame keeps the root)
   :var-set! (fn [h v] (let [[ns nm] (slot h)] (js-set-path! ns nm v) v))
   :current-ns (fn [] *ns-sym*)
   :macroexpand-1 macroexpand-1*
   :empty-queue (fn [] (.-EMPTY PersistentQueue))
   :set-current-ns! (fn [ns-sym] (set! *ns-sym* ns-sym) (set! *ns* (Namespace. (js-obj) ns-sym)))
   :char-code (fn [c] (.charCodeAt c 0))
   :alias! alias!
   :refer! refer!
   :require-host! require-host!
   :core-extras (merge {'extend extend*} reflection)
   :host-type (fn [token] (get host-types token))
   :host-instance? host-instance?
   :math math-fns
   :upper-case (fn [s] (.toUpperCase (str s)))
   :lower-case (fn [s] (.toLowerCase (str s)))
   :re-find-at re-find-at
   :re-match-full re-match-full
   :re-group-count re-group-count
   :re-source (fn [re] (.-source re))
   ;; deftype / reify: a JVM interface method is the protocol method that does its work here
   :type-method-style :protocols
   :host-method (fn [token m] (get-in host-methods [token m]))
   ;; java.util.Iterator: cljs.core's iterators (hasNext / next), as the host's own
   :iterator (fn [x] (when (or (nil? x) (coll? x) (string? x) (array? x) (satisfies? IIterable x)) (iter x)))
   :iterator? (fn [x] (and (some? x) (not (string? x)) (fn? (.-hasNext x)) (fn? (.-next x))))
   :iterator-has-next? (fn [it] (boolean (.hasNext it)))
   :iterator-next! (fn [it] (.next it))
   :namespace? (fn [x] (instance? Namespace x))
   :java-statics {'Character/isLetter (unicode-class? "\\p{L}")
                  'Character/isDigit (unicode-class? "\\p{Nd}")
                  'Character/isLetterOrDigit (unicode-class? "[\\p{L}\\p{Nd}]")
                  'Character/isAlphabetic (unicode-class? "\\p{Alphabetic}")
                  'Character/isUpperCase (unicode-class? "\\p{Uppercase}")
                  'Character/isLowerCase (unicode-class? "\\p{Lowercase}")}
   ;; a method the shim lacks: the object's own JavaScript method, at run time
   :native-method (fn [x nm]
                    (when (some? x)
                      (let [f (gobj/get x (munge nm))]
                        (when (fn? f) (fn [t & args] (.apply f t (into-array args)))))))
   :string-builder (fn [] (goog.string/StringBuffer.))
   :sb-append! (fn [sb x] (.append sb (str x)) sb)
   :sb-string (fn [sb] (.toString sb))
   :sb? (fn [x] (instance? goog.string/StringBuffer x))
   ;; edn/read-string returns nil for "" whatever :eof says; read takes :eof as the JVM's does
   :edn-read-string (fn [opts s]
                      (edn/read (update opts :readers #(merge (select-keys @cljs.reader/*tag-table* '[inst uuid]) %))
                                (rt/string-push-back-reader s)))
   :macro-def-style :defmacro
   ;; deftype / defrecord instances and reify objects: their constructor
   ;; carries cljs$lang$type, with a ctorStr outside cljs.core
   ;; (a record carries no ctorStr: defrecord sets ctorPrSeq instead)
   ;; the analyzer ignores a var's :inline fn; the codemod applies core.clj's
   :rewrite-inline? true
   :user-type? (fn [x] (and (some? x)
                            (or (record? x)
                                (let [c (.-constructor x)]
                                  (and (some? c) (true? (.-cljs$lang$type c))
                                       (let [s (.-cljs$lang$ctorStr c)]
                                         (and (string? s) (not (gstring/startsWith s "cljs.core/")))))))))
   ;; a field is a property, a method a function property
   :invoke-member (fn [x nm args absent]
                    (let [k (munge nm)
                          own? (.call (.-hasOwnProperty (.-prototype js/Object)) x k)
                          p (gobj/get x k)]
                      (cond (and own? (empty? args)) p
                            own? absent
                            (fn? p) (.apply p x (into-array args))
                            :else (if-let [f (protocol-method x k (inc (count args)))]
                                    (.apply f x (into-array (cons x args)))
                                    absent))))
   :protocol-var? (fn [ns-sym name-sym]
                    (boolean (get-in @st [::ana/namespaces ns-sym :defs name-sym :protocol-symbol])))
   :macro-wrap-style :source
   :rewrite-output-ref 'js/cohost.boot.rewrite_output
   :shim-ref (fn [nm] (symbol "js" (str "cohost.shim." (munge nm))))
   :lazy-seq (fn [f] (LazySeq. nil f nil nil))
   :delay (fn [f] (Delay. f nil))
   :exception? (fn [x] (instance? js/Error x))
   :exception-class exception-class
   ;; the shim's exceptions are js/Errors named for their class: (str e) is
   ;; "java.lang.Exception: boom", as on the JVM
   :new-exception (fn [klass msg cause]
                    (let [e (js/Error. (or msg ""))]
                      (set! (.-name e) (str klass))
                      (set! (.-cohost$class e) klass)
                      (when (nil? msg) (set! (.-cohost$nomsg e) true))
                      (when (some? cause) (set! (.-cause e) cause))
                      e))
   :exception-message (fn [e] (when-not (and (instance? js/Error e) (true? (.-cohost$nomsg e))) (ex-message e)))
   :exception-cause (fn [e] (or (ex-cause e) (when (instance? js/Error e) (let [c (.-cause e)] (when (some? c) c)))))
   :ex-info? (fn [x] (and (instance? ExceptionInfo x)
                          (let [d (ex-data x)] (not (or (contains? d :cohost/class) (= :reader-exception (:type d)))))))
   :nano-time (fn [] (long (* 1000000 (.now js/performance))))
   :current-time-millis (fn [] (.now js/Date))
   :math-ceil (fn [x] (js/Math.ceil x))
   :math-floor (fn [x] (js/Math.floor x))
   :math-sqrt (fn [x] (js/Math.sqrt x))
   :parse-long (fn [s] (let [n (js/parseInt s 10)] (when-not (js/isNaN n) n)))
   :parse-double (fn [s] (js/parseFloat s))
   :nan? (fn [x] (and (number? x) (js/isNaN x)))
   :infinite? (fn [x] (and (number? x) (not (js/isFinite x)) (not (js/isNaN x))))
   :sort-array! (fn [arr cmp] (.sort arr cmp) arr)
   :close! (fn [x] (when (fn? (.-close x)) (.close x)))
   :index-of (fn [s sub from] (.indexOf s sub from))
   :transient? (fn [x] (or (instance? TransientVector x) (instance? TransientHashMap x)
                           (instance? TransientArrayMap x) (instance? TransientHashSet x)))
   :lazy-seq? (fn [x] (instance? LazySeq x))
   :atom? (fn [x] (instance? Atom x))
   :volatile? (fn [x] (volatile? x))
   :delay? (fn [x] (delay? x))
   :reduced? (fn [x] (reduced? x))
   :deref? (fn [x] (satisfies? IDeref x))
   :pending? (fn [x] (satisfies? IPending x))
   :char? (fn [x] (and (string? x) (= 1 (count x))))
   :regex? (fn [x] (regexp? x))
   :uuid? (fn [x] (uuid? x))
   :indexed? (fn [x] (indexed? x))
   :counted? (fn [x] (counted? x))
   :reversible? (fn [x] (reversible? x))
   :sorted? (fn [x] (sorted? x))
   :multifn? (fn [x] (instance? MultiFn x))})

(adapter/install! adapter)

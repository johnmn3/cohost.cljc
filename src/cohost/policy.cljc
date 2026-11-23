;; Which top-level forms of clojure/core.clj boot as written (:eval), which
;; names the booted namespace takes from the host's own core instead (:host --
;; the var is referred, so it IS the host's), which are replaced by a
;; host-neutral definition (:replace), and which are skipped (:skip) because
;; they are the JVM itself: proxies, gen-class, structs, agents and refs, the
;; loader, java.io. The lists are data so a host can extend them.
(ns cohost.policy
    (:require [cohost.adapter :as host]))

(def def-heads
     (set '[def defn defn- defmacro defonce definline declare defmulti defmethod
            defprotocol deftype defrecord]))

(defn form-head [form] (when (seq? form) (first form)))

(defn form-name
      "The symbol a top-level def-like form defines, when it defines one."
      [form]
      (when (seq? form)
            (let [[h x] form]
                 (cond
                  (and (contains? def-heads h) (symbol? x)) (host/plain-symbol x)
                  (and (= h 'alter-meta!) (seq? x) (= 'var (first x))) (host/plain-symbol (second x))
                  :else nil))))

;; Taken from the host: its reader, printer, namespaces, loader, regexes, IO,
;; multimethods, concurrency -- the parts of core.clj that are a facade over
;; the JVM runtime rather than Clojure written in Clojure.
(def host-names
     (set '[;; printing / reading / IO
            str pr prn print println newline flush printf pr-str prn-str print-str println-str
            pr-on print-simple print-ctor print-sequential print-map print-meta
            format read read-string read-line read+string
            load-reader load-string load-file load compile slurp spit line-seq
            with-out-str with-in-str
     ;; structs: clojure.lang.PersistentStructMap is the host's or absent
            struct-map struct create-struct defstruct accessor
     ;; the host's own dynamic environment
            *out* *in* *err* *ns* *file* *command-line-args* *flush-on-newline*
            *print-meta* *print-dup* *print-readably* *print-length* *print-level*
            *print-namespace-maps* *verbose-defrecords* *warn-on-reflection*
            *compile-path* *compile-files* *unchecked-math* *compiler-options*
            *math-context* *source-path* *read-eval* *data-readers* default-data-readers
            *default-data-reader-fn* *e *1 *2 *3 *assert* *loaded-libs* *fn-loader*
            *use-context-classloader* *allow-unresolved-vars* *suppress-read* *repl*
     ;; regex: the host's Pattern; re-matcher, re-find, re-groups, re-seq and
     ;; re-matches boot stock over the shim's Matcher
            re-pattern
     ;; namespaces and the loader
            ns in-ns ns-name ns-map ns-unmap ns-publics ns-imports ns-interns ns-refers
            ns-aliases ns-unalias ns-resolve resolve require use refer refer-clojure
            alias all-ns find-ns create-ns remove-ns the-ns loaded-libs intern import
            serialized-require requiring-resolve
     ;; forms the host compiles specially
            case locking io! sync dosync
     ;; the Inst protocol (its Date extension is skipped with the other JVM forms)
            Inst
     ;; concurrency
            future future-call future-done? future-cancel future-cancelled? future?
            pmap pcalls pvalues seque promise deliver
            agent send send-off send-via set-agent-send-executor!
            set-agent-send-off-executor! release-pending-sends agent-error restart-agent
            set-error-handler! error-handler set-error-mode! error-mode agent-errors
            clear-agent-errors shutdown-agents await await-for await1 *agent*
            ref ref-set alter commute ensure ref-history-count ref-min-history
            ref-max-history ref-history
     ;; host-specific value types
            shuffle bigint biginteger bigdec with-precision
            uuid? random-uuid parse-uuid inst? inst-ms
            eduction iteration
            Throwable->map StackTraceElement->vec
            tap> add-tap remove-tap]))

;; Skipped: the JVM itself, or private machinery behind a :host name.
(def skip-names
     (set '[proxy proxy-super init-proxy update-proxy proxy-mappings proxy-name
            proxy-call-with-super get-proxy-class construct-proxy
            gen-class gen-interface definterface deftype defrecord reify extend
            extend-type extend-protocol defprotocol satisfies? extends? extenders
            find-protocol-impl find-protocol-method -cache-protocol-fn -reset-methods
            bean
            add-classpath with-loading-context
            load-one load-all load-lib load-libs prefix-spec? libspec? lib-spec throw-if
            check-cyclic-dependency root-resource root-directory *loading-verbosely*
            *pending-paths* load-data-readers data-readers load-data-reader-file
            data-reader-urls data-reader-var
            pr-on print-dup print-method
            tap-loop tapset tapq
            is-annotation? is-runtime-annotation? descriptor add-annotation
            process-annotation add-annotations
            Eduction file-seq resultset-seq enumeration-seq
            vector-of
            *clojure-version*]))

(def chunk-names
     "core.clj's chunk fns: taken from the host when its adapter has :chunks, so
   the stock chunked branches (map, filter, concat, doseq, for) build and walk
   the host's own chunks with its own fns."
     (set '[chunked-seq? chunk-first chunk-rest chunk-next chunk-buffer chunk-append chunk chunk-cons]))

(def host-only-names
     "Of host-names, those whose stock definition cannot work without the JVM:
   when the host lacks one, it is skipped rather than reported as an error."
     (set '[pr prn print println newline flush pr-str prn-str print-str println-str
            pr-on print-simple print-ctor print-sequential print-map print-meta
            read read-line read+string load-reader load-string load-file load compile
            slurp spit line-seq with-out-str with-in-str
            *out* *in* *err*
            re-pattern
            ns in-ns ns-map ns-unmap ns-publics ns-imports ns-interns ns-refers ns-aliases
            ns-unalias ns-resolve resolve require use refer refer-clojure alias all-ns
            find-ns create-ns remove-ns the-ns loaded-libs intern import
            serialized-require requiring-resolve
            case locking
     ;; its stock def needs defprotocol, which the host gives only after core.clj
     ;; (and inst? and inst-ms need it)
            Inst inst? inst-ms
            future future-call future-done? future-cancel future-cancelled? future?
            pmap pcalls pvalues seque promise deliver
            agent send send-off send-via set-agent-send-executor!
            set-agent-send-off-executor! release-pending-sends agent-error restart-agent
            set-error-handler! error-handler set-error-mode! error-mode agent-errors
            clear-agent-errors shutdown-agents await await-for await1
            shuffle bigint biginteger bigdec with-precision random-uuid parse-uuid
            eduction iteration
            tap> add-tap remove-tap
            struct-map struct create-struct defstruct accessor
     ;; clojure.instant's and clojure.uuid's readers
            default-data-readers]))

(def thread-names
     "Of host-only-names, the concurrency names Eve's thread layer
   (cohost.thread) fills after the boot for a host whose core lacks
   them: future/agent/pmap and their machinery, backed by the layer
   behind the Java shim. `seque` is host-only but not filled -- it
   stays skipped on such a host. Boot interns cohost.thread/core-impls
   and core-macros under exactly these names (see
   cohost.boot/install-thread-layer!)."
     (set '[future future-call future-done? future-cancel future-cancelled? future?
            pmap pcalls pvalues promise deliver
            agent send send-off send-via set-agent-send-executor!
            set-agent-send-off-executor! release-pending-sends agent-error
            restart-agent set-error-handler! error-handler set-error-mode!
            error-mode agent-errors clear-agent-errors shutdown-agents
            await await-for await1]))

;; A host whose adapter gives :io-handles (its own print/read machinery
;; takes only its native handles: let-go) has cohost own the print and
;; read stack instead of taking the host's.
(def io-owned-stock
     "With :io-handles, these boot as core.clj writes them (over the shim and
   the io layer) rather than being taken from the host: the pr family over
   pr-on and print-method, newline/flush over *out*'s members, the string
   forms over StringWriter."
     (set '[pr prn print println newline flush printf pr-str prn-str print-str println-str
            pr-on print-method print-dup with-out-str with-in-str]))

(def io-owned-lacks
     "With :io-handles, these are treated as lacked by the host: cohost.core
   gets its own *out*/*err*/*in* (the prelude's defs; roots set to io-layer
   values over the host's leaves after the boot), and the shim fills the
   readers (install-shim-layer!)."
     (set '[*out* *err* *in* read read-line read+string]))

(def io-owned-print-forms
     "With :io-handles, the forms of clojure/core_print.clj the boot evaluates
   (in cohost.core, after core.clj): print-method's and print-dup's methods
   and prefers, and the private helpers they call. The file's dynamic vars
   stay the host's (host-names); its Throwable->map, StackTraceElement->vec
   and PrintWriter-on stay the shim's / host's as before."
     (set '[print-sequential print-meta print-simple print-ctor print-tagged-object
            print-object print-prefix-map print-map strip-ns lift-ns
            char-escape-string char-name-string primitives-classnames deref-as-map
            print-throwable]))

(defn decide-print-form
      "-> {:action :eval|:skip :name} for a top-level form of core_print.clj on
   a host with :io-handles (see io-owned-print-forms)."
      [form]
      (let [head (form-head form)
            nm (form-name form)
            mm (when (seq? form) (second form))]
           (cond
            (and (contains? #{'defmethod 'prefer-method} head) (contains? #{'print-method 'print-dup} mm))
            {:action :eval :name (symbol (str head " " mm))}
            (and nm (contains? io-owned-print-forms nm)) {:action :eval :name nm}
            :else {:action :skip :name (or nm head)})))

(def companion-names
     "clojure.core names that the booted forms do not define: they come from the
   companion files (core_deftype, core_proxy, genclass, gvec, core_print)
   that the boot does not load, or from a deftype of core.clj's that the boot
   skips (->Eduction). Those with a plain-Clojure definition (companion-stock)
   are booted from it; the rest are taken from the host at the end of the boot
   when it has them, skipped otherwise."
     (set '[deftype defrecord defprotocol reify extend extend-type extend-protocol
            extenders extends? satisfies? record? definterface munge
            proxy proxy-super init-proxy update-proxy get-proxy-class construct-proxy
            proxy-mappings proxy-name bean gen-class gen-interface vector-of
            print-method print-dup Throwable->map StackTraceElement->vec
            char-escape-string char-name-string inst-ms* namespace-munge hash-combine
            ->Eduction]))

(def companion-stock
     "Companion names whose stock definition is plain Clojure over the shim:
   booted from that file, as core.clj's own forms are (a host's own may differ:
   ClojureScript's hash-combine takes a hash, the JVM's hashes its argument)."
     '{namespace-munge "clojure/core_deftype.clj"
       hash-combine "clojure/core_deftype.clj"})

(def runtime-vars
     "Dynamic vars the JVM runtime creates before core.clj loads (core.clj only
   documents them with add-doc-and-meta). The boot refers each from the host
   when it has one, else defines it with this default."
     '{*ns* nil *in* nil *out* nil *err* nil *file* nil *command-line-args* nil
       *warn-on-reflection* false *compile-path* nil *compile-files* false
       *unchecked-math* false *compiler-options* nil *flush-on-newline* true
       *print-meta* false *print-dup* false *print-readably* true *print-length* nil
       *print-level* nil *print-namespace-maps* true *verbose-defrecords* false
       *math-context* nil *source-path* nil *read-eval* true *data-readers* {}
       *default-data-reader-fn* nil *assert* true *fn-loader* nil
       *use-context-classloader* true *allow-unresolved-vars* false
       *suppress-read* false *agent* nil *e nil *1 nil *2 nil *3 nil
       *loaded-libs* nil *pending-paths* nil *loading-verbosely* false})

(def replacements
     {'*clojure-version*
      '(def ^:dynamic *clojure-version*
            {:major 1 :minor 12 :incremental 0 :qualifier nil})
   ;; the class tokens are symbols; the shim knows their hierarchy
      'cast
      '(defn cast
             "Throws a ClassCastException if x is not a c, else returns x."
             {:added "1.0"}
             [c x]
             (if (cohost.shim/Util-identical x nil)
                 x
                 (if (cohost.shim/instance-of? c x)
                     x
                     (throw (cohost.shim/new-ClassCastException (cohost.shim/cast-message c x))))))
   ;; core.clj reads this off clojure/version.properties inside its one top-level let
      'version-let
      '(def ^:dynamic *clojure-version*
            {:major 1 :minor 12 :incremental 0 :qualifier nil})
      'bases
      '(defn bases
             "Returns the immediate superclass and direct interfaces of c, if any"
             {:added "1.0"}
             [c]
             (when c (seq (cohost.shim/class-bases c))))
      'supers
      '(defn supers
             "Returns the immediate and indirect superclasses and interfaces of c, if any"
             {:added "1.0"}
             [class]
             (when class (not-empty (set (cohost.shim/class-supers class)))))
   ;; stock realized? casts its argument to IPending ahead of the call,
   ;; a cast no stand-in value survives; the shim's -isRealized answers
   ;; by predicate instead, for host pendings and the thread layer's
   ;; futures and promises alike
      'realized?
      '(defn realized?
             "Tests if a delay, future, or promise has been realized"
             {:added "1.4"}
             [x]
             (cohost.shim/-isRealized x))})

;; Top-level forms that are not defs and are the JVM's: the one `let` (reads
;; clojure/version.properties), the one `try` (data readers), the imports, the
;; `load`s of the companion files (the boot drives those explicitly).
(def skip-heads
     (set '[import load require when-class deftype defprotocol extend-protocol
            defmulti defmethod try let ns in-ns]))

(def host-overrides
     "Per-host adjustments to the lists above, keyed by the adapter's :name:
   :host and :stock add to and take from host-names, :skip adds to
   skip-names, :special names the host's special forms that have no var
   (skipped: nothing to take from the host, and no stock definition over one)."
     {:cljs {:host (set '[defmacro])        ; only cljs.core's defmacro can make a macro there
             :skip (set '[])}
   ;; squint: the definline array-casters' expansions send the boot's
   ;; rewrite walk into a non-terminating seq step (convicted by CDP
   ;; stack samples at (definline booleans ...) — split-with under a
   ;; macro-apply cycle, no form completes); their bodies are
   ;; clojure.lang host calls that cannot run on squint in any case,
   ;; so they are skipped rather than walked.
      :squint {:skip (set '[booleans bytes chars shorts floats ints doubles longs])}
   ;; basilisp's format is Python's % operator: core.clj's format (and printf
   ;; over it) run String/format, the shim's java.util.Formatter
      :lpy {:stock (set '[format printf])}
   ;; ClojureWasm's str of an exception writes the class's short name; core.clj's
   ;; str reaches the shim's toString, which writes the JVM's
      :cljw {:stock (set '[str])}
   ;; Phel's str of an exception is PHP's (the class, the message, the trace);
   ;; core.clj's str reaches the shim's toString. Phel has no locking: core.clj's
   ;; (monitor-enter/-exit, the shim's no-ops; Phel runs one thread) boots, so
   ;; clojure.spec.gen.alpha's dynaload defines
      :phel {:stock (set '[str locking])}
   ;; let-go's atom takes its validator when it is made (:validator), and has
   ;; no set-validator! to give one after: core.clj's atom sets it after
      :lg {:host (set '[atom])}
   ;; clojurust's evaluator has these as special forms, which a qualified name
   ;; does not reach and no macro of the name can replace; of them, fn and
   ;; defn- have no var in its core to take (:special: neither taken nor booted)
      :cljrs {:host (set '[let loop defn defmacro defonce and or letfn binding])
              :special (set '[fn defn-])}})

(def setmacro-defined
     "Bootstrap fns core.clj turns into macros with `(. (var x) (setMacro))`."
     (set '[defn defmacro]))

(def self-referential-macros
     "Macros whose redefinition expands through themselves: `(defmacro fn ...)`
   becomes `(def fn (fn fn ...))`. A host that drops a var's macro status when
   it sees the `def` (basilisp) then compiles that init as a call. These are
   emitted as a direct `(def ^:macro name (fn* name ([&form &env & params] ...)))`."
     (set '[fn]))

(defn direct-macro-def
      "`(defmacro name doc? attr? params body...)` or `(defmacro name doc? attr?
   ([params] body...)+)` -> `(def ^{:macro true ...} name (fn* name (...)))`,
   what stock defmacro + defn expand to, without going through either."
      [[_ nm & more]]
  ;; squint: the host reader returns string literals as String
  ;; objects (its string? is a typeof check and misses them), so
  ;; the docstring test needs the object arm there.
      (let [[doc more] (if #?(:squint (or (string? (first more)) (instance? js/String (first more)))
                              :default (string? (first more)))
                           [(first more) (rest more)] [nil more])
            [attrs more] (if (map? (first more)) [(first more) (rest more)] [{} more])
            arities (if (vector? (first more)) [more] more)
            arities' (map (fn [[params & body]]
                              (apply list (into ['&form '&env] params) body))
                          arities)
            m (merge attrs {:macro true :arglists (list 'quote (apply list (map first arities)))} (when doc {:doc doc}))]
           (list 'def #?(:squint nm :default (with-meta nm m)) (apply list 'fn* nm arities'))))

(def ^:private fn-heads (set '[fn* fn]))

(defn adapt-macro-def
      "On hosts where only a defmacro form makes a macro (style :defmacro), turn the
   bootstrap `(def ^:macro x (fn* x [&form &env & args] body))` defs, and the
   `(def defn (fn defn [&form &env ...] ...))` + `(. (var defn) (setMacro))`
   pairs, into defmacro forms; defmacro binds &form and &env itself."
      [form style]
      (if (not= style :defmacro)
          form
          (let [[h nm init] (if (seq? form) form [nil nil nil])]
               (cond
                (and (= h 'def) (symbol? nm)
                     (or (:macro (meta nm)) (contains? setmacro-defined nm))
                     (seq? init) (contains? fn-heads (first init)))
        ;; `(fn* name? [params] body...)` or `(fn* name? ([params] body...)+)`:
        ;; each arity drops its leading &form &env, which defmacro adds back.
                (let [more (rest init)
                      more (if (symbol? (first more)) (rest more) more)
                      arities (if (vector? (first more)) [more] more)
                      arities' (map (fn [[params & body]] (apply list (vec (drop 2 params)) body)) arities)]
                     (apply list 'defmacro #?(:squint nm :default (vary-meta nm dissoc :macro)) arities'))
                (and (= h '.) (seq? nm) (= 'var (first nm)) (= '(setMacro) init)) nil
                :else form))))

(defn lists
      "{:host-names :skip-names :special-names} for `host` (the adapter's :name): the lists above
   with its host-overrides applied, chunk-names when chunks? (the adapter
   has :chunks), and io-owned-stock booted as stock when owns-io? (the
   adapter has :io-handles). A boot computes them once."
      ([host] (lists host false false))
      ([host chunks?] (lists host chunks? false))
      ([host chunks? owns-io?]
       (let [ov (get host-overrides host)
             stock (into (set (:stock ov)) (when owns-io? io-owned-stock))]
            {:host-names (cond-> (apply disj (into host-names (:host ov)) stock)
                                 chunks? (into chunk-names))
             :skip-names (apply disj (into skip-names (:skip ov)) stock)
             :stock-names stock
             :special-names (or (:special ov) #{})})))

(defn decide
      "-> {:action :eval|:skip|:host|:replace, :name sym, :reason kw, :replacement form}
   `host` is the adapter's :name, for host-overrides; `ls` is (lists host)."
      ([form host] (decide form host (lists host)))
      ([form _host ls]
       (let [head (form-head form)
             nm (form-name form)
             host-names (:host-names ls)
             skip-names (:skip-names ls)]
            (cond
             (nil? form) {:action :skip :reason :empty}
             (= head 'let) {:action :replace :name '*clojure-version* :replacement (get replacements 'version-let)}
             (and nm (contains? replacements nm)) {:action :replace :name nm :replacement (get replacements nm)}
             (and nm (contains? skip-names nm)) {:action :skip :name nm :reason :jvm-only}
       ;; a special form of the host's evaluator with no var: the host has it,
       ;; and nothing of the name can be defined over it
             (and nm (contains? (:special-names ls) nm)) {:action :skip :name nm :reason :host-special-form}
       ;; a name the host's overrides boot as stock, whatever its head
       ;; (print-method's defmulti on a host with :io-handles); a defmethod
       ;; of it here is core.clj's Eduction one, whose deftype is skipped
             (and nm (contains? (:stock-names ls) nm) (not (contains? #{'alter-meta! 'defmethod} head))) {:action :eval :name nm}
             (and nm (contains? host-names nm))
             (if (= head 'alter-meta!)
                 {:action :skip :name nm :reason :host-var}
                 {:action :host :name nm})
             (contains? skip-heads head) {:action :skip :name (or nm head) :reason :skip-head}
             (and (= head 'alter-meta!) (nil? nm)) {:action :skip :name head :reason :jvm-only}
             :else {:action :eval :name (or nm head)}))))

;; ---------------------------------------------------------------------------
;; libraries other than clojure.core

(def lib-overrides
     "Per-library adjustments, keyed by the library's own name:
   {lib {:skip #{name ...} :replace {name form}}}. A library's forms evaluate
   as written unless named here; core.clj's lists above do not apply."
     {})

(defn decide-lib
      "-> {:action :eval|:skip|:replace, :name sym, :replacement form} for a
   top-level form of library `lib`."
      [form lib]
      (let [nm (or (form-name form) (form-head form))
            {:keys [skip replace]} (get lib-overrides lib)]
           (cond
            (nil? form) {:action :skip :name nil}
            (contains? skip nm) {:action :skip :name nm}
            (contains? replace nm) {:action :replace :name nm :replacement (get replace nm)}
            :else {:action :eval :name nm})))

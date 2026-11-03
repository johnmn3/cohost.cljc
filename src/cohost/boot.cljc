;; Boots the stock clojure/core.clj inside the host clojure-like.
;;
;; For each top-level form of core.clj, in order: ask cohost.policy what to do;
;; run the form through cohost.rewrite (JVM interop -> cohost.shim); eval it with
;; *ns* bound to `cohost.core`, a namespace that refers nothing from the host's
;; core, so every name core.clj defines is the stock one. After every form,
;; each newly defined macro is wrapped so its expansions are rewritten too (a
;; stock macro emits interop into its caller). Names the policy marks :host are
;; referred from the host's core -- they are the host's vars, not copies.
;;
;; The result is a report: how many forms booted, which were taken from the
;; host, which were skipped, and every form that failed with its error.
(ns cohost.boot
  (:require [cohost.adapter :as host]
            [cohost.rewrite :as rw]
            [cohost.shim :as shim]
            [cohost.policy :as policy]
            [cohost.scan :as scan]))

(def core-ns "The namespace the stock core.clj boots into." 'cohost.core)
(def user-ns 'cohost.user)

(defonce state (atom {:ctx nil :report nil}))

(defn ctx [] (:ctx @state))

;; Phase timers for the boot profile. Off unless boot! is called with
;; {:profile? true}: the off path does not call :nano-time and does not
;; record forms. Every host's boot report stays the numbers it prints today.
;; The predicate is not named with a trailing ? because jank rejects a
;; zero-arg call of such a name as boolean.
(defn- profiling [] (some? (:profile @state)))

(def ^:private prof+
  ;; jank's + wraps at 32 bits, so the timers sum with +'; basilisp has no +'
  ;; (its + is Python's unbounded int)
  #?(:lpy + :default +'))

(defn- profile-add! [k dt]
  (when (and (profiling) dt)
    (let [bucket (or (:profile-bucket @state) :core)]
      (swap! (:profile @state) update-in [:phases bucket k] (fn [n] (prof+ (or n 0) dt))))))

(defn- profile-form! [ns-sym form name rewrite-dt eval-dt]
  (when (and (profiling) (or (and rewrite-dt (pos? rewrite-dt)) (and eval-dt (pos? eval-dt))))
    (let [h (when (seq? form) (first form))]
      (swap! (:profile @state) update :forms conj
             {:ns (str ns-sym)
              :head (if (symbol? h) (str h) "-")
              :name (str (or name "-"))
              :rewrite (or rewrite-dt 0)
              :eval (or eval-dt 0)
              :bucket (or (:profile-bucket @state) :core)}))))

(defn- prof
  "Call thunk. When profiling, add its :nano-time to phase key k."
  [k thunk]
  (if-not (profiling)
    (thunk)
    (let [t0 (host/nano-time)
          v (thunk)
          t1 (host/nano-time)]
      (profile-add! k (- t1 t0))
      v)))

(defn- read-prof
  "read-forms. When profiling, the time between forms (the read) is :read;
   the callback's time is not."
  ([src cb] (read-prof src cb nil))
  ([src cb path]
   (if-not (profiling)
     (host/read-forms src cb path)
     (let [mark (volatile! (host/nano-time))]
       (host/read-forms src
                        (fn [form]
                          (let [now (host/nano-time)]
                            (profile-add! :read (- now @mark))
                            (cb form)
                            (vreset! mark (host/nano-time))))
                        path)
       (profile-add! :read (- (host/nano-time) @mark))))))

(defn- short-form [form]
  (let [s (pr-str form)]
    (if (> (count s) 200) (str (subs s 0 200) " ...") s)))

(defn- error-message [e] (host/error-message e))

;; ---------------------------------------------------------------------------
;; macros: rewrite what they emit

(defn wrap-macro [ctx f]
  ;; args: &form and &env, then the macro's own. basilisp passes &env first:
  ;; a wrapped macro (core.clj's, or one its defmacro made) takes [&form &env
  ;; ...], so it gets them in that order (&form is a list, &env a map or nil).
  ;; The expansion is rewritten knowing the locals of &env: a call of a local
  ;; named like a core fn is no call of the core fn.
  (fn [& args] (let [[a b & more] args
                     [form env] (if (and (map? a) (not (map? b))) [b a] [a b])
                     out (apply f form env more)]
                 (if host/*raw-macro-output* out (host/code-form (rw/rewrite ctx out {:locals (host/env-locals env)}))))))

(defn ^:export rewrite-output
  "Rewrite a macro's expansion with the current boot's ctx. Hosts whose macro
   fns cannot be replaced after definition (:macro-wrap-style :source) get
   this call compiled into every stock macro's body instead."
  ([form] (rewrite-output form nil))
  ([form env]
   ;; env: the macro's &env, whose locals the expansion's rewrite knows
   (if-let [c (and (not host/*raw-macro-output*) (ctx))] (host/code-form (rw/rewrite c form {:locals (host/env-locals env)})) form)))

(defn- wrap-defmacro-source
  "`(defmacro name doc? attr? params body...)` -> the same macro whose every
   arity returns (rewrite-output <body>)."
  [form]
  (if-not (and (seq? form) (= 'defmacro (first form)))
    form
    (let [[_ nm & more] form
          [doc more] (if (string? (first more)) [(first more) (rest more)] [nil more])
          [attrs more] (if (map? (first more)) [(first more) (rest more)] [nil more])
          arities (if (vector? (first more)) [more] more)
          arities' (map (fn [[params & body]]
                          ;; &env: the locals the expansion's rewrite knows
                          (list params (list (host/rewrite-output-ref) (apply list 'do body) '&env)))
                        arities)]
      (apply list 'defmacro nm (concat (when doc [doc]) (when attrs [attrs])
                                       (if (vector? (first more)) (first arities') arities'))))))

(def ^:private defn-heads (set '[defn defn-]))
(def ^:private inline-def-heads (set '[defn defn- def]))

(defn- may-make-macro?
  "Can evaluating form create a macro or change one? A defmacro; a def of a
   `^:macro` name or of one core.clj turns into a macro with setMacro; and
   any form that is not a def at all (`(. (var x) (setMacro))`, alter-meta!,
   a do, a macro call). Other defs (defn, defmulti, defprotocol ...) cannot,
   so the scan after them would find nothing."
  [form]
  (or (not (seq? form))
      (let [h (first form)]
        (or (not (contains? policy/def-heads h))
            (= 'defmacro h)
            (let [nm (second form)
                  ;; defn's attr-maps: after the docstring, and at the end
                  more (nnext form)
                  more (if (string? (first more)) (rest more) more)]
              (and (symbol? nm)
                   (or (:macro (meta nm)) (contains? policy/setmacro-defined nm)
                       (and (contains? defn-heads h)
                            (or (and (map? (first more)) (:macro (first more)))
                                (and (map? (last more)) (:macro (last more))))))))))))

;; Record mode for the AOT snapshot. nil unless boot! was given {:record? true}.
;; Defined above the first use: jank's analyzer does not resolve a var whose
;; def is later in the file. The off path is this nil check. :snapshot-emit
;; stays nil, so nothing is called unless a task installs a fn.
(def snapshot-log (atom nil))

(defn snapshot-events
  "The vector of events from the current {:record? true} boot, or nil."
  []
  @snapshot-log)

(defn snapshot-stop!
  "End record mode: -> the events recorded, and record nothing more."
  []
  (let [ev @snapshot-log] (reset! snapshot-log nil) ev))

(defn snapshot-start!
  "Record mode from here on, after a boot (a project's snapshot records what
   it loads on top of a booted, or snapshot-booted, core)."
  []
  (reset! snapshot-log []))

(def ^:private snap-drop-meta
  "Reader metadata the host adds again when it reads the snapshot's source."
  #{:line :column :end-line :end-column :file :source-span :jank/source})

(defn- snap-encode
  "form with every metadata map made data: {:cohost.snap/meta m
   :cohost.snap/val x}, since a host's printer may drop metadata (jank's
   ignores *print-meta*). The emitter turns it back into reader metadata."
  [x]
  (let [enc (fn [y] (cond (map? y) (into {} (map (fn [[k v]] [(snap-encode k) (snap-encode v)]) y))
                          (vector? y) (mapv snap-encode y)
                          (set? y) (set (map snap-encode y))
                          (seq? y) (apply list (map snap-encode y))
                          :else y))
        m (when (or (symbol? x) (coll? x)) (meta x))
        m (when m (let [m' (apply dissoc m snap-drop-meta)] (when (seq m') m')))
        v (enc x)]
    (if m {:cohost.snap/meta (snap-encode m) :cohost.snap/val v} v)))

(declare snap!)
(defn- snap-declare!
  "A form that failed may still have interned its name (jank's analyzer
   interns a def's var before the body fails): later forms refer to that
   var, so the snapshot declares it too."
  [ns-sym nm]
  (when (and @snapshot-log (symbol? nm) (nil? (namespace nm))
             (host/try-all (fn [] (some? (host/interned-var ns-sym nm))) (fn [_] false)))
    (snap! {:op :declare :ns ns-sym :sym nm})))

(def ^:private snap-load-scoped
  "The vars a library's load binds (with-load-bindings): a top-level set! of
   one changes nothing after the load, and a replay outside that binding
   could not set! it, so it is not recorded."
  #{'*warn-on-reflection* '*unchecked-math*})

(defn- snap-skip-form?
  [form]
  (and (seq? form) (= 'set! (first form)) (symbol? (second form))
       (contains? snap-load-scoped (symbol (name (second form))))))

(defn- snap-module
  "The namespace of the snapshot module an event belongs to: the library
   being loaded, else the core."
  []
  (or (:loading @state) 'clojure.core))

(defn- snap! [event]
  (when @snapshot-log
    (let [event (assoc event :module (snap-module))]
      (swap! snapshot-log conj event)
      (when-let [f (host/snapshot-emit)]
        (when (fn? f) (f event))))))

(defn- scan-macros!
  "Promote `^:macro` defs the host did not treat as macros, and wrap every
   not-yet-wrapped macro of ns-sym so its output goes through the codemod."
  ([ctx] (scan-macros! ctx core-ns))
  ([ctx ns-sym]
   (prof :scan
     (fn []
       (doseq [[sym v] (host/ns-interns-map ns-sym)]
         (let [m (host/var-meta v)
               mark? (and (:macro m) (not (host/macro-var? v)))
               _ (when mark? (host/mark-macro! v))
               ;; after the marking: a macro marked here is wrapped in the
               ;; same pass (the next form may call it)
               wrap? (and (host/macro-var? v) (not (:cohost/wrapped m)))]
           (when wrap?
             (when (= :root (host/macro-wrap-style))
               (host/set-var-root! v (fn [f] (wrap-macro ctx f))))
             (host/alter-var-meta! v assoc :cohost/wrapped true))
           (when (and @snapshot-log (or mark? wrap?))
             (snap! {:op :scan :ns ns-sym :sym sym}))))))))

;; ---------------------------------------------------------------------------
;; eval

(defn- host-aliases
  "{shim name -> the host core's symbol} for the shim statics whose value is
   the host core's own fn (RT-first is first, Numbers-add is +): the codemod
   names the host's fn, which the host's compiler may inline, rather than the
   shim var that holds it. Public, non-macro host vars only; the symbol is
   marked so the codemod does not read it as the stock core's. Where several
   host names hold the one fn (jolt's + is its +'), the shortest name wins:
   the plain name is the one a host compiler inlines."
  [shim-names]
  (let [core (host/core-ns-sym)
        ;; where the host's own compiler applies :inline (the JVM), a host fn
        ;; with one is not named: its expansion would read *unchecked-math* where
        ;; the static (Numbers.add) is always checked
        own-inline? (not (host/rewrite-inline?))
        better? (fn [a b] (let [na (name a) nb (name b)]
                            (or (< (count na) (count nb))
                                (and (= (count na) (count nb)) (neg? (compare na nb))))))
        by-fn (reduce (fn [m [sym h]]
                        (let [v (host/try-all (fn [] (when-not (or (host/macro-var? h) (:private (host/var-meta h))
                                                                   (and own-inline? (:inline (host/var-meta h))))
                                                       (host/var-root h))))]
                          (if (and (fn? v) (or (not (contains? m v)) (better? sym (get m v)))) (assoc m v sym) m)))
                      ;; seq: Phel's reduce over a map walks its values
                      {} (seq (host/ns-interns-map core)))
        vars #?(:cljs nil :default (host/ns-interns-map 'cohost.shim))]
    (into {} (keep (fn [s]
                     (let [nm (str s)]
                       ;; statics, not instance methods (-m) or their dispatchers (dot$m)
                       (when (and (not= "-" (subs nm 0 1)) (not= "dot$" (subs nm 0 (min 4 (count nm)))))
                         (let [v (shim/shim-value vars nm)
                               hs (when (fn? v) (get by-fn v))]
                           (when hs
                             [nm (with-meta (symbol (str core) (str hs)) {:cohost/host true})])))))
                   shim-names))))

(defn- head-resolver
  "(fn [sym]) -> the var sym names in the namespace being compiled, or nil:
   the codemod tells a core fn it may inline, and a macro whose expansion it
   rewrites, from a namespace's own name."
  []
  (let [resolve* (or (get (host/core-extras) 'ns-resolve)
                     (some-> (host/resolve-core 'ns-resolve) host/var-root))]
    (when resolve*
      (fn [sym]
        (host/try-all (fn [] (let [v (resolve* (host/current-ns) sym)] (when (host/var?* v) v))))))))

(defn- var-meta*
  "A resolved var's metadata (a ClojureScript Var object carries its own)."
  [v]
  (merge (host/try-all (fn [] (host/var-meta v)))
         (meta v)))

(defn- stock-name
  "(fn [v]) -> v's name when v is a var of the booted core, else nil."
  []
  (let [core (name core-ns)]
    (fn [v] (when (= core (let [x (host/var-ns v)] (str (if (host/namespace? x) (host/ns-name* x) x))))
              (symbol (name (host/var-sym v)))))))

(defn- rewritten-macro?
  "Is v a macro whose expansion the codemod rewrites: one cohost wrapped
   (stock or user), not the host's own? Its arguments are walked without
   inlining, which its expansion's rewrite applies."
  [v]
  (let [m (var-meta* v)]
    (boolean (and (or (:macro m) (host/macro-var? v)) (:cohost/wrapped m) (not (:cohost/host m))))))

(defn- inline-fn
  "On a host whose compiler ignores :inline: (fn [v arity]) -> the :inline fn
   of the core fn v, when it covers the arity; else nil."
  []
  (when (host/rewrite-inline?)
    (let [core-name (stock-name)]
      (fn [v n]
        (when-let [nm (core-name v)]
          (let [m (var-meta* v)
                [f ar] (if (fn? (:inline m))
                         [(:inline m) (:inline-arities m)]
                         (get (:inlines @state) nm))]
            (when (and (fn? f) (or (nil? ar) (ar n))) f)))))))

(defn make-ctx
  "The codemod's context for this host: which shim vars exist, the host's
   catch-all, its compile-style facts, and the per-namespace facts (current
   namespace, imports, renamed stock libraries, extend-position types)."
  []
  (let [shim-names (let [names (host/ns-public-names 'cohost.shim)
                         ;; a stub that only throws is no shim where the
                         ;; host has the real thing
                         names (if (host/interop-passthrough?)
                                 (let [vs (host/ns-interns-map 'cohost.shim)]
                                   (into #{} (remove #(shim/stub? (some-> (get vs %) host/var-root))) names))
                                 (set names))]
                     ;; a prebuilt dispatcher per instance method: dot$m, and a
                     ;; checkcast per checked class: cast$token
                     (-> names
                         (into (shim/install-dots!
                                (filter #(let [s (str %)] (and (= "-" (subs s 0 1)) (> (count s) 1))) names)))
                         (into (shim/install-casts!
                                (map (fn [c] [(rw/cast-name c) c]) (distinct (vals rw/checked-hints)))))))]
  (rw/make-ctx {:shim-names shim-names
                :host-aliases (when (host/host-aliases?) (host-aliases shim-names))
                :instance-fn (if-not @snapshot-log
                               shim/install-is!
                               (fn [c]
                                 (let [d (shim/install-is! c)]
                                   (snap! {:op :is :class c})
                                   d)))
                :missing-dot (if-not @snapshot-log
                               shim/install-missing-dot!
                               (fn [m]
                                 (let [d (shim/install-missing-dot! m)]
                                   (snap! {:op :dot :method m})
                                   d)))
                :resolve-head (head-resolver)
                :stock-name (stock-name)
                :rewritten-macro? rewritten-macro?
                :inline-fn (inline-fn)
                :catch-all (host/catch-all-sym)
                :try-all host/try-all
                :core-ns (name core-ns)
                :shim-ref (host/shim-ref)
                :loop-variadic-recur? (host/loop-variadic-recur?)
                :named-fn*? (host/named-fn*?)
                :plain-symbol host/plain-symbol
                :special-forms (host/special-forms)
                :binding-style (host/binding-style)
                :vector-rest-args? (host/vector-rest-args?)
                :def-meta (host/def-meta)
                :strip-inline? (host/strip-inline?)
                :current-ns host/current-ns
                :host-type (host/host-type-fn)
                :expand-macro (host/expand-macro-fn)
                :per-arity-impls? (= :per-arity (host/protocol-impl-style))
                :python-methods? (= :python (host/type-method-style))
                :move-methods? (= :protocols (host/type-method-style))
                :cohost-types? (= :cohost-types (host/type-method-style))
                :syntax-quote-source? (host/syntax-quote-source?)
                :loop-rebind-shadow? (host/loop-rebind-shadow?)
                :host-method host/host-method
                :var-form (fn [x] (when (host/var?* x)
                                    (let [n (host/var-ns x) n (if (host/namespace? n) (host/ns-name* n) n)]
                                      (list 'var (symbol (str n) (str (host/var-sym x)))))))
                :passthrough? (host/interop-passthrough?)
                :record? shim/record?*
                :protocol-var? host/protocol-var?
                :interned? (fn [s] (let [n (host/current-ns)]
                                     (boolean (and n (host/try-all (fn [] (some? (host/interned-var n s)))
                                                                   (fn [_] false))))))})))

(declare eval-top)
(declare directive)
(declare directive!)

(defn eval-in
  "Rewrite `form` and eval it with *ns* bound to `ns-sym`. A top-level `do`
   is evaluated one form at a time, as the JVM compiler does, so a macro
   defined in it is a macro for the forms after it; macros defined this way
   are wrapped like the stock ones, so what they emit is rewritten too.
   Top-level `ns`, `in-ns`, `require`, `use`, `import` and `refer-clojure`
   go through cohost's loader (stock libraries in their own namespaces)."
  [ns-sym form]
  (let [c (or (ctx) (let [c (make-ctx)] (swap! state assoc :ctx c) c))]
    (first (eval-top c ns-sym form))))

(defn- eval-in*
  "-> [value namespace-after]. lib? (a library's top-level form): scan for new
   macros only after a form that can make one; the loader scans once more
   when the library is done. Otherwise after every form."
  [c ns-sym form lib?]
  (host/with-ns ns-sym
    (fn [] (if-let [d (directive form)]
             (let [r (directive! d)]
               (snap! {:op :directive :ns ns-sym :d d})
               r)
             (let [form' (if (= :source (host/macro-wrap-style)) (wrap-defmacro-source form) form)
                   v (if-not (profiling)
                       (let [rewritten (rw/rewrite c form')
                             v (host/eval-form rewritten)]
                         (when (and @snapshot-log (not (snap-skip-form? form'))) (snap! {:op :form :ns ns-sym :form (snap-encode rewritten)}))
                         v)
                       (let [t0 (host/nano-time)
                             rewritten (rw/rewrite c form')
                             t1 (host/nano-time)
                             v (host/eval-form rewritten)
                             t2 (host/nano-time)]
                         (when (and @snapshot-log (not (snap-skip-form? form'))) (snap! {:op :form :ns ns-sym :form (snap-encode rewritten)}))
                         (profile-add! :rewrite (- t1 t0))
                         (profile-add! :eval (- t2 t1))
                         (profile-form! ns-sym form (or (policy/form-name form) (when (seq? form) (first form)))
                                        (- t1 t0) (- t2 t1))
                         v))]
               (host/after-eval form' ns-sym)
               (when (or (not lib?) (may-make-macro? form))
                 (scan-macros! c ns-sym))
               [v (host/current-ns)])))))

(def ^:private host-def-heads
  "The host's own macros that intern vars (the codemod never sees their defs)."
  #{"defprotocol" "deftype" "defrecord"})

(defn- mark-host-defs!
  "On a host whose vars compile to direct references unless marked
   (:def-meta, basilisp), the vars a host macro just interned get the mark too,
   so with-redefs of a protocol method reaches callers compiled after it."
  [ns-sym before]
  (when-let [dm (host/def-meta)]
    (doseq [[sym h] (host/ns-interns-map ns-sym) :when (not (contains? before sym))]
      (host/alter-var-meta! h merge dm))))

(defn- eval-top
  "-> [value namespace-after]; a `do` one form at a time, each in the
   namespace the one before it left."
  ([c ns-sym form] (eval-top c ns-sym form false))
  ([c ns-sym form lib?]
   (if (and (seq? form) (= 'do (first form)) (next form))
     (loop [ns ns-sym fs (seq (rest form)) v nil]
       (if fs
         (let [[v ns'] (eval-top c ns (first fs) lib?)] (recur ns' (next fs) v))
         [v ns]))
     (let [before (when (and (host/def-meta) (seq? form) (symbol? (first form))
                             (contains? host-def-heads (name (first form))))
                    (set (keys (host/ns-interns-map ns-sym))))
           r (eval-in* c ns-sym form lib?)]
       (when before (mark-host-defs! ns-sym before))
       r))))

(defn eval-in-core [form] (eval-in core-ns form))

;; ---------------------------------------------------------------------------
;; the boot

(defn- record! [report k entry]
  (swap! report (fn [r] (-> r (update k inc) (update :entries conj (assoc entry :status k))))))

(defn- stock-ns
  "A namespace name as booted code knows it: clojure.core is cohost.core, a
   stock library clojure.X the namespace it booted into."
  [x]
  (if-not (symbol? x)
    x
    (let [n (str x)]
      (if (= n "clojure.core")
        core-ns
        (if-let [m (some-> (ctx) :renames deref (get n))] (symbol m) x)))))

(defn- stock-sym [x]
  (if (and (symbol? x) (namespace x)) (symbol (str (stock-ns (symbol (namespace x)))) (name x)) x))

(def ^:private ns-arg-fns
  "Host fns naming a namespace, or a var by its qualified name: a clojure.*
   name reaches what the stock library booted into."
  {'find-ns :ns 'the-ns :ns 'ns-publics :ns 'ns-interns :ns 'ns-map :ns 'ns-aliases :ns 'ns-refers :ns
   'ns-imports :ns 'ns-resolve :ns 'resolve :sym 'requiring-resolve :sym})

(defn- ns-arg-wrap [nm f]
  (case (get ns-arg-fns nm)
    :ns (fn ([x] (f (stock-ns x)))
          ([x y] (f (stock-ns x) (stock-sym y)))
          ([x y & more] (apply f (stock-ns x) (stock-sym y) (map stock-sym more))))
    :sym (fn ([] (f))
           ([a] (f (stock-sym a)))
           ([a b] (f (stock-sym a) (stock-sym b)))
           ([a b & more] (apply f (stock-sym a) (stock-sym b) (map stock-sym more))))
    f))

(defn- note-host-dynamic!
  "A name cohost.core takes from the host: when the host's var of that name is
   dynamic (hv, or looked up when nil), every namespace that refers the core
   refers that var too (refer-core!)."
  [nm hv]
  (when (some-> (or hv (host/resolve-core nm)) host/dynamic-var?)
    (swap! state update :host-dynamic (fn [v] (if (some #(= nm %) v) v (conj (or v []) nm))))))

(declare take-from-host*)
(defn- take-from-host!
  "Give cohost.core the host core's `nm`. Dynamic vars are referred (the
   var identity matters for binding); everything else is interned as a copy,
   so a stock macro's syntax-quoted `cohost.core/pr-str` resolves. Host
   macros are interned with their macro status and never wrapped. Returns true
   when the host had it; false means the caller boots the stock definition."
  [report nm]
  (let [r (take-from-host* report nm)]
    (when r (snap! {:op :host :sym nm}))
    r))

(defn- take-from-host*
  [report nm]
  (boolean
   (if-let [v (get (host/core-extras) nm)]
     ;; the adapter's own definition of a name the host's core lacks or has
     ;; only at compile time (ClojureScript's resolve is a macro)
     (do (host/intern! core-ns nm (ns-arg-wrap nm v))
         (record! report :host {:name nm})
         (note-host-dynamic! nm nil)
         true)
     (when-let [hv (host/resolve-core nm)]
       (host/try-all
        (fn []
          (if (host/dynamic-var? hv)
            (do (host/refer-from-core! core-ns nm)
                (note-host-dynamic! nm hv)
                (when-let [c (ctx)] (swap! (:host-referred c) conj nm)))
            (let [v (host/intern! core-ns nm (ns-arg-wrap nm (host/var-root hv)))
                  hm (host/var-meta hv)
                  ;; marked first: ClojureScript moves a macro to its $macros
                  ;; namespace, where the meta must land
                  v (if (host/macro-var? hv) (host/mark-macro! v) v)
                  kept (merge (select-keys hm [:doc :arglists :private])
                              {:cohost/host true :cohost/wrapped true})]
              (host/alter-var-meta! v merge kept)))
          (record! report :host {:name nm})
          true)
        (fn [_] false))))))

(defn- prepare
  "Source-level adjustments before the codemod: self-referential macro
   redefinitions become direct defs; on :defmacro-style hosts the bootstrap
   macro defs become defmacro forms."
  [form]
  (let [form (if (and (seq? form) (= 'defmacro (first form))
                      (contains? policy/self-referential-macros (second form)))
               (policy/direct-macro-def form)
               form)
        form (policy/adapt-macro-def form (host/macro-def-style))]
    (if (= :source (host/macro-wrap-style))
      (wrap-defmacro-source form)
      form)))

(defn- inline-forms
  "[name inline-form arities-form] of a defn or def whose metadata carries
   :inline (core.clj's + is `(defn + {:inline (nary-inline 'add ...)} ...)`)."
  [form]
  (when (and (seq? form) (contains? inline-def-heads (first form)) (symbol? (second form)))
    (let [nm (second form)
          more (nnext form)
          more (if (string? (first more)) (rest more) more)
          attrs (merge (meta nm) (when (and (not= 'def (first form)) (map? (first more))) (first more)))]
      (when (contains? attrs :inline)
        [nm (:inline attrs) (:inline-arities attrs)]))))

(defn- capture-inline!
  "For the codemod's inlining (inline-fn): a host whose var metadata holds
   :inline as the unevaluated form (ClojureScript's analyzer, basilisp's
   stripped meta) gets core.clj's :inline fn for nm evaluated once here. The
   first inline def tells which kind of host this is."
  [ctx form]
  (when-let [[nm inl ar] (and (host/rewrite-inline?) (not (false? (:capture-inline? @state))) (inline-forms form))]
    (when (nil? (:capture-inline? @state))
      (swap! state assoc :capture-inline?
             (not (fn? (some-> (host/interned-var core-ns nm) host/var-meta :inline)))))
    (when (:capture-inline? @state)
      (host/try-all (fn [] (let [rewritten (rw/rewrite ctx (list 'vector inl ar))
                                 [f a] (host/eval-form rewritten)]
                             (when (fn? f)
                               (swap! state assoc-in [:inlines nm] [f a])
                               (when @snapshot-log (snap! {:op :inline :sym nm :form (snap-encode rewritten)})))))))))

(defn- eval-form! [ctx report form name status]
  (host/try-all
   (fn []
     (if-not (profiling)
       (let [rewritten (rw/rewrite ctx (prepare form))]
         (host/eval-form rewritten)
         (when @snapshot-log (snap! {:op :form :ns core-ns :form (snap-encode rewritten)}))
         (host/after-eval form core-ns)
         (capture-inline! ctx form))
       (let [t0 (host/nano-time)
             prepared (prepare form)
             rewritten (rw/rewrite ctx prepared)
             t1 (host/nano-time)
             _ (host/eval-form rewritten)
             _ (when @snapshot-log (snap! {:op :form :ns core-ns :form (snap-encode rewritten)}))
             t2 (host/nano-time)
             _ (host/after-eval form core-ns)
             _ (capture-inline! ctx form)
             t3 (host/nano-time)]
         (profile-add! :rewrite (- t1 t0))
         (profile-add! :eval (prof+ (- t2 t1) (- t3 t2)))
         (profile-form! core-ns form name (- t1 t0) (- t2 t1))))
     (record! report status {:name name}))
   (fn [e]
     (snap-declare! core-ns name)
     (record! report :error {:name name :error (error-message e) :form (short-form form)}))))

(def verbose? (atom false))

(def ^:private native-heads
  "The def forms a native can stand in for: fns (a macro has no native)."
  (set '[def defn defn- defonce definline]))

(defn- native-of
  "The adapter's native (adapter :natives) for the def form `form` of library
   lib (a string: \"clojure.core\", \"clojure.string\"): [value host-var-or-nil],
   or nil when there is none."
  [lib form]
  (let [nm (policy/form-name form)]
    (when (and (symbol? nm) (contains? native-heads (policy/form-head form)))
      (when-let [impl (get (host/natives) (symbol lib (name nm)))]
        (if (= :host impl)
          (when-let [hv (and (= lib "clojure.core") (host/resolve-core nm))]
            (when-not (host/macro-var? hv) [(host/var-root hv) hv]))
          [impl nil])))))

(defn- def-form-meta
  "The documentation the stock def form gives its var: :doc, :arglists (type
   hints dropped) and :added, as core.clj writes them."
  [form]
  (let [[h nm & more] form
        [doc more] (if (string? (first more)) [(first more) (rest more)] [nil more])
        [attrs more] (if (and (not= 'def h) (map? (first more))) [(first more) (rest more)] [nil more])
        plain (fn [params] (mapv #(if (symbol? %) (with-meta % nil) %) params))
        arglists (when (contains? defn-heads h)
                   (if (vector? (first more))
                     (list (plain (first more)))
                     (seq (map (comp plain first) (filter #(and (seq? %) (vector? (first %))) more)))))]
    (cond-> (select-keys (merge (meta nm) attrs) [:doc :added])
      doc (assoc :doc doc)
      arglists (assoc :arglists arglists))))

(defn- intern-native!
  "Intern a native as ns-sym/nm in place of the stock def form, marked
   :cohost/native, with the stock definition's documentation (else the host
   var's, for a :host native)."
  [ns-sym nm [v hv] form lib]
  ;; the name without the form's reader metadata (unevaluated: an :arglists
  ;; of (quote ...) the JVM compiler would read as the arglists)
  (let [h (host/intern! ns-sym (symbol (name nm)) v)
        meta-map (merge (when hv (select-keys (host/var-meta hv) [:doc :arglists]))
                        (def-form-meta form)
                        {:cohost/native true :cohost/wrapped true})]
    (host/alter-var-meta! h merge meta-map)
    (snap! {:op :native :ns ns-sym :sym nm :lib lib
            :meta (select-keys meta-map [:doc :arglists :cohost/native :cohost/wrapped])})
    h))

(def ^:private policy-lists
  "This boot's policy lists (policy/lists of the host), computed once per boot."
  (atom nil))

(defn- process-form!
  "Decide, then evaluate or take from the host. New macros are wrapped right
   after a form that can make one (may-make-macro?); a form taken from the
   host or skipped evaluates nothing, and take-from-host! marks what it
   interns itself."
  [ctx report form]
  (let [{:keys [action reason replacement] nm :name}
        (policy/decide form (host/host-name) (or @policy-lists (policy/lists (host/host-name))))
        _ (when @verbose? (println (str "  [" (name action) "] " nm)))
        def-form (if (= :replace action) replacement form)
        native (when (contains? #{:eval :replace} action) (native-of "clojure.core" def-form))
        evaluated (case (if native :native action)
                    :native (do (intern-native! core-ns nm native def-form "clojure.core") (record! report :native {:name nm}) nil)
                    :skip (do (record! report :skipped {:name nm :reason reason}) nil)
                    :host (when-not (take-from-host! report nm)
                            (if (contains? policy/host-only-names nm)
                              (do (record! report :skipped {:name nm :reason :no-host-equivalent}) nil)
                              (do (eval-form! ctx report form nm :ok) form)))
                    :replace (do (eval-form! ctx report replacement nm :replaced) replacement)
                    (do (eval-form! ctx report form nm :ok) form))]
    (when (and (some? evaluated) (may-make-macro? evaluated))
      (scan-macros! ctx))))

(defn- scan-string-end
  "cs (n characters) from i, just inside a string literal -> past its
   closing quote (n when it has none)."
  [cs n i]
  (loop [i i]
    (if (>= i n)
      n
      (let [c (nth cs i)]
        (if (= c \\) (recur (+ i 2)) (if (= c \") (inc i) (recur (inc i))))))))

(defn- scan-line-end
  "cs (n characters) from i -> past the next newline (n when none)."
  [cs n i]
  (loop [i i]
    (if (>= i n) n (if (= (nth cs i) \newline) (inc i) (recur (inc i))))))

(defn strip-string-tags
  "Delete `^\"...\"` type hints (JVM array class names) from source text,
   outside string, regex and character literals and comments: some host
   readers reject string metadata, and the rewriter drops :tag anyway."
  [src]
  (let [last-tag (host/last-index-of* src "^\"")
        n (count src)
        cs (host/string-chars src)]
    ;; The scan is one call per file, its loop written in special forms and
    ;; its strings and comments skipped by fns of their own: clojurust's
    ;; evaluator runs a fn's first call unlowered and expands a macro there
    ;; again on every pass (a cond loop over core.clj's 140,000 characters
    ;; ran past 13 minutes; doc/REPORT.md).
    (loop [i 0 start 0 out []]
      ;; past the last ^" nothing more is cut (most sources have none)
      (if (or (>= i n) (> i last-tag))
        (if (empty? out) src (apply str (conj out (subs src start n))))
        (let [c (nth cs i)]
          (if (= c \\)
            (recur (+ i 2) start out)
            (if (= c \;)
              (recur (scan-line-end cs n (inc i)) start out)
              (if (= c \")
                (recur (scan-string-end cs n (inc i)) start out)
                (if (and (= c \^) (< (inc i) n) (= (nth cs (inc i)) \"))
                  (let [j (scan-string-end cs n (+ i 2))]
                    (recur j j (conj out (subs src start i))))
                  (recur (inc i) start out))))))))))

(defn boot-file!
  "Boot one source file (a path) into cohost.core."
  [ctx report path]
  (let [src (if (profiling)
              (prof :read #(strip-string-tags (host/slurp-file path)))
              (strip-string-tags (host/slurp-file path)))]
    (host/with-ns core-ns
      (fn [] (read-prof src (fn [form] (process-form! ctx report form)))
        (scan-macros! ctx)))))

(defn- companion-forms
  "{name form}: the first top-level form defining each of names in the file
   at path, read once (the file's other forms are not evaluated)."
  [path names]
  (let [found (atom {})]
    (host/try-all (fn [] (let [src (if (profiling)
                                      (prof :read #(strip-string-tags (host/slurp-file path)))
                                      (strip-string-tags (host/slurp-file path)))]
                           (read-prof src (fn [form] (let [nm (policy/form-name form)]
                                                       (when (and (contains? names nm) (not (contains? @found nm)))
                                                         (swap! found assoc nm form))))))))
    @found))

(defn- boot-setup!
  "What boot! does before core.clj's first form (the snapshot's replay does
   it too): cohost.core, the codemod's ctx, the hooks. -> [ctx report]."
  [root]
  (host/ensure-bare-ns! core-ns)
  (let [ctx (if (profiling) (prof :setup #(make-ctx)) (make-ctx))
        report (atom {:ok 0 :replaced 0 :host 0 :native 0 :skipped 0 :error 0 :entries []})]
    (reset! policy-lists (policy/lists (host/host-name) (some? (host/chunks))))
    (swap! state assoc :ctx ctx :root root :host-dynamic [] :inlines {} :capture-inline? nil
           :libs {'clojure.core {:ns core-ns :status :loaded}})
    ;; (eval form) evaluates in the namespace the caller runs in, as on the JVM
    (reset! shim/eval-hook (fn [form] (eval-in (host/current-ns) form)))
    (reset! shim/load-hook (fn [path] (boot-file! ctx report (str root "/clojure/" path ".clj"))))
    [ctx report]))

(defn- install-host-reducers!
  "reduce with core's + or * hands the host its own (shim/host-reducers)."
  []
  (shim/set-host-reducers!
   (into {} (keep (fn [nm]
                    (let [sv (some-> (host/interned-var core-ns nm) host/var-root)
                          hv (some-> (host/resolve-core nm) host/var-root)]
                      (when (and (fn? sv) (fn? hv) (not (identical? sv hv)))
                        [sv hv])))
                  '[+ *]))))

(defn- snap-state
  "The boot's state a snapshot restores as data at the end of a module: the
   report, and what the codemod's ctx and the loader collected."
  [report]
  (let [c (ctx)]
    {:report report
     :host-dynamic (:host-dynamic @state)
     :capture-inline? (:capture-inline? @state)
     :imports @(:imports c)
     :renames @(:renames c)
     :user-types @(:user-types c)
     :host-referred @(:host-referred c)
     :loader-nses @(:loader-nses c)}))

(defn boot!
  "Boot core.clj. opts: :root -- the directory holding clojure/core.clj
   (default resources/cohost/clojure-1.12.0); :files -- paths under it;
   :profile? -- record phase timers (off unless true; the off path does not
   call :nano-time)."
  ([] (boot! {}))
  ([{:keys [root files verbose profile? record?] :or {root "resources/cohost/clojure-1.12.0" files ["clojure/core.clj"]}}]
   (reset! verbose? (boolean verbose))
   (reset! snapshot-log (when record? []))
   (snap! {:op :begin :opts {:root root :files files}})
   (if profile?
     (do (swap! state assoc :profile (atom {:phases {} :forms [] :walls {}}) :profile-bucket :core)
         (vreset! rw/profile-branches {})
         (vreset! rw/profile-child 0)
         (vreset! rw/profile-macro-ns {})
         (vreset! rw/profile-macro-n {}))
     (do (swap! state dissoc :profile :profile-bucket)
         (vreset! rw/profile-branches nil)
         (vreset! rw/profile-macro-ns nil)
         (vreset! rw/profile-macro-n nil)))
   (let [t-boot (when (profiling) (host/nano-time))]
   (let [[ctx report] (boot-setup! root)]
     ;; the prelude: what the JVM runtime defines before core.clj loads
     (host/with-ns core-ns
       (fn []
         (doseq [[sym dflt] policy/runtime-vars]
           (when-not (take-from-host! report sym)
             (eval-form! ctx report (list 'def (with-meta sym {:dynamic true}) dflt) sym :replaced)))))
     (doseq [f files]
       (boot-file! ctx report (str root "/" f)))
     ;; the companion files' names: the stock definition where it is plain
     ;; Clojure, else the host's, when it has them. The wall is one region:
     ;; its read, rewrite and eval stay in the :companion bucket.
     (host/with-ns core-ns
       (fn []
         (let [run-companions
               (fn []
                 (let [stock (into {} (mapcat (fn [[file nms]] (seq (companion-forms (str root "/" file) (set nms))))
                                              (group-by #(get policy/companion-stock %)
                                                        (filter #(contains? policy/companion-stock %) policy/companion-names))))]
                   (doseq [nm (sort policy/companion-names)]
                     (if (contains? policy/companion-stock nm)
                       (if-let [form (get stock nm)]
                         (process-form! ctx report form)
                         (record! report :skipped {:name nm :reason :no-host-equivalent}))
                       (when-not (take-from-host! report nm)
                         (if-let [v (get (host/core-extras) nm)]
                           (do (host/intern! core-ns nm v) (record! report :host {:name nm}) (note-host-dynamic! nm nil)
                               (snap! {:op :extra :sym nm}))
                           (record! report :skipped {:name nm :reason :no-host-equivalent}))))))
                 (scan-macros! ctx))]
           (if-not (profiling)
             (run-companions)
             (let [prev (:profile-bucket @state)
                   t0 (host/nano-time)]
               (swap! state assoc :profile-bucket :companion)
               (run-companions)
               (swap! (:profile @state) assoc-in [:walls :companion] (- (host/nano-time) t0))
               (swap! state assoc :profile-bucket prev))))))
     (install-host-reducers!)
     (reset! (:booted? ctx) true)
     (let [r (assoc @report
                    :rewrite-misses (rw/misses ctx)
                    :host-name (host/host-name))]
       (when t-boot
         (swap! (:profile @state) assoc :boot-wall (- (host/nano-time) t-boot)))
       (swap! state assoc :report r)
       (snap! {:op :end :data (snap-state r)})
       r)))))

(defn profile-section!
  "Run thunk in profile bucket k and record its wall time. A no-op addition
   when profiling is off: thunk still runs."
  [k thunk]
  (if-not (profiling)
    (thunk)
    (let [prev (:profile-bucket @state)]
      (swap! state assoc :profile-bucket k)
      (let [t0 (host/nano-time)
            v (thunk)
            dt (- (host/nano-time) t0)]
        (swap! (:profile @state) assoc-in [:walls k] dt)
        (swap! state assoc :profile-bucket (or prev :core))
        v))))

(defn- profile-top [forms k n]
  (take n (sort-by k (fn [a b] (compare b a)) forms)))

(defn- print-split!
  "Top n forms in bucket, ranked by rank-key. Each line is codemod ns then eval ns."
  [forms bucket rank-key n]
  (let [rows (profile-top (filter (fn [f] (= bucket (:bucket f))) forms) rank-key n)]
    (doseq [[i f] (map-indexed vector rows)]
      (println (str "PROFILE-SPLIT " (name bucket) " " (name rank-key) " " (inc i)
                    " " (:ns f) " " (:head f) " " (:name f)
                    " " (:rewrite f) " " (:eval f))))))

(defn print-profile!
  "Print the boot profile. Phases partition the measured total: core reading,
   codemod, eval, scan-macros!, setup (make-ctx), the companion-file wall,
   the library-phase wall, and the unaccounted remainder. PROFILE-SPLIT lines
   are the top 20 forms of the core and library phases, once by codemod time
   and once by eval time; each line prints both."
  []
  (when-let [a (:profile @state)]
    (let [p @a
          core (get-in p [:phases :core])
          read (get core :read 0)
          rewrite (get core :rewrite 0)
          ev (get core :eval 0)
          scan (get core :scan 0)
          setup (get core :setup 0)
          companion (get-in p [:walls :companion] 0)
          library (get-in p [:walls :library] 0)
          boot-wall (or (:boot-wall p) 0)
          total (prof+ boot-wall library)
          named (prof+ read rewrite ev scan setup companion library)
          other (- total named)
          rows [["reading" read] ["codemod" rewrite] ["eval" ev] ["scan-macros" scan]
                ["setup" setup] ["companion" companion] ["library" library] ["other" other]]
          pct (fn [n] (if (pos? total) (/ (* 100.0 n) total) 0.0))
          forms (:forms p)]
      (println (str "PROFILE-TOTAL " total))
      (doseq [[k n] rows]
        (println (str "PROFILE-PHASE " k " " n " " (pct n))))
      (println (str "PROFILE-SUM " (prof+ named other)))
      (print-split! forms :core :rewrite 20)
      (print-split! forms :core :eval 20)
      (print-split! forms :library :rewrite 20)
      (print-split! forms :library :eval 20)
      (when-let [m @rw/profile-branches]
        (let [rows (sort-by val (fn [a b] (compare b a)) m)
              tot (reduce (fn [a [_ n]] (prof+ a n)) 0 rows)]
          (println (str "PROFILE-BRANCH-SUM " tot))
          (doseq [[k n] rows]
            (println (str "PROFILE-BRANCH " (name k) " " n " "
                          (if (pos? tot) (/ (* 100.0 n) tot) 0.0)))))
      (when-let [mm @rw/profile-macro-ns]
        (let [counts (or @rw/profile-macro-n {})
              rows (take 20 (sort-by val (fn [a b] (compare b a)) mm))]
          (doseq [[i [nm n]] (map-indexed vector rows)]
            (println (str "PROFILE-MACRO " (inc i) " " nm " " n " " (get counts nm 0))))))))))

;; ---------------------------------------------------------------------------
;; using it

(defn- refer-core!
  "Make ns-sym see cohost.core, as `(:refer-clojure ...)` says: opts
   :exclude, :only, :rename. The host's dynamic vars are referred from the
   host's core, not interned, so they are referred one by one."
  [ns-sym {:keys [exclude only rename] :as opts}]
  (if (or exclude only rename)
    (host/refer! ns-sym core-ns opts)
    (host/refer-all! ns-sym core-ns))
  (let [skip (set exclude) keep (when only (set only))]
    (doseq [nm (:host-dynamic @state)
            :when (and (not (contains? skip nm)) (or (nil? keep) (contains? keep nm)))]
      (host/try-all (fn [] (host/refer-from-core! ns-sym nm)))))
  ns-sym)

(def ^:private session-ns
  "The namespace the last eval-str left current, as a REPL's would be; nil
   until an evaluation moves it (cohost.user)."
  (atom nil))

(defn make-user-ns!
  "A namespace that sees only cohost.core (plus special forms)."
  ([] (make-user-ns! user-ns))
  ([ns-sym]
   (when (= ns-sym user-ns) (reset! session-ns nil))
   (host/ensure-bare-ns! ns-sym)
   (refer-core! ns-sym {})))

;; ---------------------------------------------------------------------------
;; libraries: each in its own namespace, as the JVM's loader does

(defn source-roots
  "Directories searched for a library's source, in order."
  []
  (or (:roots @state) [(or (:root @state) "resources/cohost/clojure-1.12.0")]))

(defn add-source-root! [dir] (swap! state assoc :roots (conj (vec (source-roots)) dir)))

(defn- lib-path
  "clojure.core.reducers -> clojure/core/reducers; my-lib.core -> my_lib/core"
  [lib]
  (apply str (map (fn [c] (let [c (str c)] (cond (= c ".") "/" (= c "-") "_" :else c))) (str lib))))

(defn- try-slurp [path]
  (host/try-all (fn [] (host/slurp-file path))))

(defn- find-source
  "[path src] of lib's file: in each source root in turn, the extension whose
   reader-conditional key this target ranks first (cohost.scan/source-exts:
   .clj, then .cljc, on every target today -- as the JVM loads)."
  [lib]
  (let [exts (scan/source-exts (scan/ranking (:name (host/current))))]
    (some (fn [root]
            (some (fn [ext] (let [p (str root "/" (lib-path lib) ext)]
                              (when-let [src (try-slurp p)] [p src])))
                  exts))
          (source-roots))))

(defn stock-ns-name
  "The namespace a stock library boots into. clojure.X becomes cohost.X,
   as clojure.core became cohost.core: the host has a clojure.X of its
   own that its runtime may rely on. Any other library keeps its name."
  [lib]
  (let [s (str lib)]
    (cond (= s "clojure.core") core-ns
          (and (> (count s) 8) (= "clojure." (subs s 0 8))) (symbol (str "cohost." (subs s 8)))
          :else (symbol s))))

(defn- unquote* [x] (if (and (seq? x) (= 'quote (first x))) (second x) x))

(def ^:private directive-heads (set '[ns in-ns require use import refer-clojure alias]))

(defn- directive
  "A top-level loader form -> [head args], when every argument is literal."
  [form]
  (when (and (seq? form) (symbol? (first form)))
    (let [h (first form)
          hn (symbol (name h))]
      (when (and (contains? directive-heads hn)
                 (or (nil? (namespace h)) (contains? #{"clojure.core" (str core-ns)} (namespace h))))
        (let [args (rest form)
              literal? (fn [x] (or (symbol? x) (keyword? x) (string? x) (vector? x) (map? x)
                                   (and (seq? x) (or (= 'quote (first x)) (not (symbol? (first x)))
                                                     (= hn 'ns)))))]
          (when (or (= hn 'ns) (every? literal? args))
            [hn (if (= hn 'ns) args (map unquote* args))]))))))

(defn- note!
  "Record a library-load event under the library being loaded."
  [k entry]
  (when-let [lib (:loading @state)]
    (swap! state update-in [:libs lib]
           (fn [e] (-> e (update k (fnil inc 0)) (update :entries (fnil conj []) (assoc entry :status k)))))))

(declare load-lib!)
(declare refer-lib!)

(defn- import! [ns-sym specs]
  (let [pairs (mapcat (fn [spec]
                        (cond (symbol? spec)
                              (let [s (str spec) i (loop [i (dec (count s))] (cond (neg? i) -1 (= "." (str (nth s i))) i :else (recur (dec i))))]
                                [[(symbol (subs s (inc i))) spec]])
                              (sequential? spec)
                              (let [[pkg & classes] spec]
                                (map (fn [c] [c (symbol (str pkg "." c))]) classes))
                              :else nil))
                      specs)]
    (swap! (:imports (ctx)) update (str ns-sym) merge (into {} pairs))))

(defn- libspecs
  "require/use arguments -> [[lib opts-map] ...]: symbols, [lib & opts],
   prefix lists (prefix lib-or-spec ...); flags (:reload ...) ignored."
  [args]
  (mapcat (fn [a]
            (cond (symbol? a) [[a {}]]
                  ;; [lib & opts]: a vector whose second element, if any, is an
                  ;; option keyword; else a prefix list written as a vector
                  (and (vector? a) (or (nil? (second a)) (keyword? (second a))))
                  [[(first a) (apply hash-map (rest a))]]
                  (and (or (seq? a) (vector? a)) (symbol? (first a)))
                  (let [prefix (str (first a))]
                    (map (fn [x] (if (vector? x)
                                   [(symbol (str prefix "." (first x))) (apply hash-map (rest x))]
                                   [(symbol (str prefix "." x)) {}]))
                         (rest a)))
                  :else nil))
          args))

(defn- require! [ns-sym args use?]
  ;; :reload and :reload-all: the named libraries load again (their
  ;; dependencies are not reloaded by :reload-all here)
  (when (some #{:reload :reload-all} args)
    (doseq [[lib] (libspecs args)]
      (when (get-in @state [:libs (symbol (str lib)) :path])
        (swap! state update :libs dissoc (symbol (str lib))))))
  (doseq [[lib {:keys [as as-alias refer only exclude rename]}] (libspecs args)]
    (if as-alias
      ;; (alias as-alias (create-ns lib)) without loading it, as the JVM does;
      ;; a later require boots the library into the same namespace
      (let [target (or (get-in @state [:libs lib :ns]) (stock-ns-name lib))]
        (when-not (get-in @state [:libs lib])
          (host/ensure-bare-ns! target)
          (when (not= (str target) (str lib)) (swap! (:renames (ctx)) assoc (str lib) (str target))))
        (host/alias! ns-sym as-alias target))
      (let [target (load-lib! lib ns-sym)]
        (when as (host/alias! ns-sym as target))
        (cond
          (= refer :all) (refer-lib! ns-sym target {:rename rename})
          (sequential? refer) (refer-lib! ns-sym target {:only refer :rename rename})
          use? (refer-lib! ns-sym target {:only only :exclude exclude :rename rename}))))))

(defn- refer-lib! [ns-sym target opts]
  (if (= target core-ns)
    (refer-core! ns-sym opts)
    ;; opts without its nil options, by key: Phel's remove over a map walks
    ;; its values, not [k v] entries ((into {} (remove (comp nil? val) opts))
    ;; made {a b} of :refer [a b] there -- no :only, every public name referred)
    (host/refer! ns-sym target (reduce (fn [m k] (let [v (get opts k)] (if (nil? v) m (assoc m k v))))
                                       {} (keys opts)))))

(defn- ns! [nm clauses]
  (let [nm (unquote* nm)
        target (if-let [m (get @(:renames (ctx)) (str nm))] (symbol m) nm)
        ;; (ns name docstring? attr-map? references*): the namespace's metadata
        doc (when (string? (first clauses)) (first clauses))
        attrs (first (filter map? (take 2 clauses)))
        ns-meta (merge (meta nm) attrs (when doc {:doc doc}))
        clauses (filter sequential? clauses)
        rc (some #(when (= :refer-clojure (first %)) (apply hash-map (rest %))) clauses)]
    (host/ensure-bare-ns! target)
    #?(:cljs nil
       :default (when (seq ns-meta)
                  (host/try-all (fn [] (alter-meta! (host/the-ns* target) merge ns-meta)))))
    (swap! (:loader-nses (ctx)) conj (str target))
    (refer-core! target (or rc {}))
    (host/set-current-ns! target)
    (doseq [[k & args] clauses]
      (case k
        :require (require! target args false)
        :use (require! target args true)
        :import (import! target args)
        nil))
    target))

(defn- directive!
  "-> [nil namespace-after]"
  [[h args]]
  (let [cur (host/current-ns)]
    (case h
      ns [nil (ns! (first args) (rest args))]
      in-ns (let [t (first args)
                  t (if-let [m (get @(:renames (ctx)) (str t))] (symbol m) t)
                  found (host/try-all (fn [] (shim/Namespace-find t)))]
              ;; an existing namespace is entered as it is; a new one is bare
              (when-not (or (some? found) (contains? (:libs @state) t)) (host/ensure-bare-ns! t))
              (host/set-current-ns! t)
              [(or found (host/try-all (fn [] (shim/Namespace-find t)))) t])
      require (do (require! cur args false) [nil cur])
      use (do (require! cur args true) [nil cur])
      import (do (import! cur args) [nil cur])
      refer-clojure (do (refer-core! cur (apply hash-map args)) [nil cur])
      ;; (alias 'c 'clojure.core): the target as the loader booted it
      alias (let [[a t] args
                  t (cond (= t 'clojure.core) core-ns
                          (get-in @state [:libs t :ns]) (get-in @state [:libs t :ns])
                          :else (if-let [m (get @(:renames (ctx)) (str t))] (symbol m) t))]
              (host/alias! cur a t)
              [nil cur]))))

(defn- core-var [sym]
  (or (host/interned-var core-ns sym) (host/resolve-core sym)))

(defn- with-load-bindings
  "The JVM's load binds *warn-on-reflection* and *unchecked-math* (a library
   may set! them) and *ns*."
  [thunk]
  (let [bs (into {} (keep (fn [s]
                            (let [v (core-var s)]
                              (when (and v (host/dynamic-var? v))
                                [v (host/var-root v)])))
                          '[*warn-on-reflection* *unchecked-math*]))]
    (if (seq bs)
      (host/with-thread-bindings! bs thunk)
      (thunk))))

(defn- load-source!
  "Every top-level form of a library, read and evaluated one at a time in the
   namespace its (ns ...) form makes current; a form that fails is recorded
   and the load goes on, as the core boot does."
  ([lib src] (load-source! lib src nil nil))
  ([lib src from-ns] (load-source! lib src from-ns nil))
  ([lib src from-ns path]
   (let [c (ctx)]
     (with-load-bindings
       (fn []
         (host/with-ns (or from-ns core-ns)
           (fn []
             (read-prof
              (if (profiling) (prof :read #(strip-string-tags src)) (strip-string-tags src))
              (fn [form]
                (let [cur (host/current-ns)
                      {:keys [action replacement] nm :name} (policy/decide-lib form lib)]
                  (when @verbose? (println (str "  [" (name action) "] " lib " " nm)))
                  (host/try-all
                   (fn []
                     (case (if (and (= :eval action) (native-of (str lib) form)) :native action)
                       :skip (note! :skipped {:name nm})
                       :native (do (intern-native! cur nm (native-of (str lib) form) form (str lib)) (note! :native {:name nm}))
                       (let [[_ ns'] (eval-top c cur (if (= :replace action) replacement form) true)]
                         (when (not= ns' cur) (host/set-current-ns! ns'))
                         (note! :ok {:name nm}))))
                   (fn [e]
                     (snap-declare! cur nm)
                     (note! :error {:name nm :error (error-message e) :form (short-form form)})))))
              path)
             ;; a macro some form made by a side effect is wrapped now
             (scan-macros! c (host/current-ns)))))))))

(defn load-lib!
  "Load lib (a symbol) once: its source from a source root, booted into its own
   namespace, or else the host's own require. -> the namespace to alias."
  ([lib] (load-lib! lib nil))
  ([lib from-ns]
   (let [lib (symbol (str lib))]
     (if-let [e (get-in @state [:libs lib])]
       (:ns e)
       (if-let [m (get (:snapshot-libs @state) lib)]
         ;; a snapshot's compiled module of the library: loading it replays
         ;; the load (snap-lib-begin! ... snap-lib-end!)
         (do (host/require-host! m)
             (or (get-in @state [:libs lib :ns])
                 (throw (host/ex* 'java.lang.Exception (str "snapshot module " m " did not load " lib)))))
       (if-let [[path src] (find-source lib)]
         (let [target (stock-ns-name lib)
               outer (:loading @state)
               forget! (fn [] (swap! state update :libs dissoc lib) (swap! (:renames (ctx)) dissoc (str lib)))]
           (snap! {:op :lib-begin :lib lib :target target :path path :parent outer})
           (swap! state assoc-in [:libs lib] {:ns target :status :loading :path path :ok 0 :error 0 :skipped 0})
           (swap! (:renames (ctx)) assoc (str lib) (str target))
           (swap! state assoc :loading lib)
           (try (host/try-all (fn [] (load-source! lib src from-ns path))
                              (fn [e] (forget!) (snap! {:op :lib-fail :lib lib}) (throw e)))
                (finally (swap! state assoc :loading outer)))
           (when-not (contains? @(:loader-nses (ctx)) (str target))
             (forget!)
             (snap! {:op :lib-fail :lib lib})
             (throw (host/ex* 'java.lang.Exception (str "namespace '" lib "' not found after loading '/" (lib-path lib) "'"))))
           (swap! state assoc-in [:libs lib :status] :loaded)
           (swap! state assoc :loading lib)
           (snap! {:op :lib-end :lib lib :entry (get-in @state [:libs lib]) :data (snap-state nil)})
           (swap! state assoc :loading outer)
           target)
         (do (host/require-host! lib)
             (swap! state assoc-in [:libs lib] {:ns lib :status :host})
             lib)))))))

(defn lib-report
  "{lib {:ns :status :ok :error :skipped :entries}} of every library loaded."
  []
  (dissoc (:libs @state) 'clojure.core))

(defn print-lib-report [lib]
  (let [{:keys [ns status ok error skipped native entries]} (get (:libs @state) (symbol (str lib)))]
    (println (str "  " lib " -> " ns " (" (name (or status :absent)) "): "
                  (or ok 0) " forms evaluated, " (when native (str native " natives, "))
                  (or skipped 0) " skipped, " (or error 0) " errors"))
    (doseq [e entries :when (= :error (:status e))]
      (println (str "     - " (:name e) ": " (:error e))))))

(defn read-in
  "Read `src` (one form) with *ns* bound to `ns-sym`, so syntax-quote resolves
   against the stock names."
  [ns-sym src]
  (host/with-ns ns-sym (fn [] (host/read-string* src))))

(defn eval-str
  "Read and eval every form of source text, each in the namespace the one
   before it left -- starting where the last eval-str ended (cohost.user at
   first), as a REPL session does; -> the last value. opts {:session? false}
   evaluates in cohost.user without moving the session; {:on-value f} calls
   (f value ns) after each form, with the namespace it left."
  ([src] (eval-str src nil))
  ([src opts]
   (let [c (or (ctx) (let [c (make-ctx)] (swap! state assoc :ctx c) c))
         session? (not (false? (:session? opts)))
         ns0 (if session? (or @session-ns user-ns) user-ns)
         ;; [last value, current namespace]: each form is read after the one
         ;; before it ran, in the namespace it left (::kw after an ns form)
         at (atom [nil ns0])]
     (try
       (host/with-ns ns0
         (fn []
           (host/read-forms src (fn [form]
                                  (let [[_ ns] @at
                                        [v ns'] (eval-top c ns form)]
                                    (when (not= ns' ns) (host/set-current-ns! ns'))
                                    (reset! at [v ns'])
                                    (when-let [f (:on-value opts)] (f v ns')))))))
       (first @at)
       (finally (when session? (reset! session-ns (second @at))))))))

;; ---------------------------------------------------------------------------
;; report

(defn print-report [{:keys [host-name ok replaced skipped error entries rewrite-misses] :as r}]
  (let [from-host (:host r)
        natives (or (:native r) 0)
        total (+ ok replaced from-host natives skipped error)]
    (println (str "cohost.boot on " (name host-name) ": " total " top-level forms"))
    (println (str "  booted as stock: " (+ ok replaced) "   from host: " from-host
                  (when (pos? natives) (str "   natives: " natives))
                  "   skipped: " skipped "   errors: " error))
    (when (pos? error)
      (println "  errors:")
      (doseq [e (filter #(= :error (:status %)) entries)]
        (println (str "   - " (:name e) ": " (:error e)))
        (println (str "       " (:form e)))))
    (when (seq rewrite-misses)
      (println "  interop the shim lacks (rewrite-time):")
      (doseq [[k n] (sort-by (comp str key) rewrite-misses)]
        (println (str "   - " (pr-str k) " x" n))))
    (let [rm (shim/runtime-misses*)]
      (when (seq rm)
        (println "  shim misses hit at runtime:")
        (doseq [[k n] rm] (println (str "   - " (pr-str k) " x" n)))))))

;; ---------------------------------------------------------------------------
;; the AOT snapshot's replay (jank-FAST Step 6 stage 2)
;;
;; A snapshot module is the recorded boot as source: the forms the boot
;; evaluated, in order, between calls of these fns, which redo what the boot
;; did through the adapter (interns from the host, macro marking, refers,
;; the shim's prebuilt vars) and restore the boot's state as data. The
;; module's compiled object runs the same calls when it loads. Nothing here
;; runs unless a snapshot module calls it.

(def ^:private snap-report (atom nil))

(defn snap-begin!
  "A snapshot's cohost.core module, first: what boot! does before core.clj.
   libs: {lib module} of the snapshot's library modules (load-lib! requires
   the module instead of loading the source)."
  [{:keys [root]} libs]
  (reset! verbose? false)
  (reset! snapshot-log nil)
  (swap! state dissoc :profile :profile-bucket)
  (let [[_ report] (boot-setup! root)]
    (reset! snap-report report)
    (swap! state assoc :snapshot-libs libs :core-libs libs)
    nil))

(defn snap-take! "take-from-host! of nm, as the boot did." [nm]
  (take-from-host! @snap-report nm)
  nil)

(defn snap-extra! "A companion name from the adapter's core-extras." [nm]
  (host/intern! core-ns nm (get (host/core-extras) nm))
  (note-host-dynamic! nm nil)
  nil)

(defn snap-scan! "scan-macros! of ns-sym." [ns-sym]
  (scan-macros! (ctx) ns-sym)
  nil)

(defn snap-is! "The shim's instance test for class token c." [c]
  (shim/install-is! c)
  nil)

(defn snap-dot! "The shim's dispatcher for a method it lacks." [m]
  (shim/install-missing-dot! m)
  nil)

(defn snap-native!
  "A native the boot interned in place of a stock def form: the adapter's
   :natives entry for lib/nm (the host core's own var root for :host, else
   the adapter's fn), as intern-native! found it, with the recorded meta."
  [ns-sym nm lib m]
  (let [impl (get (host/natives) (symbol lib (name nm)))
        v (if (= :host impl) (host/var-root (host/resolve-core nm)) impl)
        h (host/intern! ns-sym (symbol (name nm)) v)]
    (host/alter-var-meta! h merge m)
    nil))

(defn snap-inline! "The :inline fn the codemod applies for nm: [f arities]." [nm v]
  (swap! state assoc-in [:inlines nm] v)
  nil)

(defn snap-directive! "A loader directive, as eval-in* ran it." [d]
  (directive! d)
  nil)

(defn- snap-restore! [{:keys [host-dynamic capture-inline? imports renames user-types host-referred loader-nses]}]
  (let [c (ctx)]
    (swap! state assoc :host-dynamic host-dynamic :capture-inline? capture-inline?)
    (swap! (:imports c) merge imports)
    (swap! (:renames c) merge renames)
    (swap! (:user-types c) into user-types)
    (swap! (:host-referred c) into host-referred)
    (swap! (:loader-nses c) into loader-nses)))

(defn snap-end!
  "A snapshot's cohost.core module, last: what boot! does after core.clj,
   and its report."
  [data]
  (install-host-reducers!)
  (snap-restore! data)
  (reset! (:booted? (ctx)) true)
  (swap! state assoc :report (:report data) :snapshot? true)
  nil)

(defn snap-lib-begin!
  "A library module, first: what load-lib! does before the source."
  [lib target path parent]
  ;; what is loading now (not the recorded parent: a module can load inside
  ;; another library's load at run time), restored by snap-lib-end!
  (swap! state update ::snap-outer (fnil conj []) (:loading @state))
  (swap! state assoc-in [:libs lib] {:ns target :status :loading :path path :ok 0 :error 0 :skipped 0})
  (swap! (:renames (ctx)) assoc (str lib) (str target))
  (swap! state assoc :loading lib)
  nil)

(defn snap-lib-end!
  "A library module, last: its load-lib! entry and the loader's state."
  [lib entry data parent]
  (snap-restore! data)
  (swap! state assoc-in [:libs lib] entry)
  (let [outer (peek (::snap-outer @state))]
    (swap! state (fn [st] (-> st (assoc :loading outer) (update ::snap-outer pop)))))
  nil)

(defn snap-read
  "A snapshot module's data for a host whose reader cannot read cohost's
   printed forms (let-go: the emitter's :text? dialect): text, read by
   cohost's reader (the adapter's read-string, as the boot reads)."
  [text]
  (host/read-string* text))

(defn snap-form!
  "A snapshot module's recorded form for such a host: its text, read by
   cohost's reader and evaluated through the adapter, as the boot evaluated
   the rewritten form. -> its value."
  [text]
  (host/eval-form (snap-read text)))

(defn compile-lib!
  "cohost's compile (D-10), as clojure.core/compile: lib and every library it
   loads that is not the core snapshot's (the stock core and its twelve
   libraries) are loaded again from source with record mode on, and the
   recorded events kept in (:compiled-events @state): the CLI writes them to
   the project's compiled-namespace directory when the run ends, and the
   launcher compiles them into the host's modules there (jank objects,
   Basilisp's .lpy and bytecode) before the command returns. Inside a run
   that records already (a project's first run), lib just loads: that record
   takes it once. -> lib."
  [lib]
  (let [lib (symbol (str lib))
        core (conj (set (keys (:core-libs @state))) 'clojure.core)
        project? (fn [[l _]] (not (contains? core l)))]
    (swap! state assoc :compile-requested true)
    (if @snapshot-log
      (load-lib! lib)
      (do (swap! state (fn [st] (-> st
                                    (update :libs (fn [m] (into {} (remove project? m))))
                                    (update :snapshot-libs (fn [m] (into {} (remove project? m)))))))
          (snapshot-start!)
          (try (load-lib! lib)
               (finally
                 (swap! state update :compiled-events (fn [ev] (into (or ev []) (snapshot-stop!))))))))
    lib))

(defn register-snapshot-libs!
  "A project snapshot's entry: {lib module} of its library modules, which
   load-lib! then requires instead of loading the sources."
  [libs]
  (swap! state update :snapshot-libs merge libs)
  nil)

(defn snapshot-report
  "The report of the boot a loaded snapshot restored, or nil."
  []
  (when (:snapshot? @state) (:report @state)))

(defn boot-from-snapshot!
  "boot! from a snapshot: require its cohost.core module (it replays the
   recorded boot and restores its state) unless one is loaded. root: the
   stock root later loads read (the snapshot recorded the one it was built
   with, relative to the repo). The caller checked that the snapshot on the
   module path is fresh (jank: dev/perf/jank_snapshot_cli.clj). -> the
   boot's report."
  [{:keys [root]}]
  (when-not (snapshot-report) (host/require-host! core-ns))
  (let [r (snapshot-report)]
    (when-not r
      (throw (host/ex* 'java.lang.Exception (str core-ns " loaded, but it is not a snapshot"))))
    (when root (swap! state assoc :root root))
    r))

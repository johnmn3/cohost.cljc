(ns cohost.boot
  "Boots the stock clojure/core.clj inside the host clojure-like.

   For each top-level form of core.clj, in order: ask cohost.policy what to do;
   run the form through cohost.rewrite (JVM interop -> cohost.shim); eval it with
   *ns* bound to `cohost.core`, a namespace that refers nothing from the host's
   core, so every name core.clj defines is the stock one. After every form,
   each newly defined macro is wrapped so its expansions are rewritten too (a
   stock macro emits interop into its caller). Names the policy marks :host are
   referred from the host's core — they are the host's vars, not copies.

   The result is a report: how many forms booted, which were taken from the
   host, which were skipped, and every form that failed with its error."
  (:require [cohost.adapter :as host]
            [cohost.rewrite :as rw]
            [cohost.shim :as shim]
            [cohost.policy :as policy]))

(def core-ns "The namespace the stock core.clj boots into." 'cohost.core)
(def user-ns 'cohost.user)

(defonce state (atom {:ctx nil :report nil}))

(defn ctx [] (:ctx @state))

(defn- short-form [form]
  (let [s (pr-str form)]
    (if (> (count s) 200) (str (subs s 0 200) " ...") s)))

(defn- error-message [e] (host/error-message e))

;; ---------------------------------------------------------------------------
;; macros: rewrite what they emit

(defn wrap-macro [ctx f]
  (fn [& args] (let [out (apply f args)] (if host/*raw-macro-output* out (rw/rewrite ctx out)))))

(defn ^:export rewrite-output
  "Rewrite a macro's expansion with the current boot's ctx. Hosts whose macro
   fns cannot be replaced after definition (:macro-wrap-style :source) get
   this call compiled into every stock macro's body instead."
  [form]
  (if-let [c (and (not host/*raw-macro-output*) (ctx))] (rw/rewrite c form) form))

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
                          (list params (list (host/rewrite-output-ref) (apply list 'do body))))
                        arities)]
      (apply list 'defmacro nm (concat (when doc [doc]) (when attrs [attrs])
                                       (if (vector? (first more)) (first arities') arities'))))))

(defn- scan-macros!
  "Promote `^:macro` defs the host did not treat as macros, and wrap every
   not-yet-wrapped macro of ns-sym so its output goes through the codemod."
  ([ctx] (scan-macros! ctx core-ns))
  ([ctx ns-sym]
   (doseq [[_sym v] (host/ns-interns-map ns-sym)]
     (let [m (host/var-meta v)]
       (when (and (:macro m) (not (host/macro-var? v)))
         (host/mark-macro! v))
       (when (and (host/macro-var? v) (not (:cohost/wrapped m)))
         (when (= :root (host/macro-wrap-style))
           (host/set-var-root! v (fn [f] (wrap-macro ctx f))))
         (host/alter-var-meta! v assoc :cohost/wrapped true))))))

;; ---------------------------------------------------------------------------
;; eval

(defn make-ctx
  "The codemod's context for this host: which shim vars exist, the host's
   catch-all, its compile-style facts, and the per-namespace facts (current
   namespace, imports, renamed stock libraries, extend-position types)."
  []
  (rw/make-ctx {:shim-names (let [names (host/ns-public-names 'cohost.shim)]
                              ;; a stub that only throws is no shim where the
                              ;; host has the real thing
                              (if (host/interop-passthrough?)
                                (let [vs (host/ns-interns-map 'cohost.shim)]
                                  (into #{} (remove #(shim/stub? (some-> (get vs %) host/var-root))) names))
                                names))
                :catch-all (host/catch-all-sym)
                :core-ns (name core-ns)
                :shim-ref (host/shim-ref)
                :loop-variadic-recur? (host/loop-variadic-recur?)
                :def-meta (host/def-meta)
                :strip-inline? (host/strip-inline?)
                :current-ns host/current-ns
                :host-type (host/host-type-fn)
                :per-arity-impls? (= :per-arity (host/protocol-impl-style))
                :python-methods? (= :python (host/type-method-style))
                :move-methods? (= :protocols (host/type-method-style))
                :host-method host/host-method
                :var-form (fn [x] (when (host/var?* x)
                                    (let [n (host/var-ns x) n (if (host/namespace? n) (ns-name n) n)]
                                      (list 'var (symbol (str n) (str (host/var-sym x)))))))
                :passthrough? (host/interop-passthrough?)
                :record? shim/record?*
                :protocol-var? host/protocol-var?
                :interned? (fn [s] (let [n (host/current-ns)]
                                     (boolean (and n (try (contains? (host/ns-interns-map (symbol (str n))) s)
                                                          (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) _ false))))))}))

(declare eval-top directive directive!)

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
  "-> [value namespace-after]"
  [c ns-sym form]
  (host/with-ns ns-sym
    (fn [] (if-let [d (directive form)]
             (directive! d)
             (let [form (if (= :source (host/macro-wrap-style)) (wrap-defmacro-source form) form)
                   v (host/eval-form (rw/rewrite c form))]
               (host/after-eval form ns-sym)
               (scan-macros! c ns-sym)
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
  [c ns-sym form]
  (if (and (seq? form) (= 'do (first form)) (next form))
    (loop [ns ns-sym fs (seq (rest form)) v nil]
      (if fs
        (let [[v ns'] (eval-top c ns (first fs))] (recur ns' (next fs) v))
        [v ns]))
    (let [before (when (and (host/def-meta) (seq? form) (symbol? (first form))
                            (contains? host-def-heads (name (first form))))
                   (set (keys (host/ns-interns-map ns-sym))))
          r (eval-in* c ns-sym form)]
      (when before (mark-host-defs! ns-sym before))
      r)))

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
    :ns (fn [x & more] (apply f (stock-ns x) (map stock-sym more)))
    :sym (fn [& args] (apply f (map stock-sym args)))
    f))

(defn- take-from-host!
  "Give cohost.core the host core's `nm`. Dynamic vars are referred (the
   var identity matters for binding); everything else is interned as a copy,
   so a stock macro's syntax-quoted `cohost.core/pr-str` resolves. Host
   macros are interned with their macro status and never wrapped. Returns true
   when the host had it; false means the caller boots the stock definition."
  [report nm]
  (boolean
   (if-let [v (get (host/core-extras) nm)]
     ;; the adapter's own definition of a name the host's core lacks or has
     ;; only at compile time (ClojureScript's resolve is a macro)
     (do (host/intern! core-ns nm (ns-arg-wrap nm v))
         (record! report :host {:name nm})
         true)
     (when-let [hv (host/resolve-core nm)]
       (try
         (if (host/dynamic-var? hv)
           (do (host/refer-from-core! core-ns nm)
               (when-let [c (ctx)] (swap! (:host-referred c) conj nm)))
           (let [v (host/intern! core-ns nm (ns-arg-wrap nm (host/var-root hv)))
                 hm (host/var-meta hv)]
             (host/alter-var-meta! v merge (select-keys hm [:doc :arglists :private])
                                   {:cohost/host true :cohost/wrapped true})
             (when (host/macro-var? hv) (host/mark-macro! v))))
         (record! report :host {:name nm})
         true
         (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) _ false))))))

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

(defn- eval-form! [ctx report form name status]
  (try
    (host/eval-form (rw/rewrite ctx (prepare form)))
    (host/after-eval form core-ns)
    (record! report status {:name name})
    (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) e
      (record! report :error {:name name :error (error-message e) :form (short-form form)}))))

(def verbose? (atom false))

(defn- process-form! [ctx report form]
  (let [{:keys [action reason replacement] nm :name} (policy/decide form (host/host-name))]
    (when @verbose? (println (str "  [" (name action) "] " nm)))
    (case action
      :skip (record! report :skipped {:name nm :reason reason})
      :host (when-not (take-from-host! report nm)
              (if (contains? policy/host-only-names nm)
                (record! report :skipped {:name nm :reason :no-host-equivalent})
                (eval-form! ctx report form nm :ok)))
      :replace (eval-form! ctx report replacement nm :replaced)
      (eval-form! ctx report form nm :ok))
    (scan-macros! ctx)))

(defn strip-string-tags
  "Delete `^\"...\"` type hints (JVM array class names) from source text,
   outside string, regex and character literals and comments: some host
   readers reject string metadata, and the rewriter drops :tag anyway."
  [src]
  (let [n (count src)
        at (fn [i] (when (< i n) (nth src i)))]
    (loop [i 0 start 0 out [] state :code]
      (if (>= i n)
        (apply str (conj out (subs src start n)))
        (let [c (at i)]
          ;; (cond, not case: a host's case may expand into its own recur point)
          (cond
            (= state :string) (cond (= c \\) (recur (+ i 2) start out :string)
                                    (= c \") (recur (inc i) start out :code)
                                    :else (recur (inc i) start out :string))
            (= state :comment) (if (= c \newline)
                                 (recur (inc i) start out :code)
                                 (recur (inc i) start out :comment))
            (= c \\) (recur (+ i 2) start out :code)
            (= c \;) (recur (inc i) start out :comment)
            (= c \") (recur (inc i) start out :string)
            (and (= c \^) (= (at (inc i)) \"))
            (let [j (loop [j (+ i 2)]
                      (cond (>= j n) n
                            (= (at j) \\) (recur (+ j 2))
                            (= (at j) \") j
                            :else (recur (inc j))))]
              (recur (inc j) (inc j) (conj out (subs src start i)) :code))
            :else (recur (inc i) start out :code)))))))

(defn boot-file!
  "Boot one source file (a path) into cohost.core."
  [ctx report path]
  (let [src (strip-string-tags (host/slurp-file path))]
    (host/with-ns core-ns
      (fn [] (host/read-forms src (fn [form] (process-form! ctx report form)))))))

(defn- boot-companion!
  "The stock definition of companion name nm, read from its file (the file's
   other forms stay unread here)."
  [ctx report path nm]
  (let [found (atom nil)]
    (try (host/read-forms (strip-string-tags (host/slurp-file path))
                          (fn [form] (when (and (nil? @found) (= nm (policy/form-name form))) (reset! found form))))
         (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) _ nil))
    (if-let [form @found]
      (process-form! ctx report form)
      (record! report :skipped {:name nm :reason :no-host-equivalent}))))

(defn boot!
  "Boot core.clj. opts: :root — the directory holding clojure/core.clj
   (default resources/cohost/clojure-1.12.0); :files — paths under it."
  ([] (boot! {}))
  ([{:keys [root files verbose] :or {root "resources/cohost/clojure-1.12.0" files ["clojure/core.clj"]}}]
   (reset! verbose? (boolean verbose))
   (host/ensure-bare-ns! core-ns)
   (let [ctx (make-ctx)
         report (atom {:ok 0 :replaced 0 :host 0 :skipped 0 :error 0 :entries []})]
     (swap! state assoc :ctx ctx :root root
            :libs {'clojure.core {:ns core-ns :status :loaded}})
     ;; (eval form) evaluates in the namespace the caller runs in, as on the JVM
     (reset! shim/eval-hook (fn [form] (eval-in (host/current-ns) form)))
     (reset! shim/load-hook (fn [path] (boot-file! ctx report (str root "/clojure/" path ".clj"))))
     ;; the prelude: what the JVM runtime defines before core.clj loads
     (host/with-ns core-ns
       (fn []
         (doseq [[sym dflt] policy/runtime-vars]
           (when-not (take-from-host! report sym)
             (eval-form! ctx report (list 'def (with-meta sym {:dynamic true}) dflt) sym :replaced)))))
     (doseq [f files]
       (boot-file! ctx report (str root "/" f)))
     ;; the companion files' names: the stock definition where it is plain
     ;; Clojure, else the host's, when it has them
     (host/with-ns core-ns
       (fn []
         (doseq [nm (sort policy/companion-names)]
           (if-let [file (get policy/companion-stock nm)]
             (boot-companion! ctx report (str root "/" file) nm)
             (when-not (take-from-host! report nm)
               (if-let [v (get (host/core-extras) nm)]
                 (do (host/intern! core-ns nm v) (record! report :host {:name nm}))
                 (record! report :skipped {:name nm :reason :no-host-equivalent})))))))
     (reset! (:booted? ctx) true)
     (let [r (assoc @report
                    :rewrite-misses (rw/misses ctx)
                    :host-name (host/host-name))]
       (swap! state assoc :report r)
       r))))

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
    (doseq [e (:entries (:report @state))
            :let [nm (:name e)]
            :when (and (= :host (:status e)) (not (contains? skip nm)) (or (nil? keep) (contains? keep nm)))]
      (when-let [hv (host/resolve-core nm)]
        (when (host/dynamic-var? hv)
          (try (host/refer-from-core! ns-sym nm)
               (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) _ nil))))))
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
  (try (host/slurp-file path)
       (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) _ nil)))

(defn- find-source [lib]
  (some (fn [root]
          (some (fn [ext] (let [p (str root "/" (lib-path lib) ext)]
                            (when-let [src (try-slurp p)] [p src])))
                [".clj" ".cljc"]))
        (source-roots)))

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

(def ^:private directive-heads '#{ns in-ns require use import refer-clojure alias})

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

(declare load-lib! refer-lib!)

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
    (host/refer! ns-sym target (into {} (remove (comp nil? val) opts)))))

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
                  (try (alter-meta! (the-ns target) merge ns-meta)
                       (catch #?(:jolt Throwable :clj Throwable :default Exception) _ nil))))
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
                  found (try (shim/Namespace-find t) (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) _ nil))]
              ;; an existing namespace is entered as it is; a new one is bare
              (when-not (or (some? found) (contains? (set (keys (:libs @state))) t)) (host/ensure-bare-ns! t))
              (host/set-current-ns! t)
              [(try (shim/Namespace-find t) (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) _ nil)) t])
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
  (or (get (host/ns-interns-map core-ns) sym) (host/resolve-core sym)))

(defn- with-load-bindings
  "The JVM's load binds *warn-on-reflection* and *unchecked-math* (a library
   may set! them) and *ns*."
  [thunk]
  (let [bs (into {} (for [s '[*warn-on-reflection* *unchecked-math*]
                          :let [v (core-var s)]
                          :when (and v (host/dynamic-var? v))]
                      [v (host/var-root v)]))]
    (if (seq bs)
      (do (host/push-thread-bindings! bs)
          (try (thunk) (finally (host/pop-thread-bindings!))))
      (thunk))))

(defn- load-source!
  "Every top-level form of a library, read and evaluated one at a time in the
   namespace its (ns ...) form makes current; a form that fails is recorded
   and the load goes on, as the core boot does."
  ([lib src] (load-source! lib src nil))
  ([lib src from-ns]
   (let [c (ctx)]
     (with-load-bindings
       (fn []
         (host/with-ns (or from-ns core-ns)
           (fn []
             (host/read-forms
              (strip-string-tags src)
              (fn [form]
                (let [cur (host/current-ns)
                      {:keys [action replacement] nm :name} (policy/decide-lib form lib)]
                  (when @verbose? (println (str "  [" (name action) "] " lib " " nm)))
                  (try
                    (case action
                      :skip (note! :skipped {:name nm})
                      (let [[_ ns'] (eval-top c cur (if (= :replace action) replacement form))]
                        (when (not= ns' cur) (host/set-current-ns! ns'))
                        (note! :ok {:name nm})))
                    (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) e
                      (note! :error {:name nm :error (error-message e) :form (short-form form)})))))))))))))

(defn load-lib!
  "Load lib (a symbol) once: its source from a source root, booted into its own
   namespace, or else the host's own require. -> the namespace to alias."
  ([lib] (load-lib! lib nil))
  ([lib from-ns]
   (let [lib (symbol (str lib))]
     (if-let [e (get-in @state [:libs lib])]
       (:ns e)
       (if-let [[path src] (find-source lib)]
         (let [target (stock-ns-name lib)
               outer (:loading @state)
               forget! (fn [] (swap! state update :libs dissoc lib) (swap! (:renames (ctx)) dissoc (str lib)))]
           (swap! state assoc-in [:libs lib] {:ns target :status :loading :path path :ok 0 :error 0 :skipped 0})
           (swap! (:renames (ctx)) assoc (str lib) (str target))
           (swap! state assoc :loading lib)
           (try (load-source! lib src from-ns)
                (catch #?(:cljs :default :jolt Throwable :clj Throwable :default Exception) e
                  (forget!)
                  (throw e))
                (finally (swap! state assoc :loading outer)))
           (when-not (contains? @(:loader-nses (ctx)) (str target))
             (forget!)
             (throw (host/ex* 'java.lang.Exception (str "namespace '" lib "' not found after loading '/" (lib-path lib) "'"))))
           (swap! state assoc-in [:libs lib :status] :loaded)
           target)
         (do (host/require-host! lib)
             (swap! state assoc-in [:libs lib] {:ns lib :status :host})
             lib))))))

(defn lib-report
  "{lib {:ns :status :ok :error :skipped :entries}} of every library loaded."
  []
  (dissoc (:libs @state) 'clojure.core))

(defn print-lib-report [lib]
  (let [{:keys [ns status ok error skipped entries]} (get (:libs @state) (symbol (str lib)))]
    (println (str "  " lib " -> " ns " (" (name (or status :absent)) "): "
                  (or ok 0) " forms evaluated, " (or skipped 0) " skipped, " (or error 0) " errors"))
    (doseq [e entries :when (= :error (:status e))]
      (println (str "     - " (:name e) ": " (:error e))))))

(defn read-in
  "Read `src` (one form) with *ns* bound to `ns-sym`, so syntax-quote resolves
   against the stock names."
  [ns-sym src]
  (host/with-ns ns-sym (fn [] (host/read-string* src))))

(defn eval-str
  "Read and eval every form of source text, each in the namespace the one
   before it left — starting where the last eval-str ended (cohost.user at
   first), as a REPL session does; -> the last value. opts {:session? false}
   evaluates in cohost.user without moving the session."
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
                                    (reset! at [v ns']))))))
       (first @at)
       (finally (when session? (reset! session-ns (second @at))))))))

;; ---------------------------------------------------------------------------
;; report

(defn print-report [{:keys [host-name ok replaced skipped error entries rewrite-misses] :as r}]
  (let [from-host (:host r)
        total (+ ok replaced from-host skipped error)]
    (println (str "cohost.boot on " (name host-name) ": " total " top-level forms"))
    (println (str "  booted as stock: " (+ ok replaced) "   from host: " from-host
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

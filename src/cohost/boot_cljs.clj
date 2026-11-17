(ns cohost.boot-cljs
  "Boot stock ClojureScript into a JavaScript realm on this (JVM) seat,
   the cljs twin of cohost.boot. Where the clj boot walks stock core.clj
   per-form -- policy, cohost.rewrite turning JVM interop into
   cohost.shim calls, the host evals the form into cohost.core -- this
   boot walks stock core.cljs and its related files per-form:

   - cohost.js-rewrite turns js/* interop into cohost.js-shim
     references (the shim is compiled into the realm first, unrewritten:
     its own js/* references are its host surface);
   - cljs.analyzer analyzes the form -- macros are core.cljc's, running
     unmodified on the JVM, exactly as in a stock cljs build;
   - cljs.compiler emits the form's JavaScript;
   - the realm evals it, in source order.

   The realm here is a GraalJS context in this process. Its only
   prepared globals are the ones Closure's base needs beyond a JS
   engine: window/self as the global object, a navigator stub, and
   CLOSURE_IMPORT_SCRIPT, the loader hook through which goog.require
   reaches the closure library on the classpath -- a cohost boot
   replaces the Closure loader with its own loading, so base.js's
   document/script-tag machinery is bypassed, never shimmed.

   The per-form loop is cljs.compiler/emit-source's, with the file
   replaced by the realm and the form by its rewrite."
  (:require [clojure.java.io :as io]
            [cljs.analyzer :as ana]
            [cljs.compiler :as comp]
            [cljs.env :as env]
            [cljs.vendor.clojure.tools.reader :as reader]
            [cohost.js-rewrite :as jrw])
  (:import [java.io StringWriter]
           [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]
           [java.util.concurrent.atomic AtomicLong]
           [org.graalvm.polyglot Context Value]
           [org.graalvm.polyglot.proxy ProxyExecutable]))

;; ---------------------------------------------------------------------------
;; the realm

(defn- classpath-text [resource]
  (when-let [url (io/resource resource)]
    (slurp url)))

(defn goog-text
  "The text of a goog dependency path (relative to goog/), or of the
   path as given, as a classpath resource. Public: a realm hosted in
   another process (the node seat) is served its goog files through
   this same resolution."
  [path]
  (let [rel (cond-> path
              (.startsWith path "../") (subs 3)
              (.startsWith path "./") (subs 2)
              (.startsWith path "/") (subs 1))]
    (or (classpath-text (str "goog/" rel))
        (classpath-text rel)
        (classpath-text path))))

(defn- load-file-hook
  "The realm's CLOSURE_IMPORT_SCRIPT, reached from JS as
   __cohostLoadFile(path, opt_contents). Plain dependencies are
   fetched and eval'd; transformed ones (goog.module) arrive with
   their contents as the second argument -- an empty path -- and the
   contents are eval'd instead. Answers whether the file was loaded."
  [^Context ctx]
  (reify ProxyExecutable
    (execute [_ args]
      (let [contents (when (and (> (alength args) 1) (not (.isNull ^Value (aget args 1))))
                       (.asString ^Value (aget args 1)))
            text (or contents (goog-text (str (aget args 0))))]
        (if text
          (do (.eval ctx "js" text) true)
          false)))))

(defn- read-file-hook
  "The realm's CLOSURE_LOAD_FILE_SYNC, reached from JS as
   __cohostReadFile(path): the file's contents, or nil. Transformed
   dependencies fetch their contents through this hook, transform
   them (a goog.module is wrapped in goog.loadModule), and hand the
   result back through CLOSURE_IMPORT_SCRIPT."
  [_ctx]
  (reify ProxyExecutable
    (execute [_ args]
      (goog-text (str (aget args 0))))))

(defn- timer-hooks
  "The realm's timer family, which a bare JS engine does not carry
   (setTimeout and kin are host functions, not ECMAScript): two host
   hooks over one daemon scheduler. __cohostSchedule(kind, fn, ms)
   answers an id; __cohostCancel(id) drops it. The preamble below
   spells them as the standard globals when the engine lacks them."
  []
  (let [exec (Executors/newSingleThreadScheduledExecutor
              (reify ThreadFactory
                (newThread [_ r]
                  (doto (Thread. ^Runnable r "cohost-cljs-realm-timer")
                    (.setDaemon true)))))
        counter (atom 0)
        timers (atom {})]
    {:schedule
     (reify ProxyExecutable
       (execute [_ args]
         (let [kind (str (aget args 0))
               f ^Value (aget args 1)
               ms (max 0 (.asLong ^Value (aget args 2)))
               id (swap! counter inc)
               task (reify Runnable
                      (run [_] (try (.execute f (object-array 0))
                                    (catch Throwable _ nil))))
               fut (if (= kind "interval")
                     (.scheduleAtFixedRate ^ScheduledExecutorService exec task ms ms TimeUnit/MILLISECONDS)
                     (.schedule ^ScheduledExecutorService exec task ms TimeUnit/MILLISECONDS))]
           (swap! timers assoc id fut)
           id)))
     :cancel
     (reify ProxyExecutable
       (execute [_ args]
         (let [id (.asLong ^Value (aget args 0))]
           (when-let [fut (get @timers id)]
             (.cancel fut false)
             (swap! timers dissoc id))
           nil)))}))

(def realm-preamble
  "The realm's prepared globals (see the namespace docstring)."
  (str "globalThis.CLOSURE_IMPORT_SCRIPT = function(src, contents) { return __cohostLoadFile(src, contents); };\n"
       "globalThis.CLOSURE_LOAD_FILE_SYNC = function(src) { return __cohostReadFile(src); };\n"
       "if (typeof window === 'undefined') { globalThis.window = globalThis; }\n"
       "if (typeof self === 'undefined') { globalThis.self = globalThis; }\n"
       "if (typeof navigator === 'undefined') { globalThis.navigator = {userAgent: 'GraalJS (cohost cljs realm)'}; }\n"
       "if (typeof setTimeout === 'undefined') {\n"
       "  globalThis.setTimeout = function(fn, ms) { return __cohostSchedule('timeout', fn, ms || 0); };\n"
       "  globalThis.clearTimeout = function(id) { __cohostCancel(id); };\n"
       "  globalThis.setInterval = function(fn, ms) { return __cohostSchedule('interval', fn, ms || 0); };\n"
       "  globalThis.clearInterval = function(id) { __cohostCancel(id); };\n"
       "}\n"))

(defn make-realm
  "A fresh GraalJS realm with the preamble run and Closure's base
   loaded (its dependency registry, deps.js, with it)."
  []
  (let [ctx (-> (Context/newBuilder (into-array String ["js"]))
                (.allowAllAccess true)
                (.build))
        bindings (.getBindings ctx "js")
        timers (timer-hooks)]
    (.putMember bindings "__cohostLoadFile" (load-file-hook ctx))
    (.putMember bindings "__cohostReadFile" (read-file-hook ctx))
    (.putMember bindings "__cohostSchedule" (:schedule timers))
    (.putMember bindings "__cohostCancel" (:cancel timers))
    (.eval ctx "js" realm-preamble)
    (doseq [r ["goog/base.js" "goog/deps.js"]]
      (if-let [text (classpath-text r)]
        (.eval ctx "js" text)
        (throw (ex-info (str "closure library resource missing: " r) {:resource r}))))
    ctx))

(defn eval-js!
  "Eval a JavaScript string in the realm; answer the completion value."
  [^Context ctx ^String js]
  (.eval ctx "js" js))

(defn eval-in
  "Eval a JavaScript string in a realm, whatever hosts it: a GraalJS
   Context in this process, or a transport map {:eval! (fn [js]
   value)} for a realm living in another process or engine (the node
   seat's sister builds one). Answers the completion value in the
   transport's terms (a polyglot Value for GraalJS; a plain value --
   string, number, boolean, nil -- for a transport map)."
  [realm ^String js]
  (cond
    (instance? Context realm) (.eval ^Context realm "js" js)
    (map? realm) ((:eval! realm) js)
    :else (throw (ex-info "not a cljs realm" {:realm realm}))))

;; ---------------------------------------------------------------------------
;; macro wrapping (stage 2)
;;
;; The per-form rewrite runs on source forms, before analysis. A macro
;; expansion materializes during analysis, after that rewrite, so a
;; js/* symbol the expansion introduces reaches the analyzer
;; unrewritten and is emitted as a raw realm global -- bypassing the
;; shim and its typeof-guarded roots (js/COMPILED, js/process throw
;; ReferenceError as raw globals on seats that lack them). On the JVM
;; seat core.cljc's own macros never do this (stage 1's whole-core
;; boot is the proof), but library and user macros can, and on a
;; self-hosted seat -- the compiler itself in the realm -- expansions
;; are the normal case, not the exception.
;;
;; The wrap: cljs.analyzer applies the macro var's root at expansion
;; time (macroexpand-1*), so interning a rewriting root on every macro
;; var the compiler env knows makes every expansion pass through
;; cohost.js-rewrite before analysis continues. Wrapping is per-realm
;; opt-in (boot!'s :wrap-macros), off by default: stage 1's behavior
;; is unchanged unless a realm asks.

(defonce ^:private wrapped-macros
  ;; macro vars whose roots already rewrite their expansions (macro
  ;; vars are JVM-side and shared by every realm in this process, so
  ;; the set is too)
  (atom #{}))

(defonce ^:private macro-ns-scanned
  ;; [ns-sym intern-count] pairs already scanned by wrap-loaded-macros!:
  ;; a namespace is re-scanned only when its interns change (a macro
  ;; namespace loading or reloading mid-boot)
  (atom #{}))

(defonce ^:private wrap-macros-realms
  ;; realms that opted into macro wrapping
  (atom #{}))

(defn wrap-macros-enabled?
  [realm]
  (contains? @wrap-macros-realms realm))

(defn enable-macro-wrapping!
  "Opt a realm into macro wrapping (see the section comment)."
  [realm]
  (swap! wrap-macros-realms conj realm)
  realm)

(defn- wrap-macro-var!
  [^clojure.lang.Var v]
  (when (and (.isMacro v) (not (contains? @wrapped-macros v)))
    (let [orig @v]
      (when (ifn? orig)
        ;; expansions are rewritten with jrw/rewrite-expansion, not
        ;; the source rewrite: a js* form in an expansion is the
        ;; compiler's own contract and stays verbatim (see
        ;; cohost.js-rewrite's docstring for the measured case)
        (alter-var-root v (fn [_] (fn [& args] (jrw/rewrite-expansion (apply orig args)))))
        (swap! wrapped-macros conj v)))))

(defn wrap-loaded-macros!
  "Wrap every macro var of every macro namespace the ambient compiler
   env has interned and not yet scanned at its current intern count.
   Called before each analysis in a wrapping realm, so a macro
   namespace loaded by one form is wrapped before the next form --
   which may already use it -- is analyzed."
  []
  (when env/*compiler*
    (doseq [[ns-sym ns-map] (:cljs.analyzer/namespaces @env/*compiler*)]
      (when (contains? ns-map :macros)
        (when-let [jvm-ns (find-ns ns-sym)]
          (let [interns (ns-interns jvm-ns)
                scan-key [ns-sym (count interns)]]
            (when-not (contains? @macro-ns-scanned scan-key)
              (swap! macro-ns-scanned conj scan-key)
              (doseq [[_ v] interns]
                (wrap-macro-var! v)))))))))

;; ---------------------------------------------------------------------------
;; the per-form loop (cljs.compiler/emit-source's shape)

(def compiler-opts
  {:optimizations :none
   :language-in :ecmascript-2020
   :language-out :ecmascript-2020})

(declare compiler-env)
(declare realm-envs)

(defn- emit-string
  "Emit one analyzed form to its JavaScript string."
  [ast]
  (let [sw (StringWriter.)]
    (binding [*out* sw]
      (comp/emit ast))
    (str sw)))

(defn- compile-reader!
  "The per-form loop over rdr (named name for errors): analyze each
   form (rewritten when rewrite?), emit it, eval it in the realm, in
   order. Answers the namespace the source defined."
  [realm rdr name rewrite?]
  ;; the analyzer's state lives in env/*compiler*: boot! binds it for
  ;; its own files; a later compile-source! (a library, say) rebinds
  ;; it from the realm's persisted env
  (if-not env/*compiler*
    (env/with-compiler-env (or (get @realm-envs realm) @compiler-env)
      (compile-reader! realm rdr name rewrite?))
    (binding [ana/*cljs-ns* 'cljs.user
            ana/*cljs-file* name
            ;; the bindings the stock entry points (compile-file,
            ;; analyze-form-seq) establish: core.cljs's own
            ;; (set! *unchecked-arrays* true) lands in the analyzer's
            ;; dynamic vars, which need thread bindings to be set!
            ana/*file-defs* (atom #{})
            ana/*unchecked-if* false
            ana/*unchecked-arrays* false
            ana/*cljs-warnings* ana/*cljs-warnings*
            reader/*alias-map* (or (ana/get-bridged-alias-map) reader/*alias-map* {})
            comp/*source-map-data* nil
            comp/*source-map-data-gen-col* (AtomicLong.)]
    (let [env (ana/empty-env)]
      (loop [forms (ana/forms-seq* rdr name)
             ns-name nil]
        (if (seq forms)
          (let [env (assoc env :ns (ana/get-namespace ana/*cljs-ns*))
                form (first forms)
                form (if rewrite? (jrw/rewrite form) form)]
            (when (wrap-macros-enabled? realm)
              (wrap-loaded-macros!))
            (let [ast (try (ana/analyze env form nil compiler-opts)
                           (catch Throwable t
                             (throw (ex-info (str "analysis failed in " name ": " (pr-str form))
                                             {:resource name :form form} t))))
                  js (emit-string ast)]
              (try (eval-in realm js)
                   (catch Throwable t
                     (throw (ex-info (str "realm eval failed in " name ": " (pr-str form))
                                     {:resource name :form form :js js} t))))
              (recur (rest forms) (if (= :ns (:op ast)) (:name ast) ns-name))))
          (or ns-name 'cljs.user)))))))

(defn compile-source!
  "Analyze, emit and realm-eval every form of the source at resource,
   in order. rewrite? selects the js-shim rewrite (on for stock cljs
   sources, off for the shim itself). Answers the namespace defined."
  [realm resource rewrite?]
  (let [url (io/resource resource)]
    (when-not url
      (throw (ex-info (str "cljs source resource missing: " resource) {:resource resource})))
    (with-open [rdr (io/reader url)]
      (compile-reader! realm rdr resource rewrite?))))

(defn compile-string!
  "compile-reader! over a source string."
  [realm source name rewrite?]
  (compile-reader! realm (java.io.StringReader. source) name rewrite?))

;; ---------------------------------------------------------------------------
;; the boot

(def related-files
  "core.cljs's related files, in dependency order."
  ["clojure/string.cljs"
   ;; cljs.reader is a wrapper over cljs.tools.reader (from the
   ;; tools.reader jar): its family boots first, leaves up
   "cljs/tools/reader/impl/inspect.cljs"
   "cljs/tools/reader/impl/utils.cljs"
   "cljs/tools/reader/impl/commons.cljs"
   "cljs/tools/reader/reader_types.cljs"
   "cljs/tools/reader/impl/errors.cljs"
   "cljs/tools/reader.cljs"
   "cljs/tools/reader/edn.cljs"
   "cljs/reader.cljs"
   "clojure/set.cljs"
   "clojure/data.cljs"
   "clojure/walk.cljs"])

(defonce ^:private compiler-env
  ;; the analyzer's state for the most recently booted realm: kept for
  ;; stage 1's callers; realm-envs below is the per-realm record
  (atom nil))

(defonce ^:private realm-envs
  ;; realm -> its compiler env. A realm's analyzer state is its own:
  ;; two realms booted in one process (a GraalJS realm and a node
  ;; realm, say) hold separate namespace tables, and each realm's
  ;; forms must be analyzed against the env its own boot built.
  (atom {}))

(defonce ^:private user-ns-realms
  ;; realms whose cljs.user namespace object exists (goog.provide ran
  ;; there): the analyzer learns cljs.user from any file's loop, but
  ;; the realm only gets the object from an emitted ns form
  (atom #{}))

(defn boot!
  "Boot the shim, stock core.cljs and its related files into realm
   (a fresh GraalJS realm by default; a transport map is a realm in
   another engine -- see eval-in). Answers the realm.

   opts: :wrap-macros -- rewrite macro expansions too (stage 2; see
   the macro-wrapping section). Off by default."
  ([] (boot! (make-realm)))
  ([realm] (boot! realm {}))
  ([realm {:keys [wrap-macros] :as _opts}]
   (let [cenv (env/default-compiler-env compiler-opts)]
     (reset! compiler-env cenv)
     (swap! realm-envs assoc realm cenv)
     (when wrap-macros
       (enable-macro-wrapping! realm))
     (env/with-compiler-env cenv
       (compile-source! realm "cohost/js_shim.cljs" false)
       (compile-source! realm "cljs/core.cljs" true)
       (doseq [r related-files]
         (compile-source! realm r true))
       (compile-string! realm "(ns cljs.user)" "<user-ns>" false)
       (swap! user-ns-realms conj realm)
       realm))))

(defn eval-form
  "Analyze form (a read cljs form, not a string) in namespace ns-sym
   against realm's compiler env, emit it and eval it in the realm.
   The form goes through the js-shim rewrite, like any stock source.
   Answers the realm value in the transport's terms (see eval-in).
   This is the entry the runtime-executor sister (cohost.cljs-pool)
   is built on: work crosses as a form, per the layer's contract."
  [realm ns-sym form]
  (let [cenv (or (get @realm-envs realm) @compiler-env)]
    (when-not cenv
      (throw (ex-info "eval-form before boot!" {})))
    (binding [env/*compiler* cenv]
      (when (wrap-macros-enabled? realm)
        (wrap-loaded-macros!))
      (binding [ana/*cljs-ns* ns-sym
                ana/*cljs-file* "<eval-form>"]
        (let [env (assoc (ana/empty-env) :ns (ana/get-namespace ns-sym))
              ast (ana/analyze env (jrw/rewrite form) nil compiler-opts)]
          (eval-in realm (emit-string ast)))))))

(defn eval-cljs
  "Compile and eval one cljs form (a string) in the booted realm's
   cljs.user, answering the realm value. The form goes through the
   js-shim rewrite, like any stock source."
  [realm form-str]
  (when-not (or (get @realm-envs realm) @compiler-env)
    (throw (ex-info "eval-cljs before boot!" {})))
  (binding [env/*compiler* (or (get @realm-envs realm) @compiler-env)]
    (when-not (contains? @user-ns-realms realm)
      (compile-string! realm "(ns cljs.user)" "<user-ns>" false)
      (swap! user-ns-realms conj realm))
    (binding [ana/*cljs-ns* 'cljs.user
              ana/*cljs-file* "<eval>"]
      (let [env (assoc (ana/empty-env) :ns (ana/get-namespace 'cljs.user))
            form (jrw/rewrite (reader/read-string form-str))]
        (when (wrap-macros-enabled? realm)
          (wrap-loaded-macros!))
        (let [ast (ana/analyze env form nil compiler-opts)]
          (eval-in realm (emit-string ast)))))))

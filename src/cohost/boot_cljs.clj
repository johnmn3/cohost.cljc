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

(defn- goog-text
  "The text of a goog dependency path (relative to goog/), or of the
   path as given, as a classpath resource."
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

;; ---------------------------------------------------------------------------
;; the per-form loop (cljs.compiler/emit-source's shape)

(def compiler-opts
  {:optimizations :none
   :language-in :ecmascript-2020
   :language-out :ecmascript-2020})

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
  [^Context ctx rdr name rewrite?]
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
                form (if rewrite? (jrw/rewrite form) form)
                ast (try (ana/analyze env form nil compiler-opts)
                         (catch Throwable t
                           (throw (ex-info (str "analysis failed in " name ": " (pr-str form))
                                           {:resource name :form form} t))))
                js (emit-string ast)]
            (try (eval-js! ctx js)
                 (catch Throwable t
                   (throw (ex-info (str "realm eval failed in " name ": " (pr-str form))
                                   {:resource name :form form :js js} t))))
            (recur (rest forms) (if (= :ns (:op ast)) (:name ast) ns-name)))
          (or ns-name 'cljs.user))))))

(defn compile-source!
  "Analyze, emit and realm-eval every form of the source at resource,
   in order. rewrite? selects the js-shim rewrite (on for stock cljs
   sources, off for the shim itself). Answers the namespace defined."
  [^Context ctx resource rewrite?]
  (let [url (io/resource resource)]
    (when-not url
      (throw (ex-info (str "cljs source resource missing: " resource) {:resource resource})))
    (with-open [rdr (io/reader url)]
      (compile-reader! ctx rdr resource rewrite?))))

(defn compile-string!
  "compile-reader! over a source string."
  [^Context ctx source name rewrite?]
  (compile-reader! ctx (java.io.StringReader. source) name rewrite?))

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
  ;; the analyzer's state for the booted realm: cljs.core's defs live
  ;; here, so eval-cljs must analyze against this same env
  (atom nil))

(defonce ^:private user-ns-realms
  ;; realms whose cljs.user namespace object exists (goog.provide ran
  ;; there): the analyzer learns cljs.user from any file's loop, but
  ;; the realm only gets the object from an emitted ns form
  (atom #{}))

(defn boot!
  "Boot the shim, stock core.cljs and its related files into realm
   (a fresh one by default). Answers the realm."
  ([] (boot! (make-realm)))
  ([^Context ctx]
   (let [cenv (env/default-compiler-env compiler-opts)]
     (reset! compiler-env cenv)
     (env/with-compiler-env cenv
       (compile-source! ctx "cohost/js_shim.cljs" false)
       (compile-source! ctx "cljs/core.cljs" true)
       (doseq [r related-files]
         (compile-source! ctx r true))
       (compile-string! ctx "(ns cljs.user)" "<user-ns>" false)
       (swap! user-ns-realms conj ctx)
       ctx))))

(defn eval-cljs
  "Compile and eval one cljs form (a string) in the booted realm's
   cljs.user, answering the realm value. The form goes through the
   js-shim rewrite, like any stock source."
  [^Context ctx form-str]
  (when-not @compiler-env
    (throw (ex-info "eval-cljs before boot!" {})))
  (binding [env/*compiler* @compiler-env]
    (when-not (contains? @user-ns-realms ctx)
      (compile-string! ctx "(ns cljs.user)" "<user-ns>" false)
      (swap! user-ns-realms conj ctx))
    (binding [ana/*cljs-ns* 'cljs.user
              ana/*cljs-file* "<eval>"]
      (let [env (assoc (ana/empty-env) :ns (ana/get-namespace 'cljs.user))
            form (jrw/rewrite (reader/read-string form-str))
            ast (ana/analyze env form nil compiler-opts)]
        (eval-js! ctx (emit-string ast))))))

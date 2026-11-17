(ns cohost.sci-pool
  "The JVM seat's sister for cohost.thread's runtime executors: SCI,
   the Small Clojure Interpreter — the runtime the bb sister is built
   on (bb's JVM form). A pool's realm is one SCI context: its own
   namespaces and vars, hosted on the pool's worker threads (a worker
   thread enters the realm by evaluating the item's form in it). Work
   crosses as forms + data, per the layer's realm contract; the realm's
   namespaces load from source roots through SCI's :load-fn, once, at
   boot.

   The host adapter must be installed before an executor is built
   (require cohost.hosts.auto, as everywhere in cohost)."
  (:require [cohost.thread :as thread]
            [sci.core :as sci]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- ns->path [libname]
  (-> (str libname) (str/replace \- \_) (str/replace \. \/)))

(defn- root-loader
  "SCI :load-fn over source roots: <root>/<ns path>.clj, then .cljc
   (SCI processes the file's reader conditionals from the context's
   :features — the reading the bb seat's own sources get)."
  [roots]
  (fn [{:keys [libname]}]
    (some (fn [root]
            (let [base (str root "/" (ns->path libname))]
              (some (fn [ext]
                      (let [f (io/file (str base ext))]
                        (when (.isFile f)
                          {:file (.getPath f) :source (slurp f)})))
                    [".clj" ".cljc"])))
          roots)))

(defn sci-sister
  "The SCI sister impl map for cohost.thread/runtime-executor.
   Boot options (the executor's opts, handed to :boot!):
     :roots     [source-root ...] — where the realm's namespaces load from
     :require   [ns-sym ...]      — namespaces loaded at boot, under the
                                    boot lock, before any worker serves
     :features  reader-conditional features for the realm's reading
                (default #{:clj :bb} — the bb seat's reading)
     :classes   SCI :classes map (default: java.lang.Thread, so realm
                code can name its worker thread; pass more as needed)
     :unrestricted  SCI :unrestricted — realm code may mutate built-in
                vars (the bb seat's own mode: Eve's bb completion
                alter-var-roots clojure.core/deftype at load). Default
                off; a realm that hosts bb-shaped code opts in.
     :deftype-fn  SCI :deftype-fn hook (bb supplies its own:
                babashka.impl.deftype/deftype-fn, which answers a
                SciMap constructor for map-interface deftypes)
     :namespaces, :allow, :deny — passed through to sci/init."
  []
  {:boot! (fn [{:keys [roots require features classes] :as opts}]
            (let [ctx (sci/init
                       (merge {:load-fn (root-loader roots)
                               :features (or features #{:clj :bb})
                               :classes (merge {'java.lang.Thread Thread}
                                               classes)}
                              (select-keys opts [:namespaces :allow :deny
                                                 :unrestricted :deftype-fn])))]
              (doseq [ns-sym require]
                (sci/eval-string* ctx (str "(require '" ns-sym ")")))
              ctx))
   ;; the item's form is data by construction (symbols + quoted data),
   ;; so it crosses as text and is read by the realm's own reader, in
   ;; the item's namespace
   :eval-form (fn [ctx ns-sym form]
                (:val (sci/eval-string+ ctx
                                        (str "(in-ns '" ns-sym ") " (pr-str form))
                                        {})))
   ;; an SCI context is garbage-collected with its realm; no close
   :close! (fn [_ctx] nil)})

(defn executor
  "A runtime executor of `size` workers over an SCI realm:
   (executor 1 {:roots [\"src\"] :require [my.ns]}) — a single-thread
   pool in the sister; (executor 4 ...) a four-thread one. Submit with
   cohost.thread/submit-call and submit-form; read results with
   cohost.thread/deref-future."
  [size & {:as opts}]
  (thread/runtime-executor (sci-sister) size opts))

(ns cohost.hosts.vs
  "ValueScript as a cohost host. Every capability is VS machinery that already
   exists: the reader is vs.reader (its forms made data by the core's own
   form-value conversion), eval / load-string / in-ns / refer / require are the
   runtime compiler's (vs.repl.session, the `\" vs-repl\"` var the core's eval
   family calls), namespaces and vars are the program's own table (one registry:
   the session's defs and namespaces are interned there, vs.repl.eval
   mirror-def!), constructors and predicates are the core's, time and files are
   the WASI arms. Macro status is the analyzer's user-macro table: mark-macro!
   registers the var's name with a stamp and roots the var in a fn that answers
   the stamp (THE STAMP ARITY, analyzer.vs mx-var-fn), the shape a defmacro the
   analyzer compiles has; alter-var-root! keeps that stamp on a registered macro
   so cohost's expansion wrapper (boot.cljc wrap-macro) stays a macro."
  (:require [cohost.adapter :as adapter]
            [clojure.string :as string]
            [vs.reader :as rdr]))

;; ---------------------------------------------------------------------------
;; forms: the reader's form values as data (symbol metadata rides along)

(defn- read-forms [src cb]
  (doseq [f (rdr/read-string-all src nil)]
    (cb (vs.core/prog-h-misc-form-val f))))

;; ---------------------------------------------------------------------------
;; namespaces: the runtime compiler's verbs through eval; the program's table
;; for every read (one registry)

(defn- q [x] (list 'quote x))

(defn- current-ns [] (symbol (str (eval '*ns*))))

(defn- with-ns [ns-sym thunk]
  (let [prev (current-ns)]
    (in-ns ns-sym)
    (try (thunk) (finally (in-ns prev)))))

(defn- ensure-bare-ns! [ns-sym]
  (with-ns (current-ns)
    (fn [] (eval (list 'ns ns-sym '(:refer-clojure :only [])))))
  (the-ns ns-sym))

;; the core as the session names it (vs.core) and as the program's table names it (clojure.core) are one core
(def ^:private session-core 'vs.core)

(defn- refer-from-core! [into-ns sym]
  (with-ns into-ns (fn [] (eval (list 'refer (q session-core) :only (q [sym]))))))

(defn- refer-all! [into-ns from-ns]
  (with-ns into-ns (fn [] (eval (list 'refer (q from-ns))))))

(defn- refer! [into-ns from-ns {:keys [only exclude rename]}]
  (with-ns into-ns
    (fn [] (eval (concat (list 'refer (q from-ns))
                         (when only [:only (q (vec only))])
                         (when exclude [:exclude (q (vec exclude))])
                         (when rename [:rename (q rename)]))))))

(defn- alias! [into-ns a target]
  (with-ns into-ns (fn [] (eval (list 'alias (q a) (q target))))))

(defn- require-host! [lib] (eval (list 'require (q lib))))

;; ---------------------------------------------------------------------------
;; macro status: the analyzer's user-macro table + THE STAMP ARITY

(defn- var-qname [v] (str (:ns (meta v)) "/" (:name (meta v))))

(defn- stamped
  "f answering stamp when called with the one argument nil (mx-var-fn's check), f otherwise."
  [f stamp]
  (fn
    ([x] (if (nil? x) stamp (f x)))
    ([a b] (f a b))
    ([a b & more] (apply f a b more))))

(defn- mark-macro! [v]
  (let [qn (var-qname v)
        stamp (vs.analyzer-scope/-name-hash qn)]
    (vs.analyzer/mx-register! qn stamp)
    (alter-var-root v (fn [f] (stamped f stamp)))
    (alter-meta! v assoc :macro true)
    v))

(defn- alter-var-root! [v f]
  (alter-var-root v f)
  (let [stamp (vs.analyzer/mx-lookup (var-qname v))]
    (when-not (nil? stamp)
      (alter-var-root v (fn [g] (stamped g stamp)))))
  v)

;; ---------------------------------------------------------------------------

(def adapter
  {:name :vs
   :core-ns 'clojure.core
   :catch-all 'Throwable
   :read-forms read-forms
   :ensure-bare-ns! ensure-bare-ns!
   :refer-from-core! refer-from-core!
   :refer-all! refer-all!
   :refer! refer!
   :alias! alias!
   :require-host! require-host!
   :resolve-core (fn [sym] (ns-resolve 'clojure.core sym))
   :interned-var (fn [ns-sym sym] (when (find-ns ns-sym) (get (ns-interns ns-sym) sym)))
   :ns-interns (fn [ns-sym] (ns-interns ns-sym))
   :ns-public-names (fn [ns-sym] (set (keys (ns-publics ns-sym))))
   :intern! (fn [ns-sym sym v] (intern ns-sym sym v))
   :current-ns current-ns
   :with-ns with-ns
   :set-current-ns! (fn [ns-sym] (in-ns ns-sym) nil)
   :eval (fn [form] (eval form))
   :macroexpand-1 (fn [form] (macroexpand-1 form))
   :mark-macro! mark-macro!
   :alter-var-root! alter-var-root!
   :var-raw-root (fn [v] (var-get v))
   :var-bound? (fn [v] (adapter/try-all (fn [] (var-get v) true) (fn [_] false)))
   :namespace? (fn [x] (instance? clojure.lang.Namespace x))
   :var? (fn [x] (instance? clojure.lang.Var x))
   :lazy-seq (fn [f] (lazy-seq* f))
   :delay (fn [f] (delay* f))
   :exception? (fn [x] (instance? Throwable x))
   :transient? (fn [x] (transient? x))
   :lazy-seq? (fn [x] (instance? clojure.lang.LazySeq x))
   :atom? (fn [x] (atom? x))
   :delay? (fn [x] (delay? x))
   :reduced? (fn [x] (reduced? x))
   :deref? (fn [x] (instance? clojure.lang.IDeref x))
   :pending? (fn [x] (instance? clojure.lang.IPending x))
   :char? (fn [x] (char? x))
   :regex? (fn [x] (instance? java.util.regex.Pattern x))
   :uuid? (fn [x] (uuid? x))
   :sorted? (fn [x] (sorted? x))
   :multifn? (fn [x] (instance? clojure.lang.MultiFn x))
   :nano-time (fn [] (System/nanoTime))
   :current-time-millis (fn [] (System/currentTimeMillis))
   :math-ceil (fn [x] (Math/ceil x))
   :math-floor (fn [x] (Math/floor x))
   :math-sqrt (fn [x] (Math/sqrt x))
   :parse-long (fn [s] (adapter/try-all (fn [] (Long/parseLong s)) (fn [_] nil)))
   :parse-double (fn [s] (Double/parseDouble s))
   :nan? (fn [x] (and (number? x) (Double/isNaN (double x))))
   :infinite? (fn [x] (and (number? x) (Double/isInfinite (double x))))
   :format (fn [fmt & args] (apply format fmt args))
   :index-of (fn [s sub from] (.indexOf s sub from))
   :upper-case (fn [s] (string/upper-case s))
   :lower-case (fn [s] (string/lower-case s))
   :slurp (fn [path] (slurp path))
   :spit (fn [path s] (spit path s))})

(adapter/install! adapter)

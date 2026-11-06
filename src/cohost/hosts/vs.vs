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
            [cohost.hosts.vs-core-fns :as core-fns]
            [clojure.string :as string]
            [vs.reader :as rdr]))

;; ---------------------------------------------------------------------------
;; the core's vars, rooted: VS resolves a core var by name at run time (ns-resolve, resolve) to a var with no root —
;; `@(resolve 'inc)` is nil (MEASURED 2026-11-06) — while a fn referenced by name in compiled code is the value. The
;; host's core is the program's own table (one registry), so each name of cohost.hosts.vs-core-fns (the vocabulary's
;; fns, generated) roots its var once, at install; resolve-core answers only those names and the analyzer's own
;; macros, so a name VS lacks (bigdec, rationalize) boots stock's definition, as cohost's policy expects.

(defn- root-core-vars! []
  (doseq [[n f] core-fns/fns]
    (when-let [v (ns-resolve 'clojure.core (symbol n))]
      (when (nil? (var-get v)) (alter-var-root v (constantly f))))))

(defn- core-macro? [sym] (boolean (vs.macros/builtin-macro-name? (name sym))))

(defn- resolve-core [sym]
  (when (or (contains? core-fns/fns (name sym)) (core-macro? sym))
    (ns-resolve 'clojure.core sym)))

(defn- macro-var? [v]
  (or (true? (:macro (meta v)))
      (and (= 'clojure.core (:ns (meta v))) (core-macro? (:name (meta v))))))

;; ---------------------------------------------------------------------------
;; forms: the reader's form values as data (symbol metadata rides along)

;; `#_` discards are removed from the text first: VS's run-time reader (vs.reader, the corpus reader a program carries)
;; mis-reads a discarded number, string or list inside a collection — `(vs.reader/read-string-all "(do #_1 2)" nil)`
;; answers a form whose form-count trips, where `#_x` (a symbol) reads right and the compiler's own reader reads every
;; discard right (MEASURED 2026-11-06 on main's builder; docs/vs-cohost/STATUS.md names it). Stock core.clj's `for`
;; carries two (`#_"not the inner-most loop"`). The scan is the reader's own token rules: strings, character
;; literals, comments, the delimiters; a discarded form's own discards are skipped with it.
(defn- skip-ws [s i n]
  (loop [i i] (if (and (< i n) (let [c (nth s i)] (or (= c \space) (= c \newline) (= c \tab) (= c \return) (= c \,)))) (recur (inc i)) i)))
(declare skip-form)
(defn- skip-string [s i n]                       ;; i at the opening quote -> the index after the closing one
  (loop [i (inc i)]
    (cond (>= i n) n
          (= (nth s i) \\) (recur (+ i 2))
          (= (nth s i) \") (inc i)
          :else (recur (inc i)))))
(defn- skip-token [s i n]
  (loop [i i] (if (and (< i n) (let [c (nth s i)] (not (or (= c \space) (= c \newline) (= c \tab) (= c \return) (= c \,)
                                                             (= c \() (= c \)) (= c \[) (= c \]) (= c \{) (= c \}) (= c \") (= c \;)))))
                (recur (inc i)) i)))
(defn- skip-coll [s i n]                         ;; i at the opener -> the index after its closer
  (loop [i (inc i)]
    (if (>= i n) n
      (let [c (nth s i)]
        (cond (or (= c \)) (= c \]) (= c \})) (inc i)
              (= c \;) (recur (let [j (loop [j i] (if (and (< j n) (not= (nth s j) \newline)) (recur (inc j)) j))] j))
              (= c \space) (recur (inc i))
              :else (recur (skip-form s i n)))))))
(defn- skip-form [s i n]                         ;; i at a form's first byte (no leading whitespace) -> the index after it
  (let [c (nth s i)]
    (cond (= c \") (skip-string s i n)
          (= c \\) (skip-token s (+ i 2) n)
          (= c \;) (loop [j i] (if (and (< j n) (not= (nth s j) \newline)) (recur (inc j)) j))
          (or (= c \() (= c \[) (= c \{)) (skip-coll s i n)
          (and (= c \#) (< (inc i) n) (= (nth s (inc i)) \_)) (skip-form s (skip-ws s (+ i 2) n) n)
          (and (= c \#) (< (inc i) n) (= (nth s (inc i)) \")) (skip-string s (inc i) n)
          (and (= c \#) (< (inc i) n) (or (= (nth s (inc i)) \() (= (nth s (inc i)) \{))) (skip-coll s (inc i) n)
          (or (= c \') (= c \`) (= c \~) (= c \@) (= c \^) (= c \#)) (skip-form s (skip-ws s (inc i) n) n)
          :else (skip-token s i n))))
(defn strip-discards [s]
  (let [n (count s)]
    (loop [i 0 out (StringBuilder.)]
      (if (>= i n)
        (str out)
        (let [c (nth s i)]
          (cond (= c \") (let [j (skip-string s i n)] (recur j (.append out (subs s i j))))
                (= c \\) (let [j (skip-token s (+ i 2) n)] (recur j (.append out (subs s i j))))
                (= c \;) (let [j (loop [j i] (if (and (< j n) (not= (nth s j) \newline)) (recur (inc j)) j))] (recur j (.append out (subs s i j))))
                (and (= c \#) (< (inc i) n) (= (nth s (inc i)) \_)) (recur (skip-form s (skip-ws s (+ i 2) n) n) out)
                :else (recur (inc i) (.append out c))))))))

(defn- read-forms [src cb]
  (doseq [f (rdr/read-string-all (strip-discards src) nil)]
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
    ;; a var with no fn root is one of the analyzer's own macros taken from the host (cohost interns the host's
    ;; macro var and marks it): the analyzer expands it by name already; registering it would route the call to nil
    (when (fn? (var-get v))
      (vs.analyzer/mx-register! qn stamp)
      (alter-var-root v (fn [f] (stamped f stamp))))
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
   :resolve-core resolve-core
   :macro-var? macro-var?
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

(root-core-vars!)
(adapter/install! adapter)

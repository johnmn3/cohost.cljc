(ns cohost.hosts.jolt
  "The jolt adapter (Clojure on Chez Scheme). Jolt ships a java.* shim of its
   own and clojure.lang.* classes with their JVM names, so this is nearly the
   JVM adapter; what differs is macro status, which jolt keeps on the Var
   (`.isMacro` / `.setMacro`) rather than in metadata."
  (:require [cohost.adapter :as adapter]
            [cohost.hosts.java :as java]))

(defn- read-forms [src cb]
  (let [r (java.io.PushbackReader. (java.io.StringReader. src))]
    (loop []
      (let [f (read {:eof ::eof :read-cond :allow} r)]
        (when-not (= f ::eof) (cb f) (recur))))))

(def adapter
  {:name :jolt
   ;; X3-1: the shim's MultiFn with its one-entry cache (adapter.cljc); bench:jolt
   ;; --quick, 9 interleaved rounds on max/main aeac81b: multimethod stock 20.19
   ;; -> 18.29 ms (on 5fd3e3e: 15.16 -> 13.47), no case outside its spread,
   ;; battery 149/149 and libraries 61/61 both ways
   :multimethod-cache? true
   :core-ns 'clojure.core
   :catch-all 'Throwable
   :read-forms read-forms
   :ensure-bare-ns! (fn [ns-sym]
                      (binding [*ns* *ns*]
                        (eval (list 'ns ns-sym '(:refer-clojure :only [])))
                        (the-ns ns-sym)))
   :refer-from-core! (fn [into-ns sym]
                       (binding [*ns* (the-ns into-ns)]
                         (ns-unmap into-ns sym)
                         (refer 'clojure.core :only [sym])))
   :resolve-core (fn [sym] (get (ns-publics 'clojure.core) sym))
   :macro-var? (fn [v] (.isMacro v))
   :mark-macro! (fn [v] (do (. v (setMacro)) (alter-meta! v assoc :macro true)) v)
   :var-raw-root (fn [v] (.getRawRoot v))
   :set-dynamic! (fn [v] (.setDynamic v) (alter-meta! v assoc :dynamic true) v)
   :dynamic-var? (fn [v] (boolean (or (.isDynamic v) (:dynamic (meta v)))))
   :empty-queue (fn [] clojure.lang.PersistentQueue/EMPTY)
   :namespace? (fn [x] (instance? clojure.lang.Namespace x))
   :var-bound? (fn [v] (bound? v))
   :var-thread-bound? (fn [v] (thread-bound? v))
   ;; what jolt's own lazy-seq expands to, not (clojure.lang.LazySeq. f): no
   ;; host-new lookup and apply per lazy seq. The wrapper is no ^:once fn: a
   ;; force after a failure calls the stock thunk again (itself ^:once, so it
   ;; sees its own cleared captures, as on the JVM)
   :lazy-seq (fn [f] (make-lazy-seq (fn* [] (coll->cells (f)))))
   :delay (fn [f] (clojure.lang.Delay. f))
   :exception? (fn [x] (instance? Throwable x))
   :nano-time (fn [] (System/nanoTime))
   :current-time-millis (fn [] (System/currentTimeMillis))
   :math-ceil (fn [x] (Math/ceil x))
   :math-floor (fn [x] (Math/floor x))
   :math-sqrt (fn [x] (Math/sqrt x))
   :parse-long (fn [s] (try (Long/parseLong s) (catch NumberFormatException _ nil)))
   :parse-double (fn [s] (Double/parseDouble s))
   :nan? (fn [x] (and (number? x) (Double/isNaN (double x))))
   :infinite? (fn [x] (and (number? x) (Double/isInfinite (double x))))
   :format (fn [fmt & args] (apply format fmt args))
   :sort-array! (fn [arr cmp] (java.util.Arrays/sort arr cmp) arr)
   :close! (fn [x] (.close x))
   :index-of (fn [s sub from] (.indexOf s sub (int from)))
   ;; jolt's own transient? is one record test; its instance? of the interface
   ;; walks every registered class arm (a deftype implementing it still answers)
   :transient? (fn [x] (or (transient? x) (instance? clojure.lang.ITransientCollection x)))
   ;; jolt's compiler ignores :inline; the codemod applies core.clj's
   :rewrite-inline? true
   ;; jolt's own predicates are one record or tag test each; its class walks the
   ;; registered class arms (0.3 µs for a transient, an atom)
   ;; (not vector? or fn?: jolt answers both for a deftype that implements
   ;; IPersistentVector or Fn, whose own members must still answer)
   :host-data? (fn [x] (or (transient? x) (atom? x) (string? x) (number? x)
                          (keyword? x) (symbol? x) (boolean? x)))
   :lazy-seq? (fn [x] (instance? clojure.lang.LazySeq x))
   :atom? (fn [x] (instance? clojure.lang.Atom x))
   :volatile? (fn [x] (volatile? x))
   :delay? (fn [x] (delay? x))
   :reduced? (fn [x] (reduced? x))
   :deref? (fn [x] (instance? clojure.lang.IDeref x))
   :pending? (fn [x] (instance? clojure.lang.IPending x))
   :char? (fn [x] (char? x))
   :regex? (fn [x] (instance? java.util.regex.Pattern x))
   :uuid? (fn [x] (uuid? x))
   :bigint? (fn [x] (or (instance? clojure.lang.BigInt x) (instance? java.math.BigInteger x)))
   :ratio? (fn [x] (ratio? x))
   :decimal? (fn [x] (decimal? x))
   :indexed? (fn [x] (indexed? x))
   :counted? (fn [x] (counted? x))
   :reversible? (fn [x] (reversible? x))
   :sorted? (fn [x] (sorted? x))
   ;; jolt's .comparator is a comparator object, not an IFn
   :sorted-comparator (fn [x] (let [c (.comparator x)] (if (ifn? c) c (fn [a b] (.compare c a b)))))
   :multifn? (fn [x] (instance? clojure.lang.MultiFn x))})

;; java.lang.Math.log10 promises log10(10^n) = n exactly; jolt's returns
;; 2.9999999999999996 for 1000.0, so an exact power of ten is answered exactly
(defn- log10 [x]
  (let [x (double x) r (Math/log10 x) n (Math/rint r)]
    (if (and (< (Math/abs (- r n)) 1e-9) (== x (Math/pow 10.0 n))) n r)))

;; a member by name: jolt's own `.` form (a field or a method), compiled once
;; per [name arity]; jolt reports a missing member as an
;; IllegalArgumentException "No matching field/method ..."
(def ^:private members (atom {}))
(defn- invoke-member [x nm args absent]
  (let [n (count args) k [nm n]
        ;; compiled with fn* and `.` (special forms) in this namespace: in a
        ;; cohost namespace `fn` is the stock macro, whose output the codemod
        ;; rewrites back into a dispatcher call — a loop
        f (or (get @members k)
              (let [ps (vec (repeatedly n #(gensym "a")))
                    f (binding [*ns* (the-ns 'cohost.hosts.jolt)]
                        (eval (list 'fn* (into ['o] ps)
                                    (list '. 'o (if (zero? n) (symbol nm) (apply list (symbol nm) ps))))))]
                (swap! members assoc k f)
                f))]
    (try (apply f x args)
         (catch IllegalArgumentException e
           (if (let [m (ex-message e)] (and m (.startsWith ^String m "No matching")))
             absent
             (throw e))))))

;; a host multifn's name and namespace: jolt's MultiFn carries
;; neither, so they are found by identity over the loaded namespaces
;; (defmethod-setup wants both) and cached by the multifn itself
(def ^:private mf-names (atom []))
(defn- mf-name [mf]
  (or (some (fn [[m v]] (when (identical? m mf) v)) @mf-names)
      (let [found (some (fn [ns]
                          (some (fn [[s v]] (when (identical? (var-get v) mf) [(ns-name ns) s]))
                                (ns-interns ns)))
                        (all-ns))]
        (when found (swap! mf-names conj [mf found]))
        found)))

;; the MultiFn members of a host multifn: its methods live in jolt's
;; own registry, which jolt's own core fns address (defmethod-setup
;; for a new method, given the name the search above recovers)
;; a dispatch value written as a class name is the shim's class
;; token (a symbol); the host registry keys class methods by the
;; Class itself, as the dispatch (class x) answers it
(defn- mf-dv [dv]
  (if (symbol? dv)
    (let [c (try (resolve dv) (catch Throwable _ nil))]
      (if (class? c) c dv))
    dv))

(defn- multifn-op [mf op args]
  (case op
    :add (let [[dv f] args [ns s] (mf-name mf)]
           (clojure.core/defmethod-setup s (mf-dv dv) f ns)
           mf)
    :remove (do (remove-method mf (mf-dv (first args))) mf)
    :get (get-method mf (mf-dv (first args)))
    :table (methods mf)
    :prefers (prefers mf)
    :prefer (do (prefer-method mf (first args) (second args)) mf)))

(adapter/install! (merge java/capabilities
                         adapter
                         {:math (assoc (:math java/capabilities) 'log10 log10)
                          :invoke-member invoke-member
                          :multifn-op multifn-op}))

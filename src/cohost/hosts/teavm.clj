(ns cohost.hosts.teavm
  "The TeaVM seat: stock Clojure 1.12 compiled to WasmGC by the campaign's
   TeaVM fork, running as a cohost sister. The fork emulates the JVM
   classlib (stock clojure.lang.* under its JVM names), so this adapter is
   hosts/jvm.clj's capability map with the deltas the fork forces, named
   below; everything not named is the JVM shape because the classlib is
   stock.

   Sister-only, by ruling (John, 2026-11-13): this seat serves inside an
   embedding -- VS is the primary at all times, and the seat is the
   fallback for Java calls outside the embedding's shimmed space. It is
   never an external primary: it is not in bin/cohost's host set, it has
   no :cohost/target story and no launch path, and it registers in
   scan/rankings only so a primary's resolver and the seat's own boot can
   name it. Registration is a name, not a launch promise.

   Deltas from hosts/jvm.clj:
   - :invoke-member is jolt's reflection-free shape (a compiled fn*
     holding a `.` form, cached per [name arity]), not the JVM's
     Reflector walk: TeaVM AOT has no dynamic class loading and its
     reflection surface is compile-time-registered, so the adapter is
     AOT-compiled into the body image and its `.` forms resolve against
     the fork's real classes at compile time.
   - No :multifn-op (as on the JVM seat): the fork's clojure.lang.MultiFn
     is the real class, so the shim's own MultiFn handling applies.
   - The thread set (:thread-spawn, :thread-self, :doorbell,
     :doorbell-wait, :doorbell-signal, :thread-bindings) is deliberately
     omitted: on the fork that surface is desync'd (Thread.sleep is a
     busy wait, switchContext a no-op, the rest wouldBlock), so the keys
     stay at the adapter defaults, which answer \"cannot\" -- the truth
     on this seat until the yield-to-pump versions land here (the
     one-thread binding's wait contract).
   - :native-ctor / :native-static are carried from hosts/jvm.clj
     (Class/forName + Reflector). Their in-body scope is MEASURED
     (SIS-1 falsifier, banked on lane-m/teavm-c1): Class/forName
     answers for every name-resolvable class, and the Reflector half
     answers inside the fork's reflection census (java.lang.* plus
     the registered clojure.lang data set); outside the census the
     member metadata is empty and these keys return nil -- the
     truthful cannot for this seat. Widening the census is R1, a
     fork-side supplier change, not an adapter change.
   - :byte-streams' :read-all reads a File through FileInputStream,
     not java.nio.file.Files/readAllBytes: the fork classlib has no
     java.nio.file (the fork's dependency analysis rejects
     File.toPath in this namespace -- registration landing, in-body
     build). The InputStream arm is the JVM shape unchanged."
  (:require [cohost.adapter :as adapter]
            [cohost.hosts.java :as java]))

(defn- read-forms [src cb]
  (let [r (java.io.PushbackReader. (java.io.StringReader. src))]
    (loop []
      (let [f (read {:eof ::eof :read-cond :allow} r)]
        (when-not (= f ::eof) (cb f) (recur))))))

(def adapter
  {:name :teavm
   ;; X3-1: the shim's MultiFn with its one-entry cache (adapter.cljc), as
   ;; carried on the JVM seat (hosts/jvm.clj, measured there); this seat's
   ;; own bench of the flag is owed with its first perf run
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
   ;; one lookup each, not the namespace's whole map
   :resolve-core (fn [sym]
                   (let [v (.findInternedVar ^clojure.lang.Namespace (the-ns 'clojure.core) ^clojure.lang.Symbol sym)]
                     (when (and v (.isPublic v)) v)))
   :interned-var (fn [ns-sym sym]
                   (when-let [n (find-ns ns-sym)] (.findInternedVar ^clojure.lang.Namespace n ^clojure.lang.Symbol sym)))
   :current-ns (fn [] (ns-name *ns*))
   :macro-var? (fn [v] (.isMacro ^clojure.lang.Var v))
   :mark-macro! (fn [v] (.setMacro ^clojure.lang.Var v) v)
   :var-raw-root (fn [v] (.getRawRoot ^clojure.lang.Var v))
   :set-dynamic! (fn [v] (.setDynamic ^clojure.lang.Var v) v)
   ;; the runtime's own vars (*ns*, *out*, *print-length* ...) are dynamic by
   ;; flag only; their metadata carries no :dynamic
   :dynamic-var? (fn [v] (.isDynamic ^clojure.lang.Var v))
   :empty-queue (fn [] clojure.lang.PersistentQueue/EMPTY)
   :namespace? (fn [x] (instance? clojure.lang.Namespace x))
   :var-bound? (fn [v] (bound? v))
   :var-thread-bound? (fn [v] (thread-bound? v))
   :var-set! (fn [v val] (.set ^clojure.lang.Var v val))
   ;; the natives are the host runtime's own fns, the stock ones compiled:
   ;; the shared set and what jolt answers otherwise (as hosts/jvm.clj)
   :natives (merge java/natives
                   {'clojure.core/into into 'clojure.core/reverse reverse 'clojure.core/replace replace
                    'clojure.string/replace clojure.string/replace 'clojure.string/reverse clojure.string/reverse
                    'clojure.string/split clojure.string/split})
   :lazy-seq (fn [f] (clojure.lang.LazySeq. f))
   ;; an ArrayChunk is no coll reduce walks: IChunk.reduce does
   :chunks (fn [] {:chunked-seq? chunked-seq? :chunk-first chunk-first :chunk-rest chunk-rest :chunk-next chunk-next
                   :chunk-buffer chunk-buffer :chunk-append chunk-append :chunk chunk :chunk-cons chunk-cons
                   :chunk? (fn [x] (instance? clojure.lang.IChunk x))
                   :chunk-reduce (fn [c f init] (.reduce ^clojure.lang.IChunk c f init))})
   :delay (fn [f] (clojure.lang.Delay. f))
   :exception? (fn [x] (instance? Throwable x))
   ;; the trace as data; the head frames of a shim-constructed exception
   ;; are the construction machinery itself (the adapter's ex-info, the
   ;; shim's ex-ctor, the booted core's ex-info fn) -- elide them so the
   ;; first frame is the caller's, as for a directly constructed exception
   :ex-trace (fn [e]
               ;; not an exception (a shim Thread is a state atom, e.g.):
               ;; it has no trace, and must not be cast to one
               (if-not (instance? Throwable e)
                 []
                 (let [ctor-frame? (fn [^StackTraceElement el]
                                   (let [c (.getClassName el)]
                                     (or (.startsWith c "cohost.shim$")
                                         (.startsWith c "cohost.adapter$")
                                         (.startsWith c "cohost.core$ex_info")
                                         ;; the apply machinery the construction
                                         ;; was reached through (a directly
                                         ;; constructed exception's trace starts
                                         ;; at its caller instead)
                                         (= c "clojure.lang.AFn")
                                         (= c "clojure.lang.RestFn")
                                         (= c "clojure.lang.Var")
                                         (.startsWith c "clojure.core$apply"))))]
                 (mapv (fn [^StackTraceElement el]
                         [(symbol (.getClassName el)) (symbol (.getMethodName el))
                          (.getFileName el) (.getLineNumber el)])
                       (drop-while ctor-frame? (.getStackTrace ^Throwable e))))))
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
   ;; a JVM array (byte[] from .getBytes or a java.net.http body, Object[]
   ;; from to-array): Clojure 1.12 has no array? of its own, and without
   ;; this the shim read none as an array -- (String. bytes "UTF-8") threw
   ;; its ClassCastException on a byte[] (measured, unit 9, JVM seat)
   :array? (fn [x] (and (some? x) (.isArray (class x))))
   ;; cohost.http's bodies as java.io's own byte streams (unit 9): a body a
   ;; program reads through slurp, clojure.java.io or .read is the host's
   ;; InputStream, as the JDK's HttpURLConnection hands it out
   :byte-streams (fn [] {:input (fn [^bytes bs] (java.io.ByteArrayInputStream. bs))
                         :output (fn [] (java.io.ByteArrayOutputStream.))
                         :output-bytes (fn [^java.io.ByteArrayOutputStream o] (.toByteArray o))
                         :bytes? bytes?
                         ;; Ring's bodies (cohost.ring): text and base64 to bytes and back,
                         ;; and the whole of an InputStream or a File
                         :text-bytes (fn [^String s] (.getBytes s "UTF-8"))
                         :b64-bytes (fn [^String s] (.decode (java.util.Base64/getDecoder) s))
                         :bytes-b64 (fn [^bytes bs] (.encodeToString (java.util.Base64/getEncoder) bs))
                         :read-all (fn [x] (cond (instance? java.io.File x) (with-open [in (java.io.FileInputStream. ^java.io.File x)] (.readAllBytes in))
                                                 (instance? java.io.InputStream x) (with-open [^java.io.InputStream in x] (.readAllBytes in))
                                                 :else nil))})
   ;; the host's own java.net classes, where cohost.http has no leaves
   ;; (miniature-gated, see the ns docstring)
   :native-ctor (fn [c] (when-let [k (try (Class/forName (str c)) (catch Exception _ nil))]
                          (fn [& args] (clojure.lang.Reflector/invokeConstructor k (to-array args)))))
   :native-static (fn [c m] (when-let [k (try (Class/forName (str c)) (catch Exception _ nil))]
                              (fn [& args] (clojure.lang.Reflector/invokeStaticMethod k (str m) (to-array args)))))
   :index-of (fn [s sub from] (.indexOf ^String s ^String sub (int from)))
   :transient? (fn [x] (instance? clojure.lang.ITransientCollection x))
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
   ;; RT$DefaultComparator is a Comparator but not an IFn
   :sorted-comparator (fn [x] (let [c (.comparator ^clojure.lang.Sorted x)]
                                (if (ifn? c) c (fn [a b] (.compare ^java.util.Comparator c a b)))))
   :multifn? (fn [x] (instance? clojure.lang.MultiFn x))})

;; a member by name: the host's own `.` form (a field or a method),
;; compiled once per [name arity] and cached; a missing member is
;; reported as the absent value, as the contract's :invoke-member
;; requires. Compiled with fn* and `.` (special forms) in this
;; namespace: in a cohost namespace `fn` is the stock macro, whose
;; output the codemod rewrites back into a dispatcher call -- a loop
;; (hosts/jolt.clj, whose shape this is).
(def ^:private members (atom {}))
(defn- invoke-member [x nm args absent]
  (let [n (count args) k [nm n]
        f (or (get @members k)
              (let [ps (vec (repeatedly n #(gensym "a")))
                    f (binding [*ns* (the-ns 'cohost.hosts.teavm)]
                        (eval (list 'fn* (into ['o] ps)
                                    (list '. 'o (if (zero? n) (symbol nm) (apply list (symbol nm) ps))))))]
                (swap! members assoc k f)
                f))]
    (try (apply f x args)
         (catch IllegalArgumentException e
           (if (let [m (ex-message e)] (and m (.startsWith ^String m "No matching")))
             absent
             (throw e))))))

(adapter/install! (merge java/capabilities
                         adapter
                         {:invoke-member invoke-member}))

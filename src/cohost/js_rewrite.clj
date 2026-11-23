(ns cohost.js-rewrite
    "Turn the js/* interop of a stock cljs form into cohost.js-shim
   references, the way cohost.rewrite turns JVM interop into cohost.shim
   calls for the clj boot:

     js/Error                       ->  cohost.js-shim/Error
     js/Error.                      ->  cohost.js-shim/Error.
     js/Number.POSITIVE_INFINITY    ->  (.-POSITIVE_INFINITY cohost.js-shim/Number)
     js/a.b.c                       ->  (.-c (.-b cohost.js-shim/a))
     (js/Promise.all xs)            ->  (.all cohost.js-shim/Promise xs)

   A dotted js/* symbol in call position is a method call on the
   global, and stock cljs calls it on its receiver. The plain walk
   would rewrite the head to a (.-all ...) property read and call the
   extracted function unbound -- Promise.all throws a TypeError
   (called on non-object) that way, measured in the node seat's
   member graph: eve.process-slab's init! calls
   (js/Promise.all slab-promises).
   rewrite-list rebuilds such a call as a dot call on the chain
   minus its last segment, which the analyzer emits as a call on
   the receiver. In value position the same symbol stays a property
   read -- an unbound function value, exactly stock cljs's meaning.

   Everything else is walked through unchanged in shape: sequences,
   vectors, maps, sets and metadata are rebuilt with rewritten elements.
   Three forms are not descended into:

   - (quote ...): its symbols are data. A macro that splices a quoted
     js/* symbol into code position is the macro-wrap problem, which on
     the JVM seat does not arise (core.cljc's macro expansions introduce
     js* forms, never js/* symbols -- derived from the 1.12.134 sources
     at this lane's start; it returns with self-hosted realms).
   - the template string of (js* ...): the analyzer emits it verbatim.
     js*'s argument expressions ARE rewritten.
   - (var ...): its argument names a var, never a js global.

   A js root the shim does not define is not papered over here: the
   rewritten reference fails analysis, naming the missing root.

   rewrite-expansion is the second entry, for macro expansions (the
   macro wrap in cohost.boot-cljs). It differs in exactly one rule:
   a js* form is returned verbatim, template AND arguments. In an
   expansion a js* form is the compiler's own contract, not source
   interop: cljs.core's exists? macro, for one, expands to a js* form
   whose argument spells a namespace object's var as js/<ns>.<name>
   (js/cljs.core.PROTOCOL_SENTINEL) -- the js/ prefix there names the
   namespace object, never a global, and rewriting it corrupts the
   reference (measured: a wrapping boot died on core.cljs's
   (defonce PROTOCOL_SENTINEL #object[...]) with exactly those
   symbols rewritten). Source forms keep the descending rule: their
   js* arguments are source expressions, written by a programmer."
    (:require [clojure.string :as str]))

(def shim-ns 'cohost.js-shim)

(defn- js-symbol
       "Rewrite one js/* symbol. Returns a symbol, or a (.-prop ...) form for
   a dotted name."
       [sym]
       (let [nm (name sym)]
            (cond
      ;; constructor: js/Error. -> cohost.js-shim/Error.
             (str/ends-with? nm ".")
             (symbol (str shim-ns) nm)

      ;; dotted: js/Number.POSITIVE_INFINITY -> (.-POSITIVE_INFINITY
      ;; cohost.js-shim/Number); deeper chains nest.
             (str/includes? nm ".")
             (let [parts ;; splint: not equivalent: .split/.replace take a regex string with JS semantics; clojure.string/split wants a Pattern and replace differs on string matches
                   #_{:splint/disable [style/prefer-clj-string]}
                   (.split nm "\\.")]
                  (reduce (fn [acc part] (list (symbol (str ".-" part)) acc))
                          (symbol (str shim-ns) (aget parts 0))
                          (drop 1 parts)))

             :else
             (symbol (str shim-ns) nm))))

(declare rewrite*)

(defn- dotted-js-head
       "When a list's head is a dotted js/* symbol (js/Promise.all -- not
   a constructor's trailing dot), the [root middle... method] name
   parts; else nil."
       [head]
       (when (and (symbol? head) (= "js" (namespace head)))
             (let [nm (name head)]
                  (when (and (str/includes? nm ".") (not (str/ends-with? nm ".")))
                        ;; splint: not equivalent: .split/.replace take a regex string with JS semantics; clojure.string/split wants a Pattern and replace differs on string matches
                        #_{:splint/disable [style/prefer-clj-string]}
                        (.split nm "\\.")))))

(defn- rewrite-list [mode form]
       (let [head (first form)]
            (cond (or (= 'quote head) (= 'var head)) form (dotted-js-head head) (let [parts (dotted-js-head head)
                                                                                      target (reduce (fn [acc part] (list (symbol (str ".-" part)) acc))
                                                                                                     (symbol (str shim-ns) (aget parts 0))
                                                                                                     (drop 1 (butlast parts)))]
                                                                                     (apply list (symbol (str "." (last parts))) target
                                                                                            (map #(rewrite* mode %) (rest form)))) (= 'js* head) (if (= :expansion mode)
                                                                                                                                                     form
                                                                                                                                                     (apply list 'js* (second form)
                                                                                                                                                            (map #(rewrite* mode %) (drop 2 form)))) :else (apply list (map #(rewrite* mode %) form)))))

(defn- rewrite*
       [mode form]
  ;; metadata is captured BEFORE the rebuild and reattached after:
  ;; on an expansion it is semantic, not decoration -- the
  ;; deftype/extend-type macros mark the method fn forms they
  ;; generate with :cljs.analyzer/type (and protocol-impl/inline),
  ;; and the analyzer reads exactly that to emit the method's
  ;; `var self__ = this;` for field access. Dropping it (the first
  ;; walker did: it checked the rebuilt form's meta, which is always
  ;; nil) leaves field references munged to self__.<field> with no
  ;; declaration -- a wrapping boot died on core.cljs's MultiFn that
  ;; way. On source forms the same preservation keeps the reader's
  ;; line/column data, which the old walker also silently dropped.
       (let [m (meta form)
             form' (cond
                    (symbol? form) (if (= "js" (namespace form))
                                       (js-symbol form)
                                       form)
                    (seq? form) (rewrite-list mode form)
                    (vector? form) (mapv #(rewrite* mode %) form)
                    (map? form) (into (try (empty form) ;; splint: catching Throwable is the isolation contract: host/seat failures must not escape this boundary
                                           #_{:splint/disable [lint/catch-throwable]}
                                           (catch Throwable _ {}))
                                      (map (fn [[k v]] [(rewrite* mode k) (rewrite* mode v)]))
                                      form)
                    (set? form) (into (try (empty form) ;; splint: catching Throwable is the isolation contract: host/seat failures must not escape this boundary
                                           #_{:splint/disable [lint/catch-throwable]}
                                           (catch Throwable _ #{}))
                                      (map #(rewrite* mode %))
                                      form)
                    :else form)]
            (if (and m (instance? clojure.lang.IObj form'))
                (with-meta form' (rewrite* mode m))
                form')))

(defn rewrite
      "Rewrite one read form (see the namespace docstring for the rules)."
      [form]
      (rewrite* :source form))

(defn rewrite-expansion
      "Rewrite one macro expansion: like rewrite, but a js* form is
   returned verbatim -- in expansion position js* is the compiler's
   contract, and its js/ arguments name namespace objects, not
   globals."
      [form]
      (rewrite* :expansion form))

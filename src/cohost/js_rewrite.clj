(ns cohost.js-rewrite
  "Turn the js/* interop of a stock cljs form into cohost.js-shim
   references, the way cohost.rewrite turns JVM interop into cohost.shim
   calls for the clj boot:

     js/Error                       ->  cohost.js-shim/Error
     js/Error.                      ->  cohost.js-shim/Error.
     js/Number.POSITIVE_INFINITY    ->  (.-POSITIVE_INFINITY cohost.js-shim/Number)
     js/a.b.c                       ->  (.-c (.-b cohost.js-shim/a))

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
   rewritten reference fails analysis, naming the missing root.")

(def shim-ns 'cohost.js-shim)

(defn- js-symbol
  "Rewrite one js/* symbol. Returns a symbol, or a (.-prop ...) form for
   a dotted name."
  [sym]
  (let [nm (name sym)]
    (cond
      ;; constructor: js/Error. -> cohost.js-shim/Error.
      (.endsWith nm ".")
      (symbol (str shim-ns) nm)

      ;; dotted: js/Number.POSITIVE_INFINITY -> (.-POSITIVE_INFINITY
      ;; cohost.js-shim/Number); deeper chains nest.
      (.contains nm ".")
      (let [parts (.split nm "\\.")]
        (reduce (fn [acc part] (list (symbol (str ".-" part)) acc))
                (symbol (str shim-ns) (aget parts 0))
                (drop 1 parts)))

      :else
      (symbol (str shim-ns) nm))))

(declare rewrite)

(defn- rewrite-list [form]
  (let [head (first form)]
    (cond
      (= 'quote head) form
      (= 'var head) form
      ;; (js* "template" expr...): template verbatim, exprs rewritten
      (= 'js* head) (apply list 'js* (second form) (map rewrite (drop 2 form)))
      :else (apply list (map rewrite form)))))

(defn rewrite
  "Rewrite one read form (see the namespace docstring for the rules)."
  [form]
  (let [form (cond
               (symbol? form) (if (= "js" (namespace form))
                                (js-symbol form)
                                form)
               (seq? form) (rewrite-list form)
               (vector? form) (mapv rewrite form)
               (map? form) (into {} (map (fn [[k v]] [(rewrite k) (rewrite v)])) form)
               (set? form) (into #{} (map rewrite) form)
               :else form)]
    ;; metadata rides along rewritten (a ^js/Foo tag is inference data
    ;; only, but a tag naming a shim root should name the shim's var)
    (if (and (instance? clojure.lang.IObj form) (meta form))
      (with-meta form (rewrite (meta form)))
      form)))

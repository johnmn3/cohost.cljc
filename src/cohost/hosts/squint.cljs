(ns cohost.hosts.squint
  "Squint as a cohost host: stock Clojure booted on the squint
   dialect (squint-cljs 0.16.213) under Node, no JVM and no
   self-hosted analyzer. What the cljs seat gets from its analyzer,
   this seat supplies from its own machinery:

   - namespaces and vars live in a registry the adapter owns; a
     var's value is a property of a plain JS object hung on
     globalThis under the namespace's dotted path (cohost.core at
     globalThis.cohost.core), so compiled eval chunks read current
     values at call time and alter-var-root! is a property write.
     Var handles are {:ns :name} maps; metadata, macro and dynamic
     flags live in the registry entry, never on the value.
   - eval is chunk compilation: the form is qualified by a
     scope-aware walk against the registry (locals left alone,
     aliases expanded, unqualified names resolved own-namespace,
     then refers, then cohost.core), printed, compiled with
     squint's own compiler (the synchronous compiler.js build),
     and run after rewriting its sqcore import to a stashed
     global and its export statement to a return. A def-family
     form's exports are harvested back into the registry.
   - reading is this file's own reader: the full form grammar
     boot reads stock sources with -- reader conditionals by
     cohost.scan's :squint ranking, syntax-quote expanded the
     JVM way against the registry, fn literals, tagged literals
     through the seat's data readers.
   - macros: :macro-def-style :meta, :macro-wrap-style :root;
     macroexpand-1 applies the registry's macro fn directly.
   - no threads: the thread caps stay nil (a threadless seat);
     a body's sleep is the body's own, injected into cohost.bodies
     by the entry, as on the other compiled seats.

   Dialect facts this leans on, all measured at the pin: squint's
   reader features are #{:squint :cljs} with first-matching-arm
   resolution; a bare form's qualified reference compiles to a
   dotted global path (other.ns/get-x -> other.ns.get_x()); its
   core import is the only import a require-free chunk emits."
  (:require [cohost.adapter :as adapter]
            [clojure.string :as string]
            ["node:fs" :as fs]
            ["squint-cljs/core.js" :as sqcore]
            ["squint-cljs" :as squint-compiler])
  (:refer-clojure :exclude [read-string ns-interns]))

;; ---------------------------------------------------------------------
;; munging (the cljs table squint shares)

(def ^:private munge-table
  {"-" "_" "+" "_PLUS_" "*" "_STAR_" "/" "_SLASH_" "=" "_EQ_"
   "<" "_LT_" ">" "_GT_" "!" "_BANG_" "?" "_QMARK_" "'" "_SINGLEQUOTE_"
   "#" "_SHARP_" "%" "_PERCENT_" "&" "_AMPERSAND_" "|" "_BAR_"
   "~" "_TILDE_" "^" "_CARET_" ":" "_COLON_"})

(defn- munge-name [s]
  (apply str (map (fn [ch] (get munge-table ch ch)) (str s))))

;; ---------------------------------------------------------------------
;; the registry

(defonce ^:private reg (atom {}))
;; ns-sym -> {:vars {sym {:meta map :macro? bool :dynamic? bool :bound? bool}}
;;            :refers {sym src-ns-sym} :aliases {sym ns-sym}}
(defonce ^:private cur-ns (atom 'user))
(defonce ^:private binding-frames (atom [])) ;; [{handle value} ...], dynamic vars only
(defonce ^:private data-readers (atom {}))   ;; tag-sym -> fn, the seat's own readers

(defn- ns-obj!
  "The JS object holding ns-sym's var values, created on demand:
   globalThis.<seg>.<seg>... for the namespace's dotted name."
  [ns-sym]
  (loop [obj js/globalThis segs (string/split (str ns-sym) #"\.")]
    (if (empty? segs)
      obj
      (let [seg (first segs)
            nxt (aget obj seg)]
        (when (nil? nxt) (aset obj seg (js-obj)))
        (recur (aget obj seg) (rest segs))))))

(defn- var-value [ns-sym sym] (aget (ns-obj! ns-sym) (munge-name sym)))
(defn- set-var-value! [ns-sym sym v] (aset (ns-obj! ns-sym) (munge-name sym) v))

(defn- entry [ns-sym] (get @reg ns-sym))
(defn- var-entry [ns-sym sym] (get-in @reg [ns-sym :vars sym]))

(defn- ensure-ns!
  [ns-sym]
  (when-not (contains? @reg ns-sym)
    (ns-obj! ns-sym)
    (swap! reg assoc ns-sym {:vars {} :refers {} :aliases {}}))
  ns-sym)

(defn- handle [ns-sym sym]
  (with-meta {:ns ns-sym :name sym} {:cohost/var true}))

(defn- intern-in! [ns-sym sym v]
  (ensure-ns! ns-sym)
  (set-var-value! ns-sym sym v)
  (swap! reg assoc-in [ns-sym :vars sym]
         (merge {:meta {} :macro? false :dynamic? false} (var-entry ns-sym sym) {:bound? true}))
  (handle ns-sym sym))

(defn mirror!
  "Intern pre-compiled values (the AOT cohost.shim / cohost.boot
   module exports) into the registry, so eval'd stock code calling
   them through the global path and boot resolving them through
   the caps see the same fns. Called by the body entry with
   {ns-sym {sym value}}."
  [mirrors]
  (doseq [[ns-sym vars] mirrors]
    (ensure-ns! ns-sym)
    (doseq [[sym v] vars] (intern-in! ns-sym sym v))))

;; ---------------------------------------------------------------------
;; the reader

(def ^:private ranking [:clj :default])

(defonce ^:private gensym-counter (atom 0))

(declare read-form* resolve-unqualified expand-sq-concat read-reader-cond
         squint-consts qualify)

(defn- ws-char? [c] (or (= c " ") (= c "\t") (= c "\n") (= c "\r") (= c ",")))
(defn- term-char? [c]
  (or (ws-char? c) (contains? #{"(" ")" "[" "]" "{" "}" "\"" ";" "'" "@" "^" "`" "~"} c)))

(defn- skip-ws [cs i]
  (let [n (count cs)]
    (loop [i i]
      (cond
        (>= i n) i
        (ws-char? (nth cs i)) (recur (inc i))
        (= (nth cs i) ";") (recur (loop [j i] (if (or (>= j n) (= (nth cs j) "\n")) j (recur (inc j)))))
        (and (= (nth cs i) "#") (< (inc i) n) (= (nth cs (inc i)) "_"))
        (let [[_ j] (read-form* cs (+ i 2))] (recur (skip-ws cs j)))
        :else i))))

(defn- token-end [cs i]
  (let [n (count cs)]
    (loop [j i] (if (or (>= j n) (term-char? (nth cs j))) j (recur (inc j))))))

(defn- parse-number [tok]
  (cond
    (re-matches #"[+-]?\d+" tok) (js/parseInt tok 10)
    (re-matches #"[+-]?\d+N" tok) (js/BigInt (subs tok 0 (dec (count tok))))
    (re-matches #"[+-]?(\d+\.\d*|\.\d+|\d+)([eE][+-]?\d+)?M?" tok) (js/parseFloat tok)
    (re-matches #"[+-]?\d+/\d+" tok)
    (let [[a b] (string/split tok #"/")]
      (list 'cohost.core// (js/parseInt a 10) (js/parseInt b 10)))
    (re-matches #"0[xX][0-9a-fA-F]+" tok) (js/parseInt tok 16)
    :else nil))

(defn- read-token [tok ns-sym]
  (cond
    (= tok "nil") nil
    (= tok "true") true
    (= tok "false") false
    (string/starts-with? tok ":")
    (let [body (subs tok 1)]
      (cond
        (string/starts-with? body ":")
        (let [b (subs body 1)]
          (if (string/includes? b "/")
            (let [[a nm] (string/split b #"/" 2)]
              (keyword (str (or (get-in @reg [ns-sym :aliases (symbol a)]) a)) nm))
            (keyword (str ns-sym) b)))
        (string/includes? body "/")
        (let [[a nm] (string/split body #"/" 2)] (keyword a nm))
        :else (keyword body)))
    :else
    (let [num (parse-number tok)]
      (if (some? num) num (symbol tok)))))

(defn- read-string-lit [cs i]
  ;; i at the opening quote; returns [string next-i]
  (let [n (count cs)]
    (loop [j (inc i) sb []]
      (let [c (nth cs j)]
        (cond
          (= c "\"") [(apply str sb) (inc j)]
          (= c "\\")
          (let [e (nth cs (inc j))]
            (case e
              "n" (recur (+ j 2) (conj sb "\n"))
              "t" (recur (+ j 2) (conj sb "\t"))
              "r" (recur (+ j 2) (conj sb "\r"))
              "b" (recur (+ j 2) (conj sb "\b"))
              "f" (recur (+ j 2) (conj sb "\f"))
              "\\" (recur (+ j 2) (conj sb "\\"))
              "\"" (recur (+ j 2) (conj sb "\""))
              "u" (recur (+ j 6) (conj sb (js/String.fromCharCode (js/parseInt (apply str (subvec cs (+ j 2) (+ j 6))) 16))))
              (recur (+ j 2) (conj sb e))))
          :else (recur (inc j) (conj sb c)))))))

(def ^:private char-names
  {"newline" "\n" "space" " " "tab" "\t" "return" "\r"
   "backspace" "\b" "formfeed" "\f"})

(defn- expand-syntax-quote [form ns-sym gens]
  (cond
    (symbol? form)
    (cond
      (contains? #{'unquote 'unquote-splicing} form) form
      (string/ends-with? (str form) "#")
      (let [base (subs (str form) 0 (dec (count (str form))))]
        (if (contains? @gens base)
          (list 'quote (get @gens base))
          (let [g (symbol (str base "__" (swap! gensym-counter inc) "__auto__"))]
            (swap! gens assoc base g)
            (list 'quote g))))
      :else
      (let [q (cond
                (namespace form)
                (if (= (namespace form) "clojure.core") (symbol "cohost.core" (name form)) form)
                (resolve-unqualified ns-sym form) (resolve-unqualified ns-sym form)
                :else (symbol (str ns-sym) (str form)))]
        (list 'quote q)))
    (and (seq? form) (= (first form) 'unquote)) (second form)
    (and (seq? form) (= (first form) 'unquote-splicing))
    (throw (ex-info "unquote-splicing used outside a collection in syntax-quote" {}))
    (vector? form)
    (list 'apply 'cohost.core/vector
          (list 'cohost.core/sequence
                (list 'cohost.core/seq
                      (expand-sq-concat (seq form) ns-sym gens))))
    (map? form)
    (list 'apply 'cohost.core/hash-map
          (list 'cohost.core/sequence
                (list 'cohost.core/seq
                      (expand-sq-concat (mapcat (fn [[k v]] [k v]) form) ns-sym gens))))
    (set? form)
    (list 'apply 'cohost.core/hash-set
          (list 'cohost.core/sequence
                (list 'cohost.core/seq
                      (expand-sq-concat (seq form) ns-sym gens))))
    (seq? form)
    (list 'cohost.core/sequence
          (list 'cohost.core/seq
                (expand-sq-concat (seq form) ns-sym gens)))
    :else (list 'quote form)))

(defn- expand-sq-concat [elts ns-sym gens]
  (let [parts (map (fn [e]
                     (if (and (seq? e) (= (first e) 'unquote-splicing))
                       (second e)
                       (list 'cohost.core/list (expand-syntax-quote e ns-sym gens))))
                   elts)]
    (if (empty? parts)
      (list 'cohost.core/list)
      (apply list 'cohost.core/concat parts))))

(defn- resolve-unqualified
  "ns-sym/sym when sym names a var of ns-sym, one it refers, or a
   cohost.core var; nil when it names none of them."
  [ns-sym sym]
  (cond
    (var-entry ns-sym sym) (symbol (str ns-sym) (str sym))
    (get-in @reg [ns-sym :refers sym])
    (symbol (str (get-in @reg [ns-sym :refers sym])) (str sym))
    (var-entry 'cohost.core sym) (symbol "cohost.core" (str sym))
    :else nil))

(defn- apply-data-reader [tag form]
  (let [readers (merge @data-readers
                       (let [v (try (var-value 'cohost.core '*data-readers*) (catch :default _ nil))]
                         (if (map? v) v {})))
        default-fn (try (var-value 'cohost.core '*default-data-reader-fn*) (catch :default _ nil))
        f (or (get readers tag) default-fn)]
    (if f
      (f form)
      (throw (ex-info (str "no data reader for tag " tag) {:tag tag})))))

(defn- read-coll [cs i closer make ns-sym]
  ;; i just past the opener; returns [coll next-i]
  (let [n (count cs)]
    (loop [j (skip-ws cs i) acc []]
      (cond
        (>= j n) (throw (ex-info (str "EOF while reading, expected " closer) {}))
        (= (nth cs j) closer) [(make acc) (inc j)]
        (and (= (nth cs j) "#") (< (inc j) n) (= (nth cs (inc j)) "?"))
        (let [[v j2 splicing?] (read-reader-cond cs j ns-sym true)]
          (recur (skip-ws cs j2) (if splicing? (into acc v) (if (= v ::skip) acc (conj acc v)))))
        :else
        (let [[v j2] (read-form* cs j)]
          (recur (skip-ws cs j2) (if (= v ::skip) acc (conj acc v))))))))

(defn- read-reader-cond [cs i ns-sym in-coll?]
  ;; i at '#', next is '?'; returns [value next-i splicing?]
  (let [splicing? (= (nth cs (+ i 2)) "@")
        [form j] (read-form* cs (+ i (if splicing? 3 2)))
        pairs (partition 2 form)
        chosen (loop [ps pairs]
                 (cond
                   (empty? ps) ::skip
                   (contains? (set ranking) (ffirst ps)) (second (first ps))
                   (= (ffirst ps) :default) (second (first ps))
                   :else (recur (rest ps))))]
    [chosen j splicing?]))

(defn- collect-percent-refs [form]
  (let [refs (atom #{})]
    (letfn [(walk [f nested?]
              (cond
                (symbol? f)
                (let [s (str f)]
                  (when (and (not nested?) (string/starts-with? s "%"))
                    (swap! refs conj s)))
                (seq? f) (let [nested?' (or nested? (= (first f) 'fn*) (and (seq? f) (symbol? (first f)) (= (str (first f)) "fn")))]
                           (doseq [e f] (walk e nested?)))
                (coll? f) (doseq [e f] (walk e nested?))
                :else nil))]
      (walk form false)
      @refs)))

(defn- fn-literal [form]
  ;; form is the list read from #( ... ); its elements are the body
  (let [refs (collect-percent-refs form)
        nums (sort (keep (fn [s] (when-let [m (re-matches #"%(\d+)" s)] (js/parseInt (second m) 10))) refs))
        has-rest? (contains? refs "%&")
        params (cond-> (mapv (fn [k] (symbol (str "%" k))) (range 1 (inc (or (last nums) 0))))
                 has-rest? (into ['& '%&]))
        body (rest form)]
    (apply list 'fn* params (if (= 1 (count body)) body (list (apply list 'do body))))))

(defn- read-dispatch [cs i ns-sym]
  ;; i at '#'
  (let [c (nth cs (inc i))]
    (cond
      (= c "{") (read-coll cs (+ i 2) "}" (fn [acc] (apply hash-set acc)) ns-sym)
      (= c "(") (let [[form j] (read-form* cs (inc i))] [(fn-literal form) j])
      (= c "\"") (let [[s j] (read-string-lit cs (inc i))] [(js/RegExp. s) j])
      (= c "?") (let [[v j _] (read-reader-cond cs i ns-sym false)] [v j])
      (= c ":")
      (let [[form j] (read-form* cs (inc i))
            prefix (cond (keyword? form) (name form)
                         (symbol? form) (if (namespace form)
                                          (str (or (get-in @reg [ns-sym :aliases (symbol (namespace form))])
                                                   (namespace form)) "/" (name form))
                                          (str form))
                         :else (throw (ex-info "bad namespaced map literal" {})))
            [m j2] (read-form* cs j)]
        [(into {} (map (fn [[k v]] [(if (and (keyword? k) (not (namespace k))) (keyword prefix (name k)) k) v]) m)) j2])
      (= c "#")
      (let [j (token-end cs (+ i 2))
            tok (apply str (subvec cs (+ i 2) j))]
        [(symbol "cohost.squint-consts" (case tok
                                          "Inf" "POSITIVE_INFINITY"
                                          "-Inf" "NEGATIVE_INFINITY"
                                          "NaN" "NaN"
                                          (throw (ex-info (str "unknown ## literal: " tok) {}))))
         j])
      :else
      (let [[tag j] (read-form* cs (inc i))
            [form j2] (read-form* cs j)]
        [(apply-data-reader tag form) j2]))))

(defn- with-meta* [f mm]
  ;; squint's with-meta copies its target and cannot copy a string;
  ;; symbols and keywords ARE strings on this host, and string/char
  ;; literals are String objects (so the qualify walk and the form
  ;; printer can tell them apart from symbols): meta on any of those
  ;; is dropped here. The def path recovers what matters: :macro
  ;; from the def head, :dynamic from the earmuff convention.
  ;; Meta on collections and other objects attaches normally.
  (if (or (string? f) (instance? js/String f)) f (with-meta f mm)))

(defn- read-form* [cs i]
  ;; returns [form next-i]; ::skip for a form the reader drops
  (let [i (skip-ws cs i)
        n (count cs)]
    (when (>= i n) (throw (ex-info "EOF while reading" {:eof true})))
    (let [c (nth cs i)]
      (cond
        (= c "(") (read-coll cs (inc i) ")" (fn [acc] (apply list acc)) @cur-ns)
        (= c "[") (read-coll cs (inc i) "]" (fn [acc] acc) @cur-ns)
        (= c "{") (read-coll cs (inc i) "}" (fn [acc] (apply hash-map acc)) @cur-ns)
        (= c ")") (throw (ex-info "unmatched )" {}))
        (= c "]") (throw (ex-info "unmatched ]" {}))
        (= c "}") (throw (ex-info "unmatched }" {}))
        (= c "\"") (let [[s j] (read-string-lit cs i)] [(js/String. s) j])
        (= c "'") (let [[f j] (read-form* cs (inc i))] [(list 'quote f) j])
        (= c "`")
        (let [[f j] (read-form* cs (inc i))]
          [(expand-syntax-quote f @cur-ns (atom {})) j])
        (= c "~")
        (if (= (nth cs (inc i)) "@")
          (let [[f j] (read-form* cs (+ i 2))] [(list 'unquote-splicing f) j])
          (let [[f j] (read-form* cs (inc i))] [(list 'unquote f) j]))
        (= c "@") (let [[f j] (read-form* cs (inc i))] [(list 'deref f) j])
        (= c "^")
        (let [[m j] (read-form* cs (inc i))
              [f j2] (read-form* cs j)
              mm (cond (keyword? m) {m true} (symbol? m) {:tag m} (string? m) {:tag m} :else m)]
          [(with-meta* f mm) j2])
        (= c "\\")
        (let [j (token-end cs (inc i))
              tok (apply str (subvec cs (inc i) j))]
          [(js/String. (or (get char-names tok)
                           (cond
                             (string/starts-with? tok "u") (js/String.fromCharCode (js/parseInt (subs tok 1) 16))
                             (string/starts-with? tok "o") (js/String.fromCharCode (js/parseInt (subs tok 1) 8))
                             :else (subs tok 0 1))))
           j])
        (= c "#") (read-dispatch cs i @cur-ns)
        :else
        (let [j (token-end cs i)
              tok (apply str (subvec cs i j))]
          [(read-token tok @cur-ns) j])))))

(defn- read-forms-impl [src cb]
  (let [cs (vec src) n (count cs)]
    (loop [i (skip-ws cs 0)]
      (when (< i n)
        (let [[f j] (read-form* cs i)]
          (when (not= f ::skip) (cb f))
          (recur (skip-ws cs j)))))))

(defn- read-one [s opts]
  (let [cs (vec s)
        i (skip-ws cs 0)]
    (if (>= i (count cs))
      (:eof opts)
      (let [[f _] (read-form* cs i)]
        (if (= f ::skip) (:eof opts) f)))))

;; ---------------------------------------------------------------------
;; the qualify walk

(def ^:private special-forms
  '#{def if do let* loop* recur fn* quote var throw try catch finally
     new set! . &})

(defn- binding-names
  "The local names a binding form introduces."
  [bf]
  (cond
    (symbol? bf) (if (or (= bf '_) (namespace bf)) #{} #{bf})
    (vector? bf) (into #{} (mapcat binding-names bf))
    (map? bf) (into #{}
                    (concat (mapcat binding-names (keys bf))
                            (when (symbol? (:as bf)) [(:as bf)])
                            (mapcat (fn [k] (filter symbol? (get bf k))) [:keys :syms])))
    :else #{}))

(defn- qualify-sym [ns-sym locals s]
  (cond
    (not (symbol? s)) s
    (contains? locals s) s
    (contains? special-forms s) s
    (namespace s)
    (let [a (symbol (namespace s))]
      (cond
        (= a 'clojure.core) (symbol "cohost.core" (name s))
        (= (str a) (str ns-sym)) s
        (get-in @reg [ns-sym :aliases a]) (symbol (str (get-in @reg [ns-sym :aliases a])) (name s))
        :else s))
    :else (or (resolve-unqualified ns-sym s) s)))

(defn- qualify [form ns-sym locals]
  (cond
    (symbol? form) (qualify-sym ns-sym locals form)
    (seq? form)
    (if (empty? form)
      form
      (let [head (first form)]
        (cond
          (= head 'quote) form
          (= head 'fn*)
          (let [named? (symbol? (second form))
                sigs (if named? (nthrest form 2) (rest form))
                sigs (if (vector? (first sigs)) (list sigs) sigs)
                new-sigs (map (fn [sig]
                                (let [params (first sig)
                                      locals' (into locals (binding-names params))
                                      locals' (if named? (conj locals' (second form)) locals')]
                                  (apply list params (map #(qualify % ns-sym locals') (rest sig)))))
                              sigs)]
            (apply list (concat (if named? ['fn* (second form)] ['fn*]) new-sigs)))
          (or (= head 'let*) (= head 'loop*))
          (let [binds (second form)
                [new-binds locals']
                (loop [bs (seq binds) acc [] locals locals]
                  (if (empty? bs)
                    [acc locals]
                    (let [bf (first bs) init (second bs)]
                      (recur (nthrest bs 2)
                             (conj acc bf (qualify init ns-sym locals))
                             (into locals (binding-names bf))))))]
            (apply list head new-binds (map #(qualify % ns-sym locals') (nthrest form 2))))
          (= head 'letfn*)
          (let [specs (second form)
                names (into locals (map (comp first) specs))
                new-specs (mapv (fn [spec]
                                  (let [params (second spec)
                                        locals' (into names (binding-names params))]
                                    (apply list (first spec) params (map #(qualify % ns-sym locals') (nthrest spec 2)))))
                                specs)]
            (apply list head new-specs (map #(qualify % ns-sym names) (nthrest form 2))))
          (or (= head 'def) (= head 'defonce))
          (apply list head (second form) (map #(qualify % ns-sym locals) (nthrest form 2)))
          (or (= head 'deftype*) (= head 'defrecord*))
          form ;; the codemod's output for these is shim calls; never raw here
          (= head 'try)
          (let [parts (map (fn [p]
                             (if (and (seq? p) (= (first p) 'catch))
                               (let [cls (second p) bname (nth p 2)
                                     locals' (if (symbol? bname) (conj locals bname) locals)]
                                 (apply list 'catch cls bname (map #(qualify % ns-sym locals') (nthrest p 3))))
                               (qualify p ns-sym locals)))
                           (rest form))]
            (apply list 'try parts))
          :else (apply list (map #(qualify % ns-sym locals form) form)))))
    (vector? form) (mapv #(qualify % ns-sym locals) form)
    (map? form) (into {} (map (fn [[k v]] [(qualify k ns-sym locals) (qualify v ns-sym locals)]) form))
    (set? form) (into #{} (map #(qualify % ns-sym locals) form))
    :else form))

;; ---------------------------------------------------------------------
;; the form printer: qualified form -> source text for compileString.
;; Squint's pr-str cannot print code (a symbol prints as a quoted
;; string), and on this host symbol, keyword and string values are
;; all JS strings -- the reader keeps literals distinct (String
;; objects), and this printer decides the rest by position:
;; seq heads and expression positions print bare (symbols), map
;; keys / vector elements / set elements print as keywords, with a
;; small table for the core fns whose key argument is a keyword.
;; Inside quote everything prints bare: the chunk compiler turns
;; bare strings back into the same string values.

(defn- escape-string [s]
  (-> (str s)
      (string/replace "\\" "\\\\")
      (string/replace "\"" "\\\"")
      (string/replace "\n" "\\n")
      (string/replace "\r" "\\r")
      (string/replace "\t" "\\t")))

(def ^:private key-arg-heads #{"get" "contains?" "find"})
(def ^:private key-args-heads #{"assoc" "dissoc"})

(declare print-form)

(defn- print-binding-form [bf locals quoted?]
  (cond
    (string? bf) bf
    (vector? bf)
    (str "[" (string/join " " (map #(print-binding-form % locals quoted?) bf)) "]")
    (map? bf)
    (str "{"
         (string/join " "
                      (map (fn [[k v]]
                             (cond
                               (contains? #{"keys" "syms" "strs" "as"} k)
                               (str ":" k " [" (string/join " " (map str v)) "]")
                               (= k "or")
                               (str ":or {" (string/join " " (map (fn [[dk dv]] (str dk " " (print-form dv locals quoted?))) v)) "}")
                               :else
                               (str (print-binding-form k locals quoted?)
                                    " "
                                    (if (string? v) (str ":" v) (print-form v locals quoted?)))))
                           bf))
         "}")
    :else (print-form bf locals quoted?)))

(defn- print-form [form locals quoted?]
  (cond
    (nil? form) "nil"
    (true? form) "true"
    (false? form) "false"
    (number? form) (str form)
    (= (js/typeof form) "bigint") (str form "N")
    (instance? js/String form) (str "\"" (escape-string form) "\"")
    (instance? js/RegExp form)
    (str "(js/RegExp. \"" (escape-string (.-source form)) "\" \"" (escape-string (.-flags form)) "\")")
    (string? form)
    (cond quoted? form
          (contains? locals form) form
          (string/includes? form "/") form
          (contains? special-forms form) form
          :else form)
    (seq? form)
    (if (empty? form)
      "()"
      (let [head (first form)]
        (cond
          (= head 'quote) (str "(quote " (print-form (second form) locals true) ")")
          (= head 'fn*)
          (let [named? (symbol? (second form))
                sigs (if named? (nthrest form 2) (rest form))
                sigs (if (vector? (first sigs)) (list sigs) sigs)]
            (str "(fn* "
                 (when named? (str (second form) " "))
                 (string/join " "
                              (map (fn [sig]
                                     (let [locals' (into locals (binding-names (first sig)))
                                           locals' (if named? (conj locals' (second form)) locals')]
                                       (str "(" (print-binding-form (first sig) locals quoted?)
                                            " "
                                            (string/join " " (map #(print-form % locals' quoted?) (rest sig)))
                                            ")")))
                                   sigs))
                 ")"))
          (or (= head 'let*) (= head 'loop*))
          (let [binds (second form)
                pairs (partition 2 binds)
                locals' (reduce (fn [ls [bf _]] (into ls (binding-names bf))) locals pairs)]
            (str "(" head " ["
                 (string/join " "
                              (loop [ps (seq pairs) ls locals acc []]
                                (if (empty? ps)
                                  acc
                                  (let [[bf init] (first ps)]
                                    (recur (rest ps)
                                           (into ls (binding-names bf))
                                           (conj acc (print-binding-form bf locals quoted?)
                                                 (print-form init ls quoted?)))))))
                 "] "
                 (string/join " " (map #(print-form % locals' quoted?) (nthrest form 2)))
                 ")"))
          (= head 'letfn*)
          (let [specs (second form)
                names (into locals (map (comp first) specs))]
            (str "(letfn* ["
                 (string/join " "
                              (map (fn [spec]
                                     (let [locals' (into names (binding-names (second spec)))]
                                       (str "(" (first spec) " "
                                            (print-binding-form (second spec) locals quoted?)
                                            " "
                                            (string/join " " (map #(print-form % locals' quoted?) (nthrest spec 2)))
                                            ")")))
                                   specs))
                 "] "
                 (string/join " " (map #(print-form % names quoted?) (nthrest form 2)))
                 ")"))
          (or (= head 'def) (= head 'defonce))
          ;; [def name init] or [def name docstring init]: only the
          ;; four-element shape has a docstring in position 2
          (str "(" head " " (second form)
               (when (= (count form) 4)
                 (str " " (print-form (nth form 2) locals quoted?)))
               (let [init (nth form (if (= (count form) 4) 3 2) nil)]
                 (when (some? init) (str " " (print-form init locals quoted?))))
               ")")
          (= head 'try)
          (str "(try "
               (string/join " "
                            (map (fn [p]
                                   (if (and (seq? p) (= (first p) 'catch))
                                     (let [locals' (if (symbol? (nth p 2)) (conj locals (nth p 2)) locals)]
                                       (str "(catch " (second p) " " (nth p 2) " "
                                            (string/join " " (map #(print-form % locals' quoted?) (nthrest p 3)))
                                            ")"))
                                     (print-form p locals quoted?)))
                                 (rest form)))
               ")")
          :else
          (let [hname (when (string? head) (name head))]
            (str "(" (print-form head locals quoted?)
                 (when (seq (rest form)) " ")
                 (string/join " "
                              (map-indexed
                                (fn [i el]
                                  (let [pos (inc i)]
                                    (if (and (string? el)
                                             (not quoted?)
                                             (not (contains? locals el))
                                             (not (string/includes? el "/"))
                                             (not (contains? special-forms el))
                                             (or (and (contains? key-arg-heads hname) (= pos 2))
                                                 (and (contains? key-args-heads hname) (>= pos 2)
                                                      (or (= hname "dissoc") (even? pos)))))
                                      (str ":" el)
                                      (print-form el locals quoted?))))
                                (rest form)))
                 ")")))))
    (vector? form)
    (str "[" (string/join " " (map (fn [el]
                                     (if (and (string? el) (not quoted?)
                                              (not (contains? locals el))
                                              (not (string/includes? el "/"))
                                              (not (contains? special-forms el)))
                                       (str ":" el)
                                       (print-form el locals quoted?)))
                                   form)) "]")
    (map? form)
    (str "{"
         (string/join " "
                      (map (fn [[k v]]
                             (str (if (and (string? k) (not quoted?)
                                           (not (string/includes? k "/")))
                                    (str ":" k)
                                    (print-form k locals quoted?))
                                  " "
                                  (print-form v locals quoted?)))
                           form))
         "}")
    (set? form)
    (str "#{"
         (string/join " "
                      (map (fn [el]
                             (if (and (string? el) (not quoted?)
                                      (not (contains? locals el))
                                      (not (string/includes? el "/")))
                               (str ":" el)
                               (print-form el locals quoted?)))
                           form))
         "}")
    :else (pr-str form)))

;; ---------------------------------------------------------------------
;; chunk eval

(defonce ^:private chunk-dir
  (str (try (.-COHOST_SQUINT_CHUNKDIR js/process.env) (catch :default _ nil))
       ""))

(defn- compile-js [src]
  (let [res (squint-compiler/compileString src (js-obj))]
    (.-javascript res)))

(defn- run-js-chunk
  "Compile src (a single top-level form's text, no ns form, no
   requires) and run it synchronously. Returns {munged-name value}
   for the chunk's exports."
  [src]
  (let [js (compile-js src)
        js (string/replace js
                           "import * as squint_core from 'squint-cljs/core.js';"
                           "const squint_core = globalThis.__cohost_squint_core;")
        export-m (re-find #"export\s*\{([^}]*)\}" js)
        names (if export-m
                (map string/trim (string/split (nth export-m 1) #","))
                [])
        body (string/replace js #"export\s*\{[^}]*\}" "")
        ret (string/join ", " (map (fn [nm] (str nm ": " nm)) names))
        fn-text (str "(function(){\n" body "\nreturn {" ret "};\n})")
        f (js/eval fn-text)]
    (f)))

(def ^:private def-heads
  '#{def defn defn- defmacro definline defonce defmulti deftype
     defrecord defprotocol})

(defn- eval-form-impl [form]
  (let [ns-sym @cur-ns
        q (qualify form ns-sym #{})
        head (when (seq? q) (first q))]
    (if (contains? def-heads head)
      (let [exports (run-js-chunk (print-form q #{} false))
            nm (second q)
            v (get exports (munge-name nm))]
        (when (and (some? nm) (symbol? nm))
          (intern-in! ns-sym nm v)
          ;; meta cannot ride on a symbol here (see with-meta*):
          ;; :macro comes from the def head, :dynamic from the
          ;; name's own meta when it survived, else the earmuff
          ;; convention stock code follows for its dynamic vars
          (when (contains? '#{defmacro definline} head)
            (swap! reg assoc-in [ns-sym :vars nm :macro?] true))
          (when (or (true? (:dynamic (meta nm)))
                    (and (> (count (name nm)) 2)
                         (string/starts-with? (name nm) "*")
                         (string/ends-with? (name nm) "*")))
            (swap! reg assoc-in [ns-sym :vars nm :dynamic?] true))
          ;; deftype/defrecord also export ->Name and map->Name
          (when (contains? '#{deftype defrecord} head)
            (doseq [extra [(symbol (str "->" nm)) (symbol (str "map->" nm))]]
              (let [ev (get exports (munge-name extra))]
                (when (some? ev) (intern-in! ns-sym extra ev))))))
        (handle ns-sym nm))
      (let [exports (run-js-chunk (print-form (list 'def '__cohost_eval_v q) #{} false))]
        (get exports "__cohost_eval_v")))))

(defn- resolve-handle [ns-sym sym]
  (cond
    (var-entry ns-sym sym) (handle ns-sym sym)
    (get-in @reg [ns-sym :refers sym]) (handle (get-in @reg [ns-sym :refers sym]) sym)
    (var-entry 'cohost.core sym) (handle 'cohost.core sym)
    :else nil))

(defn- macroexpand-1-impl [form]
  (if (and (seq? form) (symbol? (first form)))
    (let [h (resolve-handle @cur-ns (first form))]
      (if (and h (true? (:macro? (var-entry (:ns h) (:name h)))))
        (let [f (var-value (:ns h) (:name h))]
          (apply f form {} (rest form)))
        form))
    form))

(defn- expand-all-top [form]
  (loop [f form]
    (let [f' (macroexpand-1-impl f)]
      (if (identical? f' f) f (recur f')))))

;; ---------------------------------------------------------------------
;; caps

(defn- var-get-impl [h]
  (let [{ns-sym :ns sym :name} h]
    (if (true? (:dynamic? (var-entry ns-sym sym)))
      (let [frames @binding-frames]
        (loop [i (dec (count frames))]
          (cond
            (< i 0) (var-value ns-sym sym)
            (contains? (nth frames i) h) (get (nth frames i) h)
            :else (recur (dec i)))))
      (var-value ns-sym sym))))

;; ---------------------------------------------------------------------
;; regex / math / edn caps (ported from the cljs adapter's shapes)

(defn- global-re [re]
  (js/RegExp. (.-source re) (str (.-flags re) (when-not (.-global re) "g") (when-not (.-hasIndices re) "d"))))

(defn- match-vec [m]
  [(.-index m) (+ (.-index m) (count (aget m 0))) (mapv (fn [g] (when-not (undefined? g) g)) (vec (.slice m)))
   (when-let [ix (.-indices m)] (mapv (fn [p] (when-not (undefined? p) [(aget p 0) (aget p 1)])) (vec (.slice ix))))])

(defn- re-find-at [re s from]
  (let [g (global-re re)]
    (set! (.-lastIndex g) from)
    (when-let [m (.exec g s)] (match-vec m))))

(defn- re-match-full [re s]
  (when-let [m (.exec (js/RegExp. (str "^(?:" (.-source re) ")$") (str (.replace (.replace (.-flags re) "g" "") "d" "") "d")) s)]
    (match-vec m)))

(defn- re-group-count [re] (dec (.-length (.exec (js/RegExp. (str (.-source re) "|")) ""))))

(defn- read-edn-one [opts s]
  (if-let [readers (:readers opts)]
    (let [saved @data-readers]
      (reset! data-readers (merge saved readers))
      (try (read-one s opts) (finally (reset! data-readers saved))))
    (read-one s opts)))

(def adapter
  {:name :squint
   :core-ns 'squint.core
   :catch-all 'default
   ;; the value constructors + type predicates install! requires:
   ;; squint core's own classes (its LazySeq widens instanceof to
   ;; every lazy cell; a transient is whatever carries the
   ;; ITransientCollection -conj! slot)
   :lazy-seq (fn [f] (new (aget sqcore "LazySeq") f))
   :delay (fn [f] (new (aget sqcore "Delay") f))
   :exception? (fn [x] (instance? js/Error x))
   :transient? (fn [x] (and (some? x)
                            (some? (aget x (aget sqcore "ITransientCollection__conj_BANG_")))))
   :lazy-seq? (fn [x] (instance? (aget sqcore "LazySeq") x))
   :atom? (fn [x] (instance? (aget sqcore "Atom") x))
   :parse-long (fn [s] (let [n (js/parseInt s 10)] (when-not (js/isNaN n) n)))
   :parse-double (fn [s] (js/parseFloat s))
   :nano-time (fn [] (long (* 1000000 (.now js/performance))))
   :current-time-millis (fn [] (.now js/Date))
   :math-ceil (fn [x] (js/Math.ceil x))
   :math-floor (fn [x] (js/Math.floor x))
   :math-sqrt (fn [x] (js/Math.sqrt x))
   :upper-case (fn [s] (.toUpperCase (str s)))
   :lower-case (fn [s] (.toLowerCase (str s)))
   :re-find-at re-find-at
   :re-match-full re-match-full
   :re-group-count re-group-count
   :edn-read-string (fn [opts s] (read-edn-one opts s))
   :macro-def-style :meta
   :macro-wrap-style :root
   :named-fn*? true
   :rest-args :seq
   :natives {}
   :core-extras {}
   :core-lacks #{}
   :shim-ref (fn [nm] (symbol "cohost.shim" (str nm)))
   :rewrite-output-ref 'cohost.boot/rewrite-output
   :slurp (fn [path] (fs/readFileSync (str path) "utf8"))
   :spit (fn [path s] (fs/writeFileSync (str path) (str s)))
   :read-forms read-forms-impl
   :read-string (fn ([s] (read-one s {})) ([s opts] (read-one s opts)))
   :host-read-string (fn [s] (read-one s {}))
   :plain-symbol (fn [x] x)
   :with-ns (fn [ns-sym thunk]
              (let [old @cur-ns]
                (reset! cur-ns ns-sym)
                (try (thunk) (finally (reset! cur-ns old)))))
   :ensure-bare-ns! (fn [ns-sym] (ensure-ns! ns-sym))
   :eval (fn [form] (eval-form-impl (expand-all-top form)))
   :ns-interns (fn [ns-sym]
                 (into {} (map (fn [[sym _]] [sym (handle ns-sym sym)])
                               (get-in @reg [ns-sym :vars]))))
   :interned-var (fn [ns-sym sym] (when (var-entry ns-sym sym) (handle ns-sym sym)))
   :ns-public-names (fn [ns-sym]
                      (into #{} (comp (remove (fn [[_ e]] (true? (:private (:meta e)))))
                                      (map first))
                            (get-in @reg [ns-sym :vars])))
   :intern! intern-in!
   :refer-all! (fn [into-ns from-ns]
                 (ensure-ns! into-ns)
                 (doseq [[sym _] (get-in @reg [from-ns :vars])]
                   (swap! reg assoc-in [into-ns :refers sym] from-ns)))
   :refer-from-core! (fn [into-ns sym]
                       (let [v (aget sqcore (munge-name sym))]
                         (when (some? v) (intern-in! into-ns sym v))))
   :refer! (fn [into-ns from-ns {:keys [only exclude rename]}]
             (ensure-ns! into-ns)
             (let [ren (or rename {})
                   syms (if only only (keys (get-in @reg [from-ns :vars])))]
               (doseq [sym syms]
                 (when-not (contains? (set exclude) sym)
                   (swap! reg assoc-in [into-ns :refers (get ren sym sym)] from-ns)))))
   :alias! (fn [into-ns a target]
             (ensure-ns! into-ns)
             (swap! reg assoc-in [into-ns :aliases a] target))
   :set-current-ns! (fn [ns-sym] (ensure-ns! ns-sym) (reset! cur-ns ns-sym))
   :current-ns (fn [] @cur-ns)
   :require-host! (fn [lib]
                    (when-not (contains? @reg lib)
                      (throw (ex-info (str "cohost: no host lib " lib " on squint")
                                      {:cohost/unsupported :require-host!}))))
   :resolve-core (fn [sym]
                   (when (some? (aget sqcore (munge-name sym)))
                     (handle 'squint.core sym)))
   :var-get var-get-impl
   :var-raw-root (fn [h] (var-value (:ns h) (:name h)))
   :var-meta (fn [h] (:meta (var-entry (:ns h) (:name h))))
   :alter-var-meta! (fn [h f & args]
                      (let [e (var-entry (:ns h) (:name h))]
                        (swap! reg assoc-in [(:ns h) :vars (:name h) :meta]
                               (apply f (:meta e) args))
                        h))
   :alter-var-root! (fn [h f]
                      (set-var-value! (:ns h) (:name h) (f (var-value (:ns h) (:name h))))
                      h)
   :macro-var? (fn [h] (true? (:macro? (var-entry (:ns h) (:name h)))))
   :mark-macro! (fn [h]
                  (swap! reg assoc-in [(:ns h) :vars (:name h) :macro?] true)
                  h)
   :var-ns (fn [h] (:ns h))
   :var-sym (fn [h] (:name h))
   :var? (fn [x] (true? (:cohost/var (meta x))))
   :namespace? (fn [x] (and (symbol? x) (contains? @reg x)))
   :dynamic-var? (fn [h] (true? (:dynamic? (var-entry (:ns h) (:name h)))))
   :set-dynamic! (fn [h]
                   (swap! reg assoc-in [(:ns h) :vars (:name h) :dynamic?] true)
                   h)
   :var-bound? (fn [h] (true? (:bound? (var-entry (:ns h) (:name h)))))
   :var-thread-bound? (fn [h] (boolean (some #(contains? % h) @binding-frames)))
   :push-thread-bindings! (fn [m] (swap! binding-frames conj m))
   :pop-thread-bindings! (fn [] (swap! binding-frames pop) nil)
   :thread-bindings (fn [] (apply merge @binding-frames))
   :var-set! (fn [h v]
               (let [frames @binding-frames]
                 (loop [i (dec (count frames))]
                   (cond
                     (< i 0) (set-var-value! (:ns h) (:name h) v)
                     (contains? (nth frames i) h)
                     (swap! binding-frames assoc i (assoc (nth frames i) h v))
                     :else (recur (dec i)))))
               v)
   :macroexpand-1 macroexpand-1-impl
   :env-locals (fn [env] (keys (:locals env)))})

(def squint-consts
  {:POSITIVE_INFINITY js/Infinity
   :NEGATIVE_INFINITY (- js/Infinity)
   :NaN js/NaN})

(defn install!
  "Install the squint adapter and the seat's constants namespace.
   Runs on module load (below), the cljs adapter's pattern: the
   graph's load-time capability reads (cohost.shim's top-level
   defs) must find the adapter installed, in the compiled body
   and in the compiler's own load of the graph alike."
  []
  (aset js/globalThis "__cohost_squint_core" sqcore)
  ;; squint core has no munge; the AOT shim's :cljs arms call the
  ;; bare global (squint emits it unqualified), so supply it here
  (aset js/globalThis "munge" (fn [x] (munge-name x)))
  (ensure-ns! 'cohost.squint-consts)
  (doseq [[sym v] squint-consts] (intern-in! 'cohost.squint-consts sym v))
  (adapter/install! adapter))

(install!)

(defn set-data-readers!
  "Merge tag readers (tag-sym -> fn) into the seat's table: how a
   body installs e.g. eve/keyword once Eve is loaded."
  [m]
  (swap! data-readers merge m))

(ns cohost.hosts.java
  "Capabilities the JVM and jolt adapters share: both hosts name java.lang,
   java.util.regex and clojure.edn natively, so these are one-line calls into
   the host. Merged into hosts/jvm.clj's and hosts/jolt.clj's adapter maps."
  (:require [clojure.edn :as edn]
            [clojure.string :as string]))

(defn- groups [^java.util.regex.Matcher m]
  (mapv (fn [i] (.group m (int i))) (range (inc (.groupCount m)))))
(defn- spans [^java.util.regex.Matcher m]
  ;; a group that did not participate has no text (jolt's start(g) is then
  ;; false, the JVM's -1)
  (mapv (fn [i] (when (some? (.group m (int i))) [(.start m (int i)) (.end m (int i))])) (range (inc (.groupCount m)))))
(defn- ch [c] (if (number? c) (int c) (int (char c))))

;; The exceptions the shim raises, as the host's own classes:
;; [() (String) (String Throwable)] constructors, nil where the JVM has none
;; (a cause the class cannot carry leaves the shim's stand-in).
(def ^:private exception-ctors
  {'java.lang.Throwable [#(Throwable.) #(Throwable. ^String %) #(Throwable. %1 %2)]
   'java.lang.Exception [#(Exception.) #(Exception. ^String %) #(Exception. %1 %2)]
   'java.lang.RuntimeException [#(RuntimeException.) #(RuntimeException. ^String %) #(RuntimeException. %1 %2)]
   'java.lang.Error [#(Error.) #(Error. ^String %) #(Error. %1 %2)]
   'java.lang.AssertionError [#(AssertionError.) #(AssertionError. ^String %) #(AssertionError. %1 %2)]
   'java.lang.IllegalArgumentException [#(IllegalArgumentException.) #(IllegalArgumentException. ^String %) #(IllegalArgumentException. %1 %2)]
   'java.lang.IllegalStateException [#(IllegalStateException.) #(IllegalStateException. ^String %) #(IllegalStateException. %1 %2)]
   'java.lang.UnsupportedOperationException [#(UnsupportedOperationException.) #(UnsupportedOperationException. ^String %) #(UnsupportedOperationException. %1 %2)]
   'java.lang.ArithmeticException [#(ArithmeticException.) #(ArithmeticException. ^String %) nil]
   'java.lang.NumberFormatException [#(NumberFormatException.) #(NumberFormatException. ^String %) nil]
   'java.lang.ClassCastException [#(ClassCastException.) #(ClassCastException. ^String %) nil]
   'java.lang.NullPointerException [#(NullPointerException.) #(NullPointerException. ^String %) nil]
   'java.lang.IndexOutOfBoundsException [#(IndexOutOfBoundsException.) #(IndexOutOfBoundsException. ^String %) nil]
   'java.lang.ArrayIndexOutOfBoundsException [#(ArrayIndexOutOfBoundsException.) #(ArrayIndexOutOfBoundsException. ^String %) nil]
   'java.lang.StringIndexOutOfBoundsException [#(StringIndexOutOfBoundsException.) #(StringIndexOutOfBoundsException. ^String %) nil]
   'java.lang.InterruptedException [#(InterruptedException.) #(InterruptedException. ^String %) nil]
   'java.util.NoSuchElementException [#(java.util.NoSuchElementException.) #(java.util.NoSuchElementException. ^String %) nil]
   'java.util.ConcurrentModificationException [#(java.util.ConcurrentModificationException.) #(java.util.ConcurrentModificationException. ^String %) #(java.util.ConcurrentModificationException. %1 %2)]
   'java.io.IOException [#(java.io.IOException.) #(java.io.IOException. ^String %) #(java.io.IOException. %1 %2)]})

(defn- new-exception [klass msg cause]
  (when-let [[c0 c1 c2] (get exception-ctors klass)]
    (cond (some? cause) (when c2 (c2 msg cause))
          (some? msg) (c1 msg)
          :else (c0))))

(defn exception-class
  "The class token of a host exception: its own class when the shim raises
   that class, else the nearest one the shim's class table has."
  [e]
  (let [n (symbol (.getName (class e)))]
    (if (contains? exception-ctors n)
      n
      (cond (instance? clojure.lang.ArityException e) 'clojure.lang.ArityException
            (instance? java.util.regex.PatternSyntaxException e) 'java.util.regex.PatternSyntaxException
            (instance? IllegalAccessError e) 'IllegalAccessError
            (instance? StringIndexOutOfBoundsException e) 'StringIndexOutOfBoundsException
            (instance? ArrayIndexOutOfBoundsException e) 'ArrayIndexOutOfBoundsException
            (instance? IndexOutOfBoundsException e) 'IndexOutOfBoundsException
            (instance? NumberFormatException e) 'NumberFormatException
            (instance? java.util.IllegalFormatException e) (symbol (.getName (class e)))
            (instance? IllegalArgumentException e) 'IllegalArgumentException
            (instance? IllegalStateException e) 'IllegalStateException
            (instance? ArithmeticException e) 'ArithmeticException
            (instance? ClassCastException e) 'ClassCastException
            (instance? NullPointerException e) 'NullPointerException
            (instance? UnsupportedOperationException e) 'UnsupportedOperationException
            (instance? AssertionError e) 'AssertionError
            (instance? StackOverflowError e) 'StackOverflowError
            (instance? Error e) 'Error
            :else 'RuntimeException))))

(def natives
  "The host's own fns that stand in for the stock ones (adapter :natives), one
   opt-in each: they have the host's semantics, so the battery, the corpus and
   the bench's check against JVM Clojure's answers gate every entry. The JVM
   and jolt share these; the JVM adds what jolt's own core answers otherwise
   (hosts/jvm.clj)."
  {'clojure.core/frequencies frequencies 'clojure.core/group-by group-by
   'clojure.core/partition partition 'clojure.core/partition-all partition-all
   'clojure.core/partition-by partition-by 'clojure.core/re-seq re-seq
   'clojure.core/sort sort 'clojure.core/sort-by sort-by
   'clojure.core/map map 'clojure.core/filter filter 'clojure.core/remove remove
   'clojure.core/keep keep 'clojure.core/keep-indexed keep-indexed 'clojure.core/map-indexed map-indexed
   'clojure.core/mapcat mapcat 'clojure.core/concat concat
   'clojure.core/take take 'clojure.core/take-while take-while 'clojure.core/take-nth take-nth
   'clojure.core/drop drop 'clojure.core/drop-while drop-while
   'clojure.core/interpose interpose 'clojure.core/interleave interleave
   'clojure.core/distinct distinct 'clojure.core/dedupe dedupe
   'clojure.core/split-at split-at 'clojure.core/split-with split-with
   'clojure.core/mapv mapv 'clojure.core/filterv filterv
   'clojure.core/reduce reduce 'clojure.core/reduce-kv reduce-kv
   'clojure.core/vec vec 'clojure.core/set set
   'clojure.core/merge merge 'clojure.core/merge-with merge-with
   'clojure.core/select-keys select-keys 'clojure.core/zipmap zipmap
   'clojure.core/assoc-in assoc-in 'clojure.core/update-in update-in 'clojure.core/get-in get-in
   'clojure.core/update update
   'clojure.core/every? every? 'clojure.core/some some 'clojure.core/not-every? not-every?
   'clojure.core/not-any? not-any?
   'clojure.core/comp comp 'clojure.core/partial partial 'clojure.core/juxt juxt
   'clojure.core/complement complement 'clojure.core/fnil fnil
   'clojure.core/every-pred every-pred 'clojure.core/some-fn some-fn
   'clojure.core/swap! swap! 'clojure.core/reset! reset! 'clojure.core/compare-and-set! compare-and-set!
   'clojure.core/swap-vals! swap-vals! 'clojure.core/reset-vals! reset-vals!
   'clojure.core/last last 'clojure.core/butlast butlast
   'clojure.core/max-key max-key 'clojure.core/min-key min-key
   'clojure.core/repeatedly repeatedly
   'clojure.string/join string/join
   'clojure.string/upper-case string/upper-case 'clojure.string/lower-case string/lower-case
   'clojure.string/capitalize string/capitalize
   'clojure.string/trim string/trim 'clojure.string/triml string/triml 'clojure.string/trimr string/trimr
   'clojure.string/blank? string/blank? 'clojure.string/includes? string/includes?
   'clojure.string/starts-with? string/starts-with? 'clojure.string/ends-with? string/ends-with?
   'clojure.string/index-of string/index-of 'clojure.string/last-index-of string/last-index-of
   'clojure.string/split-lines string/split-lines})

(def capabilities
  {:natives natives
   ;; the JVM and jolt name clojure.lang and java.* classes themselves
   :host-class (fn [token] (let [c (try (resolve token) (catch Throwable _ nil))] (when (class? c) c)))
   ;; interop the shim lacks is the host's own
   :interop-passthrough? true
   ;; deftype and defrecord instances, reify objects: their own members first
   ;; (and a java.util collection that is no Clojure collection: its own
   ;; .add / .get / .size answer)
   ;; The host core's own deftypes (clojure.core.Eduction) are its data: the
   ;; shim's methods answer for them. (jolt names a reify class
   ;; clojure.lang.IObj$reify__N; that one is the user's.)
   ;; Any other object the shim does not model (a Future, a Thread, a
   ;; StringBuilder, a java.util collection) answers its own members too.
   :user-type? (fn [x] (and (let [n (str (class x))]
                              (not (and (.startsWith n "class clojure.") (not (.contains n "$reify")))))
                            (or (instance? clojure.lang.IType x)
                                (instance? clojure.lang.IRecord x)
                                (and (instance? clojure.lang.IObj x) (not (coll? x)) (not (symbol? x)) (not (fn? x)))
                                (not (or (nil? x) (string? x) (number? x) (char? x) (boolean? x) (keyword? x)
                                         (symbol? x) (coll? x) (fn? x) (var? x) (seq? x)
                                         (instance? clojure.lang.Namespace x) (instance? java.util.regex.Pattern x)
                                         (instance? clojure.lang.IDeref x) (instance? clojure.lang.IPending x)
                                         (instance? Throwable x) (class? x) (instance? clojure.lang.MultiFn x)
                                         (.isArray (class x)))))))
   ;; the shim raises the host's own exceptions; ExceptionInfo is the class
   :new-exception new-exception
   :exception-class exception-class
   :ex-info? (fn [x] (and (instance? clojure.lang.ExceptionInfo x) (not (contains? (ex-data x) :cohost/class))))
   ;; java.util.Iterator: the host's own over its data and over user types
   :iterator (fn [x] (when (instance? java.lang.Iterable x) (.iterator ^java.lang.Iterable x)))
   :iterator? (fn [x] (instance? java.util.Iterator x))
   :iterator-has-next? (fn [it] (.hasNext ^java.util.Iterator it))
   :iterator-next! (fn [it] (.next ^java.util.Iterator it))
   ;; a match's groups and spans when asked (re-seq of a group-free pattern
   ;; asks only for group 0): the matcher is this call's own
   :re-find-at (fn [re s from]
                 (let [m (re-matcher re s)]
                   (when (.find m (int from)) [(.start m) (.end m) (delay (groups m)) (delay (spans m))])))
   :re-match-full (fn [re s]
                    (let [m (re-matcher re s)]
                      (when (.matches m) [0 (count s) (delay (groups m)) (delay (spans m))])))
   ;; java.lang.Character, the JVM's own (by code point)
   :java-statics {'Character/isWhitespace #(Character/isWhitespace (int (ch %)))
                  'Character/isDigit #(Character/isDigit (int (ch %)))
                  'Character/isLetter #(Character/isLetter (int (ch %)))
                  'Character/isLetterOrDigit #(Character/isLetterOrDigit (int (ch %)))
                  'Character/isAlphabetic #(Character/isAlphabetic (int (ch %)))
                  'Character/isUpperCase #(Character/isUpperCase (int (ch %)))
                  'Character/isLowerCase #(Character/isLowerCase (int (ch %)))
                  'Character/toUpperCase #(if (number? %) (Character/toUpperCase (int %)) (Character/toUpperCase (char %)))
                  'Character/toLowerCase #(if (number? %) (Character/toLowerCase (int %)) (Character/toLowerCase (char %)))
                  'Character/digit (fn [c r] (Character/digit (int (ch c)) (int r)))
                  'Object/hashCode (fn [x] (.hashCode ^Object x))
                  'Collections/reverseOrder (fn ([] (java.util.Collections/reverseOrder))
                                              ([c] (java.util.Collections/reverseOrder c)))
                  ;; (getNumericValue and forDigit: jolt has neither; the shim's serve)
                  }
   :re-group-count (fn [re] (.groupCount (re-matcher re "")))
   :upper-case (fn [s] (.toUpperCase (str s)))
   :lower-case (fn [s] (.toLowerCase (str s)))
   :string-builder (fn [] (StringBuilder.))
   :sb-append! (fn [^StringBuilder sb x] (.append sb (str x)) sb)
   :sb-string (fn [sb] (str sb))
   :sb? (fn [x] (instance? StringBuilder x))
   :edn-read-string (fn [opts s] (edn/read-string opts s))
   :math {'sin (fn [a] (Math/sin (double a)))
          'cos (fn [a] (Math/cos (double a)))
          'tan (fn [a] (Math/tan (double a)))
          'asin (fn [a] (Math/asin (double a)))
          'acos (fn [a] (Math/acos (double a)))
          'atan (fn [a] (Math/atan (double a)))
          'toRadians (fn [a] (Math/toRadians (double a)))
          'toDegrees (fn [a] (Math/toDegrees (double a)))
          'exp (fn [a] (Math/exp (double a)))
          'log (fn [a] (Math/log (double a)))
          'log10 (fn [a] (Math/log10 (double a)))
          'sqrt (fn [a] (Math/sqrt (double a)))
          'cbrt (fn [a] (Math/cbrt (double a)))
          'ceil (fn [a] (Math/ceil (double a)))
          'floor (fn [a] (Math/floor (double a)))
          'rint (fn [a] (Math/rint (double a)))
          'round (fn [a] (Math/round (double a)))
          'ulp (fn [a] (Math/ulp (double a)))
          'signum (fn [a] (Math/signum (double a)))
          'sinh (fn [a] (Math/sinh (double a)))
          'cosh (fn [a] (Math/cosh (double a)))
          'tanh (fn [a] (Math/tanh (double a)))
          'expm1 (fn [a] (Math/expm1 (double a)))
          'log1p (fn [a] (Math/log1p (double a)))
          'getExponent (fn [a] (Math/getExponent (double a)))
          'nextUp (fn [a] (Math/nextUp (double a)))
          'nextDown (fn [a] (Math/nextDown (double a)))
          'incrementExact (fn [a] (Math/incrementExact (long a)))
          'decrementExact (fn [a] (Math/decrementExact (long a)))
          'negateExact (fn [a] (Math/negateExact (long a)))
          'IEEEremainder (fn [a b] (Math/IEEEremainder (double a) (double b)))
          'atan2 (fn [a b] (Math/atan2 (double a) (double b)))
          'pow (fn [a b] (Math/pow (double a) (double b)))
          'addExact (fn [a b] (Math/addExact (long a) (long b)))
          'subtractExact (fn [a b] (Math/subtractExact (long a) (long b)))
          'multiplyExact (fn [a b] (Math/multiplyExact (long a) (long b)))
          'floorDiv (fn [a b] (Math/floorDiv (long a) (long b)))
          'floorMod (fn [a b] (Math/floorMod (long a) (long b)))
          'hypot (fn [a b] (Math/hypot (double a) (double b)))
          'copySign (fn [a b] (Math/copySign (double a) (double b)))
          'nextAfter (fn [a b] (Math/nextAfter (double a) (double b)))
          'scalb (fn [a b] (Math/scalb (double a) (int b)))}})

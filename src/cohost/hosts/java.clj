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
   the bench's check against JVM Clojure's answers gate every entry."
  {'clojure.core/frequencies frequencies 'clojure.core/group-by group-by
   'clojure.core/partition partition 'clojure.core/re-seq re-seq
   'clojure.core/sort sort 'clojure.core/sort-by sort-by
   'clojure.string/join string/join})

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
   :re-find-at (fn [re s from]
                 (let [m (re-matcher re s)]
                   (when (.find m (int from)) [(.start m) (.end m) (groups m) (spans m)])))
   :re-match-full (fn [re s]
                    (let [m (re-matcher re s)]
                      (when (.matches m) [0 (count s) (groups m) (spans m)])))
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

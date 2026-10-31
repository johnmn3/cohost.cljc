(ns cohost.lib-battery
  "Clojure's own standard libraries (clojure.walk, clojure.set, clojure.string,
   clojure.zip, clojure.data, clojure.edn, clojure.template, clojure.test,
   clojure.math, clojure.stacktrace, clojure.repl: 1.12.0's sources, and
   clojure.spec.alpha 0.5.238, the version 1.12.0 depends on; all vendored
   unchanged), required through cohost's loader on this host, each in its own
   namespace, and checked against JVM Clojure 1.12.0's answers."
  (:require [cohost.adapter :as host]
            [cohost.boot :as boot]))

;; Generated from JVM Clojure 1.12.0: each expected value is what the source
;; evaluates to there, after the same require. Regenerate rather than edit.
(def libraries
  [{:lib 'clojure.walk :alias 'w
    :cases
    [["postwalk-replace" "(w/postwalk-replace {:a 1} [:a {:b :a}])" (quote [1 {:b 1}])]
     ["prewalk-replace" "(w/prewalk-replace {:a 1} [:a {:b :a}])" (quote [1 {:b 1}])]
     ["keywordize-keys" "(w/keywordize-keys {\"a\" {\"b\" 1}})" (quote {:a {:b 1}})]
     ["stringify-keys" "(w/stringify-keys {:a {:b 1}})" (quote {"a" {"b" 1}})]
     ["postwalk over list, set, map" "(w/postwalk (fn [x] (if (number? x) (inc x) x)) '(1 [2 #{3}] {4 5}))" (quote (2 [3 #{4}] {5 6}))]
     ["prewalk order" "(let [acc (atom [])] (w/prewalk (fn [x] (swap! acc conj x) x) [1 [2]]) @acc)" (quote [[1 [2]] 1 [2] 2])]
     ["macroexpand-all" "(w/macroexpand-all '(when x (-> y z)))" (quote (if x (do (z y))))]]}
   {:lib 'clojure.set :alias 's
    :cases
    [["union intersection difference" "[(s/union #{1} #{2}) (s/intersection #{1 2} #{2 3}) (s/difference #{1 2} #{2})]" (quote [#{1 2} #{2} #{1}])]
     ["subset? superset?" "[(s/subset? #{1} #{1 2}) (s/superset? #{1} #{1 2})]" (quote [true false])]
     ["select project" "[(s/select odd? #{1 2 3}) (s/project #{{:a 1 :b 2}} [:a])]" (quote [#{1 3} #{{:a 1}}])]
     ["rename-keys rename" "[(s/rename-keys {:a 1 :b 2} {:a :b :b :a}) (s/rename #{{:a 1}} {:a :z})]" (quote [{:b 1, :a 2} #{{:z 1}}])]
     ["index join map-invert" "[(s/index #{{:a 1} {:a 2}} [:a]) (s/join #{{:a 1}} #{{:a 1 :b 2}}) (s/map-invert {:a 1})]" (quote [{{:a 1} #{{:a 1}}, {:a 2} #{{:a 2}}} #{{:a 1, :b 2}} {1 :a}])]]}
   {:lib 'clojure.string :alias 'str
    :cases
    [["case" "[(str/upper-case \"ab\") (str/lower-case \"AB\") (str/capitalize \"aBC\")]" (quote ["AB" "ab" "Abc"])]
     ["split" "[(str/split \"a,b,,c,,\" #\",\") (str/split \"a1b2c\" #\"\\d\" 2) (str/split \"abc\" #\"\") (str/split-lines \"a\\nb\\r\\nc\")]" (quote [["a" "b" "" "c"] ["a" "b2c"] ["a" "b" "c"] ["a" "b" "c"]])]
     ["join" "[(str/join [1 2]) (str/join \", \" [1 \"a\" :k]) (str/join \"-\" [])]" (quote ["12" "1, a, :k" ""])]
     ["replace string, char, regex" "[(str/replace \"aXbX\" \"X\" \"-\") (str/replace \"abca\" \\a \\z) (str/replace \"a1b22\" #\"\\d+\" \"#\") (str/replace \"a1b22\" #\"(\\d+)\" \"<$1>\")]" (quote ["a-b-" "zbcz" "a#b#" "a<1>b<22>"])]
     ["replace with a fn" "(str/replace \"abc\" #\"[ac]\" str/upper-case)" "AbC"]
     ["replace-first" "[(str/replace-first \"aXbX\" \"X\" \"-\") (str/replace-first \"a1b2\" #\"\\d\" \"#\") (str/replace-first \"a1b2\" #\"\\d\" (fn [d] (str \"<\" d \">\")))]" (quote ["a-bX" "a#b2" "a<1>b2"])]
     ["trim family" "[(str/trim \"  x \") (str/triml \" x \") (str/trimr \" x \") (str/trim-newline \"x\\r\\n\") (str/blank? \" \\t\") (str/blank? \"x\")]" (quote ["x" "x " " x" "x" true false])]
     ["predicates" "[(str/starts-with? \"abc\" \"ab\") (str/ends-with? \"abc\" \"bc\") (str/includes? \"abc\" \"b\")]" (quote [true true true])]
     ["index-of last-index-of" "[(str/index-of \"abcb\" \"b\") (str/index-of \"abcb\" \\b 2) (str/index-of \"abc\" \"z\") (str/last-index-of \"abcb\" \"b\") (str/last-index-of \"abcb\" \"b\" 2)]" (quote [1 3 nil 3 1])]
     ["reverse escape re-quote-replacement" "[(str/reverse \"abc\") (str/escape \"a<b>\" {\\< \"&lt;\" \\> \"&gt;\"}) (str/re-quote-replacement \"$1\\\\\")]" (quote ["cba" "a&lt;b&gt;" "\\$1\\\\"])]]}
   {:lib 'clojure.zip :alias 'z
    :cases
    [["vector-zip navigation" "(-> (z/vector-zip [1 [2 3]]) z/down z/right z/down z/node)" 2]
     ["edit and root" "(z/root (-> (z/vector-zip [1 2]) z/down (z/edit inc)))" (quote [2 2])]
     ["insert-right" "(-> (z/vector-zip [1 2]) z/down (z/insert-right 9) z/root)" (quote [1 9 2])]
     ["seq-zip" "(-> (z/seq-zip '(a (b c))) z/down z/right z/down z/node)" (quote b)]
     ["depth-first traversal" "(loop [loc (z/vector-zip [1 [2 3] 4]) acc []] (if (z/end? loc) acc (recur (z/next loc) (conj acc (z/node loc)))))" (quote [[1 [2 3] 4] 1 [2 3] 2 3 4])]
     ["remove" "(z/root (z/remove (-> (z/vector-zip [1 2 3]) z/down z/right)))" (quote [1 3])]
     ["lefts rights path" "(let [loc (-> (z/vector-zip [1 [2 3 4]]) z/down z/right z/down z/right)] [(z/lefts loc) (z/rights loc) (z/path loc)])" (quote [(2) (4) [[1 [2 3 4]] [2 3 4]]])]]}
   {:lib 'clojure.data :alias 'd
    :cases
    [["maps" "(d/diff {:a 1 :b 2} {:a 1 :b 3})" (quote ({:b 2} {:b 3} {:a 1}))]
     ["vectors" "(d/diff [1 2 3] [1 2 4])" (quote [[nil nil 3] [nil nil 4] [1 2]])]
     ["sets" "(d/diff #{1 2} #{2 3})" (quote [#{1} #{3} #{2}])]
     ["scalars and nil" "[(d/diff 1 2) (d/diff nil {:a 1}) (d/diff \"a\" \"a\")]" (quote [[1 2 nil] [nil {:a 1} nil] [nil nil "a"]])]
     ["nested" "(d/diff {:a [1 2] :b #{1}} {:a [1 3] :b #{1}})" (quote ({:a [nil 2]} {:a [nil 3]} {:b #{1}, :a [1]}))]]}
   {:lib 'clojure.edn :alias 'edn
    :cases
    [["read-string" "[(edn/read-string \"{:a [1 2]}\") (edn/read-string \"#{1 2}\") (edn/read-string \"[1 \\\"s\\\" :k sym 1.5]\")]" (quote [{:a [1 2]} #{1 2} [1 "s" :k sym 1.5]])]
     [":readers" "(edn/read-string {:readers {'foo inc}} \"#foo 1\")" 2]
     [":default" "(edn/read-string {:default (fn [t v] [t v])} \"#bar 1\")" (quote [bar 1])]
     [":eof and nil" "[(edn/read-string {:eof :done} \"\") (edn/read-string nil)]" (quote [:done nil])]]}
   {:lib 'clojure.template :alias 't
    :cases
    [["apply-template" "(t/apply-template '[x] '(+ x 1) [2])" (quote (+ 2 1))]
     ["do-template" "(t/do-template [a b] (+ a b) 1 2 3 4)" 7]]}
   {:lib 'clojure.test :alias 'ct
    :cases
    [["deftest attaches :test" "(do (ct/deftest probe-t1 (ct/is (= 1 1))) (fn? (:test (meta (var probe-t1)))))" true]
     ["a failing is reports FAIL" "(do (ct/deftest probe-t2 (ct/is (= 1 2))) (boolean (re-find #\"FAIL\" (with-out-str (binding [ct/*test-out* *out*] (ct/test-var (var probe-t2)))))))" true]
     ["thrown? are testing" "(do (ct/deftest probe-t3 (ct/testing \"ctx\" (ct/is (thrown? clojure.lang.ExceptionInfo (throw (ex-info \"x\" {})))) (ct/are [x y] (= x y) 1 1 2 2))) (let [r (atom nil)] (with-out-str (binding [ct/*test-out* *out*] (reset! r (ct/run-test-var (var probe-t3))))) [(:pass @r) (:fail @r) (:error @r)]))" (quote [3 0 0])]]}
   {:lib 'clojure.math :alias 'm
    :cases
    [["sqrt pow cbrt hypot" "[(m/sqrt 4.0) (m/pow 2.0 10.0) (m/cbrt 27.0) (m/hypot 3.0 4.0)]" (quote [2.0 1024.0 3.0 5.0])]
     ["rounding" "[(m/round 1.5) (m/round -1.5) (m/rint 2.5) (m/rint 3.5) (m/floor -1.5) (m/ceil -1.5)]" (quote [2 -1 2.0 4.0 -2.0 -1.0])]
     ["integer division" "[(m/floor-div -7 2) (m/floor-mod -7 2) (m/floor-div 7 2)]" (quote [-4 1 3])]
     ["transcendental" "[(m/log 1.0) (m/log10 1000.0) (m/exp 0.0) (m/sin 0.0) (m/atan2 0.0 1.0) (m/to-degrees m/PI)]" (quote [0.0 3.0 1.0 0.0 0.0 180.0])]
     ["constants and signs" "[m/PI m/E (m/signum -2.0) (m/copy-sign 1.0 -3.0)]" (quote [3.141592653589793 2.718281828459045 -1.0 -1.0])]
     ["ulp next-up get-exponent" "[(m/ulp 1.0) (m/next-up 1.0) (m/get-exponent 8.0) (m/scalb 1.0 3)]" (quote [2.220446049250313E-16 1.0000000000000002 3 8.0])]
     ["edge values" "[(m/log 0.0) (NaN? (m/sqrt -1.0)) (m/exp 1000.0)]" (quote [##-Inf true ##Inf])]]}
   {:lib 'clojure.stacktrace :alias 'st
    :cases
    [["root-cause" "(ex-message (st/root-cause (ex-info \"a\" {} (ex-info \"b\" {}))))" "b"]
     ["print-throwable" "(boolean (re-find #\"boom\" (with-out-str (st/print-throwable (ex-info \"boom\" {})))))" true]]}
   {:lib 'clojure.repl :alias 'r
    :cases
    [["doc of a stock fn" "(boolean (re-find #\"lazy sequence\" (with-out-str (r/doc map))))" true]
     ["demunge" "[(r/demunge \"a_b\") (r/demunge \"foo_QMARK_\") (r/demunge \"clojure.core$map\")]" (quote ["a-b" "foo?" "clojure.core/map"])]
     ["apropos" "(boolean (seq (r/apropos \"zipmap\")))" true]]}
   {:lib 'clojure.spec.alpha :alias 'sp
    :cases
    [["valid? conform" "[(sp/valid? int? 1) (sp/valid? int? :a) (sp/conform (sp/cat :a int? :b keyword?) [1 :x])]" (quote [true false {:a 1, :b :x}])]
     ["registry and keys" "(do (sp/def ::n pos-int?) (sp/def ::m (sp/keys :req-un [::n])) [(sp/valid? ::m {:n 1}) (sp/valid? ::m {:n -1}) (sp/valid? ::m {})])" (quote [true false false])]
     ["explain-data problems" "(mapv (juxt :val :path :in) (::sp/problems (sp/explain-data (sp/coll-of int?) [1 :a])))" (quote [[:a [] [1]]])]
     ["or alt nilable" "[(sp/conform (sp/or :i int? :s string?) \"x\") (sp/conform (sp/alt :k keyword? :n number?) [1]) (sp/valid? (sp/nilable int?) nil)]" (quote [[:s "x"] [:n 1] true])]
     ["unform describe" "[(sp/unform (sp/cat :a int? :b keyword?) {:a 1 :b :x}) (sp/describe (sp/cat :a int?))]" (quote [(1 :x) (cat :a int?)])]
     ["invalid and the problems key" "[(sp/conform int? :a) (contains? (sp/explain-data int? :a) :clojure.spec.alpha/problems)]" (quote [:clojure.spec.alpha/invalid true])]]}])

(defn run-lib-battery!
  "eval-str: source string -> value, in the stock user namespace. Requires each
   library under its alias, runs its cases, prints a tally per library, every
   failure, and every form of a library that failed to load."
  [eval-str]
  (let [results
        (doall
         (for [{:keys [lib alias cases]} libraries]
           (let [req-error (host/try-all (fn [] (eval-str (str "(require '[" lib " :as " alias "])")) nil)
                                         (fn [e]
                                           (host/error-message e)))
                 rs (doall
                     (for [[label src expected] cases]
                       (let [_ (when @boot/verbose? (println (str "  [lib case] " lib " / " label)))
                             [ok v] (host/try-all (fn [] [true (eval-str src)])
                                                  (fn [e]
                                                    [false (str "ERR " (host/error-message e))]))]
                         {:label label :pass (and ok (= v expected)) :got v :expected expected})))]
             {:lib lib :require-error req-error :results rs})))
        n (reduce + (map (comp count :results) results))
        passed (reduce + (map (fn [r] (count (filter :pass (:results r)))) results))]
    (println (str "libraries: " passed "/" n " cases pass across " (count results) " clojure.* libraries"))
    (doseq [{:keys [lib results require-error]} results]
      (println (str "  " lib "  " (count (filter :pass results)) "/" (count results)
                    (when require-error (str "   require failed: " require-error))))
      (let [{:keys [error entries]} (get (boot/lib-report) lib)]
        (when (and error (pos? error))
          (doseq [e entries :when (= :error (:status e))]
            (println (str "     load error: " (:name e) ": " (:error e))))))
      (doseq [r (remove :pass results)]
        (println (str "     FAIL " (:label r) "\n          got      " (pr-str (:got r)) "\n          expected " (pr-str (:expected r))))))
    results))

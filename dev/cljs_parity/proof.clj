(require '[cohost.boot-cljs :as bc])
(import '[org.graalvm.polyglot Value])

(def ctx (bc/boot!))
(println "BOOT-OK")

(defn ev [form] (bc/eval-cljs ctx form))
(defn evs [form] (let [v (ev form)] (when v (.asString v))))

(def checks
  [["arith"        "(str (+ 1 2))" "3"]
   ["map-inc"      "(pr-str (map inc [1 2 3]))" "(2 3 4)"]
   ["reduce-range" "(pr-str (reduce + (range 100)))" "4950"]
   ["sort"         "(pr-str (sort [3 1 2]))" "(1 2 3)"]
   ["filter-into"  "(pr-str (into [] (filter odd? (range 10))))" "[1 3 5 7 9]"]
   ["seq-fns"      "(pr-str (->> (range 20) (filter even?) (take 3) (map #(* % %))))" "(0 4 16)"]
   ["hash-stable"  "(str (= (hash \"hello\") (hash \"hello\")))" "true"]
   ["multimethod"  "(str (do (defmulti mm :k) (defmethod mm :a [m] 41) (inc (mm {:k :a}))))" "42"]
   ["record"       "(pr-str (do (defrecord R [a]) (assoc (->R 1) :b 2)))" "#cljs.user.R{:a 1, :b 2}"]
   ["date-epoch"   "(str (.getTime (js/Date. 0)))" "0"]
   ["error-caught" "(try (throw (js/Error. \"kaboom\")) (catch :default e (str \"caught:\" (.-message e))))" "caught:kaboom"]
   ["core-throw"   "(try (nth [1] 5) (catch :default e (.-message e)))" "No item 5 in vector of length 1"]
   ["ns-require"   "(ns proof.app (:require [clojure.string :as str] [cljs.reader :as rdr]))" nil]
   ["string-split" "(pr-str (clojure.string/split \"a,b,,c\" #\",\"))" "[\"a\" \"b\" \"\" \"c\"]"]
   ["string-regex" "(pr-str (clojure.string/replace \"foo boo\" #\"o\" \"0\"))" "\"f00 b00\""]
   ["string-join"  "(pr-str (clojure.string/join \"-\" [1 2 3]))" "\"1-2-3\""]
   ["reader-basic" "(pr-str (cljs.reader/read-string \"[1 :a \\\"x\\\"]\"))" "[1 :a \"x\"]"]
   ["reader-inst"  "(pr-str (cljs.reader/read-string \"#inst \\\"2020-01-02T03:04:05.000-00:00\\\"\"))" "#inst \"2020-01-02T03:04:05.000-00:00\""]
   ["reader-edn"   "(pr-str (cljs.reader/read-string \"{:a #{1 2} :b (3 4)}\"))" "{:a #{1 2}, :b (3 4)}"]
   ["set-union"    "(pr-str (sort (clojure.set/union #{1 2} #{2 3})))" "(1 2 3)"]
   ["data-diff"    "(pr-str (vec (clojure.data/diff {:a 1 :b 1} {:a 2 :b 1})))" "[{:a 1} {:a 2} {:b 1}]"]
   ["walk"         "(pr-str (clojure.walk/postwalk (fn [x] (if (number? x) (inc x) x)) {:a [1 2]}))" "{:a [2 3]}"]])

(def results
  (mapv (fn [[label form expected]]
          (let [got (try (evs form) (catch Throwable t (str "THREW: " (ex-message t))))]
            {:label label :expected expected :got got :pass (= expected got)}))
        checks))

(doseq [{:keys [label expected got pass]} results]
  (println (if pass "PASS" "FAIL") label (when-not pass (str " expected=" (pr-str expected) " got=" (pr-str got)))))
(println "PROOF:" (count (filter :pass results)) "/" (count results) "passed")

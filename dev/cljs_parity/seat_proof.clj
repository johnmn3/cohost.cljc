;; CLJS parity stage 2: the realm on cohost.thread's runtime-executor
;; shape. One cljs realm behind a size-1 runtime executor; work
;; crosses as forms + data (submit-form / submit-call), results cross
;; back as data through the layer future.
;;
;;   dev/cljs_parity/run.sh seat_proof.clj graal
;;   dev/cljs_parity/run.sh seat_proof.clj node
;;
;; The same checks run on both seats: the shim and the boot are the
;; seat-independent part; only the realm's host differs.
(require 'cohost.hosts.auto)
(require '[cohost.thread :as thread]
         '[cohost.cljs-pool :as pool])

(def seat (keyword (or (first *command-line-args*) "graal")))
(println "SEAT" seat "(the realm boots on first submit)")

(def ex (pool/executor 1 {:seat seat}))

(defn sub [ns-sym form & args]
  (thread/deref-future (apply thread/submit-form ex ns-sym form args)))

(defn call* [qsym & args]
  (thread/deref-future (apply thread/submit-call ex qsym args)))

(def checks
  [["plus"        #(call* 'cljs.core/+ 1 2) 3]
   ["str-cat"     #(call* 'cljs.core/str "a" "b" "c") "abc"]
   ["apply-vec"   #(sub 'cljs.user '(fn [xs] (reduce + xs)) [1 2 3 4]) 10]
   ["map-result"  #(sub 'cljs.user '(fn [m] (assoc m :c 3)) {:a 1 :b 2}) {:a 1 :b 2 :c 3}]
   ["vec-result"  #(sub 'cljs.user '(fn [xs] (into [] (map inc xs))) [1 2 3]) [2 3 4]]
   ["string-join" #(call* 'clojure.string/join "-" ["a" "b" "c"]) "a-b-c"]
   ["set-union"   #(call* 'clojure.set/union #{1 2} #{2 3}) #{1 2 3}]
   ["reader"      #(call* 'cljs.reader/read-string "[1 :a \"x\"]") [1 :a "x"]]
   ["walk"        #(sub 'cljs.user '(fn [] (clojure.walk/postwalk (fn [x] (if (number? x) (inc x) x)) {:a [1 2]}))) {:a [2 3]}]
   ["js-error"    #(sub 'cljs.user '(fn [] (try (throw (js/Error. "kaboom")) (catch :default e (.-message e))))) "kaboom"]
   ["kw-result"   #(sub 'cljs.user '(fn [k] (keyword "proof" (name k))) :seat) :proof/seat]
   ["def-persist" #(do (sub 'cljs.user '(fn [] (do (def proof-x 41) nil)))
                       (sub 'cljs.user '(fn [] (inc proof-x)))) 42]])

(def results
  (mapv (fn [[label thunk expected]]
          (let [got (try (thunk) (catch Throwable t (str "THREW: " (ex-message t))))]
            {:label label :expected expected :got got :pass (= expected got)}))
        checks))

(doseq [{:keys [label expected got pass]} results]
  (println (if pass "PASS" "FAIL") label (when-not pass (str " expected=" (pr-str expected) " got=" (pr-str got)))))
(println "SEAT-PROOF" seat ":" (count (filter :pass results)) "/" (count results) "passed")
(System/exit (if (every? :pass results) 0 1))

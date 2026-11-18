;; CLJS parity, string requires: a namespace graph whose files
;; string-require JS modules (["crypto" :as nc] -- eve's node
;; adapters' shape) loads into a :node realm through boot!'s
;; :load-ns, and the modules work: the analyzer takes the names
;; from its node-module index, and each namespace's node$module$
;; vars are assigned from the realm's own require (the seat's
;; :require-root anchors it).
;;
;;   dev/cljs_parity/run.sh string_require_proof.clj <fixture-app-dir>
;;
;; Checks:
;;   graph-loads     the executor's boot (with :load-ns) completes
;;   crypto-call     sha256-hex of "abc" is the known digest
;;   node-fs-call    fs-there? answers true and false for real paths
;;   promise-all     (js/Promise.all ...) keeps its receiver through
;;                   the js rewrite (an unbound call throws)
(require 'cohost.hosts.auto)
(require '[cohost.thread :as thread]
         '[cohost.cljs-pool :as pool])

(def fixture (first *command-line-args*))
(when-not fixture
  (println "usage: string_require_proof.clj <fixture-app-dir>")
  (System/exit 2))
(println "FIXTURE" fixture)

(def results (atom []))
(defn check [label got expected]
  (let [pass (= got expected)]
    (swap! results conj pass)
    (println (if pass "PASS" "FAIL") label
             (when-not pass (str " -- expected=" (pr-str expected) " got=" (pr-str got))))))

(def ex (pool/executor 1 {:seat :node :require-root fixture
                          :load-ns '[string-require-test]}))
(check "graph-loads" (some? ex) true)

(defn sub [ns-sym form & args]
  (thread/deref-future (apply thread/submit-form ex ns-sym form args)))

(check "crypto-call"
       (sub 'cljs.user '(fn [] (string-require-test/sha256-hex "abc")))
       "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

(check "node-fs-call"
       [(sub 'cljs.user '(fn [p] (string-require-test/fs-there? p))
             (str fixture "/package.json"))
        (sub 'cljs.user '(fn [p] (string-require-test/fs-there? p))
             (str fixture "/no-such-file"))]
       [true false])

(sub 'cljs.user '(fn [] (string-require-test/promise-sum!)))
(check "promise-all"
       (loop [n 0]
         (let [v (sub 'cljs.user '(fn [] @string-require-test/promise-outcome))]
           (if (or (= 42 v) (= n 20))
             v
             (do (Thread/sleep 250) (recur (inc n))))))
       42)

(thread/shutdown-executor! ex)

(let [rs @results]
  (println "STRING-REQUIRE:" (count (filter identity rs)) "/" (count rs) "passed")
  (System/exit (if (every? identity rs) 0 1)))

;; CLJS parity stage 2: macro wrapping. A macro whose expansion
;; introduces a js/* symbol bypasses the per-form rewrite (the
;; expansion materializes during analysis). Realm A boots without
;; wrapping: the program below must fail -- the expansion's
;; js/COMPILED is emitted as a raw global and the realm throws
;; ReferenceError. Realm B boots with :wrap-macros true: the same
;; program loads, its values are the shim's, and the whole stock boot
;; itself ran under wrapping (core.cljc's macros wrapped as they were
;; interned), so B's boot checks double as the no-regression proof.
;;
;;   dev/cljs_parity/run.sh macro_wrap_proof.clj
(require '[cohost.boot-cljs :as bc])

(def program
  (str "(ns wrap.app (:require-macros [proof.wrap-macros :as pm]))\n"
       "(def compiled-val (pm/shim-compiled))\n"
       "(def process-val (pm/shim-process))\n"
       "(def err-msg (try (throw (pm/make-err \"wrapped\")) (catch :default e (.-message e))))\n"
       "(def inf-val (pm/pos-inf))\n"))

(def results (atom []))
(defn check [label pass detail]
  (swap! results conj {:label label :pass pass})
  (println (if pass "PASS" "FAIL") label (when-not pass (str " -- " detail))))

;; --- realm A: no wrapping; the program must throw -------------------
(def ctx-a (bc/boot!))
(println "BOOT-A-OK (no wrapping)")
(let [thrown (try (bc/compile-string! ctx-a program "<wrap-app>" true)
                  nil
                  (catch Throwable t t))]
  (check "unwrapped-program-throws" (some? thrown) "program loaded without wrapping")
  (when thrown
    (let [chain (loop [t thrown, acc []]
                  (if t (recur (.getCause t) (conj acc (str (ex-message t))))
                        acc))
          text (apply str chain)]
      ;; the thrower is a raw-global ReferenceError from an expansion's
      ;; js/* symbol. (js/process is the one that throws on this seat:
      ;; goog's base.js happens to define a global COMPILED, so the
      ;; js/COMPILED def reads it silently -- same wrong provenance,
      ;; no observable throw. The process def is the discriminator.)
      (check "unwrapped-failure-is-the-js-global"
             (boolean (re-find #"ReferenceError: (process|COMPILED) is not defined" text))
             (str "chain: " (pr-str chain))))))

;; --- realm B: wrapping on; boot + program + values ------------------
(def ctx-b (bc/boot! (bc/make-realm) {:wrap-macros true}))
(println "BOOT-B-OK (wrapping on; the stock boot ran under it)")

(defn evb [form] (some-> (bc/eval-cljs ctx-b form) (.asString)))

(let [loaded (try (bc/compile-string! ctx-b program "<wrap-app>" true)
                  true
                  (catch Throwable t (str "THREW: " (ex-message t))))]
  (check "wrapped-program-loads" (= true loaded) (str loaded)))

(doseq [[label form expected]
        [["boot-arith"    "(str (+ 1 2))" "3"]
         ["boot-string"   "(pr-str (clojure.string/split \"a,b\" #\",\"))" "[\"a\" \"b\"]"]
         ["boot-reader"   "(pr-str (cljs.reader/read-string \"[1 :a]\"))" "[1 :a]"]
         ["compiled-is-shim-false" "(str wrap.app/compiled-val)" "false"]
         ["process-is-guarded"     "(str (exists? wrap.app/process-val))" "false"]
         ["error-through-shim"     "(str wrap.app/err-msg)" "wrapped"]
         ["dotted-through-shim"    "(str wrap.app/inf-val)" "Infinity"]]]
  (let [got (try (evb form) (catch Throwable t (str "THREW: " (ex-message t))))]
    (check label (= expected got) (str "expected=" (pr-str expected) " got=" (pr-str got)))))

(let [rs @results]
  (println "WRAP-PROOF:" (count (filter :pass rs)) "/" (count rs) "passed")
  (System/exit (if (every? :pass rs) 0 1)))

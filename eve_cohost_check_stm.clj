;; STM lane: booted-context verification. Boots the stock core on the
;; JVM seat with :eve-stm in force (refs are cohost.tx layer refs,
;; transactions run the world-atom construction) and the thread names
;; treated as host-lacked (agents/futures are the thread layer's, so
;; the STM×agent seam is the layer's on both sides); then runs
;; stock-shaped programs in the booted user namespace.
;;   clojure -Sdeps '{:paths ["src"]}' -M eve_cohost_check_stm.clj
(require 'cohost.hosts.jvm 'cohost.boot 'cohost.policy 'cohost.adapter)
(alias 'boot 'cohost.boot)
(alias 'host 'cohost.adapter)

(def report (boot/boot! {:host-lacks cohost.policy/thread-names
                         :eve-stm true}))
(boot/print-report report)
(boot/make-user-ns!)

(def checks (atom []))
(defn check [label src expected]
  (let [[ok v] (host/try-all (fn [] [true (boot/eval-str src {:session? false})])
                             (fn [e] [false (str "ERR " (host/error-message e))]))
        pass (and ok (= v expected))]
    (swap! checks conj pass)
    (println (if pass "PASS" "FAIL") label "=>" (pr-str v)
             (when-not pass (str " (expected " (pr-str expected) ")")))))

(check "ref + deref" "(do (def r1 (ref 41)) @r1)" 41)
(check "dosync alter" "(do (dosync (alter r1 inc)) @r1)" 42)
(check "ref-set" "(do (dosync (ref-set r1 7)) @r1)" 7)
(check "commute" "(do (dosync (commute r1 + 5)) @r1)" 12)
(check "alter outside tx throws"
       "(try (alter r1 inc) :no-throw (catch IllegalStateException e :threw))"
       :threw)
(check "sync runs" "(do (sync nil (ref-set r1 9)) @r1)" 9)
(check "isRunning inside a transaction"
       "(dosync (clojure.lang.LockingTransaction/isRunning))" true)
(check "isRunning outside" "(clojure.lang.LockingTransaction/isRunning)" false)

(check "setup two refs" "(do (def ra (ref 10)) (def rb (ref 100)) :ok)" :ok)
(check "abort rolls back both refs"
       "(do (try (dosync (ref-set ra 11) (ref-set rb 99) (throw (Exception. \"boom\"))) (catch Exception e :caught)) [@ra @rb])"
       [10 100])
(check "snapshot: read, other thread commits, read again"
       "(do (def seen (atom nil)) (def t (Thread. (fn [] (dosync (let [x @ra] (Thread/sleep 60) (reset! seen [x @ra])))))) (.start t) (Thread/sleep 20) (dosync (ref-set ra 555)) (.join t) [@seen @ra])"
       [[10 10] 555])

(check "validator at commit"
       "(do (def rv (ref 5 :validator (fn [v] (>= v 0)))) (try (dosync (ref-set rv -1)) :no-throw (catch IllegalStateException e :threw)))"
       :threw)
(check "validator left the value" "@rv" 5)
(check "set-validator!/get-validator"
       "(do (set-validator! rv (fn [v] (< v 100))) (try (dosync (ref-set rv 500)) :no-throw (catch IllegalStateException e :threw)))"
       :threw)
(check "watch fires once at commit"
       "(do (def wlog (atom [])) (add-watch rv :w (fn [k r old new] (swap! wlog conj [old new]))) (dosync (ref-set rv 6) (ref-set rv 7)) @wlog)"
       [[5 7]])
(check "history count" "(do (dosync (ref-set rv 8)) (ref-history-count rv))" 2)
(check "ref meta" "(do (def rm (ref 1 :meta {:tag :x})) (:tag (meta rm)))" :x)

;; the STM x agent seam: sends in a transaction
(check "setup agent" "(do (def ag (agent [])) :ok)" :ok)
(check "send in a committing tx fires once"
       "(do (dosync (send ag conj :fired)) (Thread/sleep 100) @ag)"
       [:fired])
(check "send in an aborting tx never fires"
       "(do (try (dosync (send ag conj :nope) (throw (Exception. \"boom\"))) (catch Exception e :caught)) (Thread/sleep 100) @ag)"
       [:fired])
(check "agent error + restart still stock-shaped"
       "(do (def ag2 (agent 0 :error-mode :fail)) (send ag2 (fn [_] (throw (Exception. \"x\")))) (Thread/sleep 100) (restart-agent ag2 42) @ag2)"
       42)
(check "await drains"
       "(do (def ag3 (agent 0)) (send ag3 inc) (await ag3) @ag3)" 1)

(println (str "CHECK-STM: " (count (filter identity @checks)) "/" (count @checks) " PASS"))
(System/exit (if (every? identity @checks) 0 1))

;; Eve-in-cohost unit 5: booted-context verification. Boots the stock
;; core on the JVM seat with policy/thread-names treated as host-lacked
;; (the unit 1/2 thread fill) AND :eve-atoms on (unit 5), with the
;; seat's Eve hosting installed first; then runs stock-shaped programs
;; in the booted user namespace whose atoms are Eve-backed layer
;; atoms, and checks the registry (the startup domain) from the host.
;;   clojure -Sdeps '{:paths ["src"]}' -M eve_cohost_check_u5.clj
;; (Eve's sources must be on the classpath for the hosting:
;;   -Sdeps '{:paths ["src" "/home/hatch/src/eve/src"]}')
(require 'cohost.hosts.jvm 'cohost.boot 'cohost.policy 'cohost.adapter)
(require '[cohost.atom :as al] '[cohost.atom-eve :as atom-hosting])
(alias 'boot 'cohost.boot)
(alias 'host 'cohost.adapter)

;; the check bookkeeping is defined BEFORE the boot: after
;; make-user-ns! the user namespace's own atom/swap! names are the
;; booted ones (that is the point of the exercise), so this file's
;; harness keeps host atoms by creating them now
(def checks (atom []))
(defn check [label src expected]
  (let [[ok v] (host/try-all (fn [] [true (boot/eval-str src {:session? false})])
                             (fn [e] [false (str "ERR " (host/error-message e))]))
        pass (and ok (= v expected))]
    (swap! checks conj pass)
    (println (if pass "PASS" "FAIL") label "=>" (pr-str v) (when-not pass (str " (expected " (pr-str expected) ")")))))
(defn check-host [label expected actual]
  (let [pass (= expected actual)]
    (swap! checks conj pass)
    (println (if pass "PASS" "FAIL") label "=>" (pr-str actual) (when-not pass (str " (expected " (pr-str expected) ")")))))

(atom-hosting/install!)

(def report (boot/boot! {:host-lacks cohost.policy/thread-names
                         :eve-atoms true}))
(boot/print-report report)
(boot/make-user-ns!)

(check-host "boot errors" 0 (:error report))

;; -- the thread layer over the new atoms (units 1-2, unregressed) ------
(check "future deref"
       "@(future (+ 1 2))" 3)
(check "binding conveyance into future"
       "(do (def ^:dynamic *xv* 1) (binding [*xv* 42] @(future *xv*)))" 42)
(check "pmap"
       "(vec (pmap (fn [x] (* x x)) (range 10)))" [0 1 4 9 16 25 36 49 64 81])
(check "promise deliver via future"
       "(do (def pr1 (promise)) (future (deliver pr1 7)) @pr1)" 7)
(check "agent send + await"
       "(do (def a1 (agent 0)) (send a1 inc) (send a1 (fn [s] (+ s 10))) (await a1) @a1)" 11)
(check "agent error mode continue"
       "(do (def a4 (agent 0 :error-mode :continue)) (send a4 (fn [s] (/ 1 0))) (send a4 inc) (await a4) @a4)" 1)
(check "delay realized"
       "(let [d (delay 40)] [(realized? d) @d (realized? d)])" [false 40 true])

;; -- clojure.lang.Atom over Eve atoms -----------------------------------
(check "atom swap + deref"
       "(do (def a (atom 5)) [(swap! a inc) @a])" [6 6])
(check "atom reset"
       "[(reset! a 10) @a]" [10 10])
(check "compare-and-set!"
       "[(compare-and-set! a 10 11) (compare-and-set! a 10 12) @a]" [true false 11])
(check "cas by equality on a collection root"
       "(do (def c (atom {:x [1 2]})) [(compare-and-set! c {:x [1 2]} {:x [3]}) @c])" [true {:x [3]}])
(check "swap-vals! / reset-vals!"
       "(do (def sv (atom 1)) [(swap-vals! sv inc) (reset-vals! sv 9) @sv])" [[1 2] [2 9] 9])
(check "validator rejects and accepts"
       "(do (def va (atom 2)) (set-validator! va even?) [(try (swap! va inc) (catch IllegalStateException e :rejected)) @va (swap! va + 2) (fn? (get-validator va))])"
       [:rejected 2 4 true])
(check "watches fire with exact pairs"
       "(do (def wa (atom 0)) (def seen (atom [])) (add-watch wa :k (fn [k r o n] (swap! seen conj [k o n]))) (swap! wa inc) (reset! wa 5) (remove-watch wa :k) (swap! wa inc) @seen)"
       [[:k 0 1] [:k 1 5]])
(check "getWatches interop"
       "(do (def gw (atom 0)) (add-watch gw :k (fn [k r o n])) (keys (.getWatches gw)))" [:k])
(check "meta at creation and with-meta sharing"
       "(do (def ma (atom 1 :meta {:doc \"d\"})) [(meta ma) (swap! (with-meta ma {:doc \"e\"}) inc) @ma])"
       [{:doc "d"} 2 2])
(check "instance? Atom and IAtom"
       "[(instance? clojure.lang.Atom a) (instance? clojure.lang.IAtom a)]" [true true])
(check "atom holding a fn (host seat)"
       "(do (def hf (atom (fn [x] (inc x)))) (def r1 ((deref hf) 1)) (swap! hf (fn [_] (fn [x] (* 2 x)))) [r1 ((deref hf) 21)])"
       [2 42])
(check "atom of a vector, conj"
       "(do (def vv (atom [1 2])) (swap! vv conj 3) @vv)" [1 2 3])
(check "atom inside a future"
       "(do (def fa (atom 0)) [@(future (swap! fa inc)) @fa])" [1 1])
(check "atom inside an agent action"
       "(do (def aa (atom 0)) (def ag (agent 0)) (send ag (fn [s] (swap! aa inc) s)) (await ag) @aa)" 1)
(check "pmap over atoms"
       "(do (def ps (mapv (fn [_] (atom 0)) (range 3))) (doall (pmap (fn [x] (swap! x inc)) ps)) (mapv deref ps))"
       [1 1 1])

;; -- vars for the registry checks ----------------------------------------
(check "def a data var" "(do (def answer 42) :ok)" :ok)
(check "def a fn var" "(do (def myfn (fn [x] x)) :ok)" :ok)
(check "def + alter-var-root"
       "(do (def dyn {:a 1}) (alter-var-root #'dyn assoc :b 2) dyn)" {:a 1 :b 2})

;; -- the registry, from the host ------------------------------------------
;; a program's defs intern through the host compiler on this seat;
;; the registry tracks them by sweeps at load boundaries -- this is
;; the user namespace's boundary, swept as a library's would be
(al/publish-namespace! 'user)
(def table (al/registry-table))
(check-host "registry: data root held inline" {:root 42} (get-in table ['user 'answer]))
(check-host "registry: fn root is a resident" {:resident true} (get-in table ['user 'myfn]))
(check-host "registry: alter-var-root republished" {:root {:a 1 :b 2}} (get-in table ['user 'dyn]))
(check-host "registry: core intern recorded" {:resident true} (get-in table ['cohost.core 'swap!]))
(check-host "hosting state" true (:startup-domain? (atom-hosting/hosting-state)))

(let [n (count @checks) f (count (filter not @checks))]
  (println (str "CHECK-U5: " n " checks, " f " failures"))
  (System/exit (if (zero? f) 0 1)))

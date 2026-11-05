;; Eve-in-cohost unit 7: booted-context verification of tiered atoms.
;; Boots the stock core on the JVM seat with the unit 1/2 thread fill and
;; :eve-atoms on (units 1-5), the seat's Eve hosting installed first; then
;; runs stock-shaped programs in the booted user namespace: the kind is
;; chosen ONLY by metadata on (atom x :meta {...}) -- the program uses
;; clojure.core's atom/swap!/reset!/compare-and-set!/alter-meta!/future
;; and nothing else.
;;   dev/u7/clj-eve.sh eve_cohost_check_u7.clj <fresh-dir>
(require 'cohost.hosts.jvm 'cohost.boot 'cohost.policy 'cohost.adapter)
(require '[cohost.atom :as al] '[cohost.atom-eve :as atom-hosting])
(alias 'boot 'cohost.boot)
(alias 'host 'cohost.adapter)

(def base (or (first *command-line-args*) (str "/tmp/u7-check-" (System/nanoTime))))
(.mkdirs (java.io.File. base))

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
(def report (boot/boot! {:host-lacks cohost.policy/thread-names :eve-atoms true}))
(boot/print-report report)
(boot/make-user-ns!)
(check-host "boot errors" 0 (:error report))

(boot/eval-str (str "(def base " (pr-str base) ")") {:session? false})

;; -- the default stays :mem -------------------------------------------------
(check "plain atom is mem" "(do (def m (atom 1)) [(swap! m inc) (cohost.atom/kind m)])" [2 :mem])

;; -- a disk atom, chosen by metadata -----------------------------------------
(check "disk atom by meta"
       "(do (def d (atom {:n 0} :meta {:eve/kind :disk :eve/path (str base \"/db\") :eve/name \"app/d\"}))
            [(cohost.atom/kind d) @d])"
       [:disk {:n 0}])
(check "swap! / reset! / compare-and-set!"
       "[(swap! d update :n inc) (reset! d {:n 5}) (compare-and-set! d {:n 5} {:n 6}) (compare-and-set! d {:n 5} {:n 7}) @d]"
       [{:n 1} {:n 5} true false {:n 6}])
(check "swap-vals! / reset-vals!"
       "[(swap-vals! d update :n inc) (reset-vals! d {:n 0})]"
       [[{:n 6} {:n 7}] [{:n 7} {:n 0}]])
(check "meta reads back"
       "(:eve/kind (meta d))" :disk)
(check "watch on a disk atom"
       "(do (def seen (atom [])) (add-watch d :w (fn [_ _ o n] (swap! seen conj [o n]))) (swap! d update :n inc) (remove-watch d :w) @seen)"
       [[{:n 0} {:n 1}]])
(check "validator on a disk atom"
       "(do (set-validator! d #(< (:n %) 10)) [(try (reset! d {:n 99}) (catch Exception e :refused)) @d])"
       [:refused {:n 1}])
(check "a second atom naming the place joins it"
       "(do (set-validator! d nil) (def d2 (atom :ignored :meta (meta d))) (swap! d2 update :n + 100) @d)"
       {:n 101})

;; -- loud failures ----------------------------------------------------------------
(check "unrepresentable root at creation"
       "(try (atom {:f inc} :meta {:eve/kind :disk :eve/path (str base \"/db\") :eve/name \"app/bad\"}) :no-throw
             (catch Exception e (:cohost.atom/error (ex-data e))))"
       :unrepresentable)
(check "unrepresentable publish"
       "(try (reset! d {:f inc}) :no-throw (catch Exception e (:cohost.atom/error (ex-data e))))"
       :unrepresentable)
(check "downgrade by meta"
       "(try (reset-meta! d {}) :no-throw (catch Exception e (:cohost.atom/error (ex-data e))))"
       :downgrade)
(check "unknown kind"
       "(try (atom 1 :meta {:eve/kind :tape}) :no-throw (catch Exception e (:cohost.atom/error (ex-data e))))"
       :unknown-kind)

;; -- promotion by alter-meta! ----------------------------------------------------------
(check "mem -> disk promotion keeps the root"
       "(do (def p (atom [1 2 3]))
            (swap! p conj 4)
            (alter-meta! p assoc :eve/kind :disk :eve/path (str base \"/db\") :eve/name \"app/p\")
            (swap! p conj 5)
            [(cohost.atom/kind p) @p @(atom nil :meta (meta p))])"
       [:disk [1 2 3 4 5] [1 2 3 4 5]])
(check "promotion of a fn-holding atom fails by name, atom unharmed"
       "(do (def hp (atom {:h inc}))
            [(try (alter-meta! hp assoc :eve/kind :disk :eve/path (str base \"/db\") :eve/name \"app/hp\") :no-throw
                  (catch Exception e (:cohost.atom/error (ex-data e))))
             (cohost.atom/kind hp)
             ((:h @hp) 1)])"
       [:unrepresentable :mem 2])

;; -- bindings conveyance through core's future (binding-conveyor-fn) -----------------
(check "a conveying shared atom + a conveyed var"
       "(do (def shared (atom {} :meta {:eve/kind :disk :eve/path (str base \"/db\") :eve/name \"app/s\" :eve/conveys true}))
            (def ^{:dynamic true :eve/convey shared} *tenant* nil)
            (binding [*tenant* \"acme\"] @(future *tenant*)))"
       "acme")
(check-host "the frame rode the shared atom's meta-atom"
            {'user/*tenant* "acme"}
            (into {} @(:frame (al/state-of (boot/eval-str "shared" {:session? false})))))

(let [n (count @checks) f (count (remove true? @checks))]
  (println (str "CHECK-U7: " n " checks, " f " failures"))
  (atom-hosting/close-disk-domain! (str base "/db"))
  (shutdown-agents)
  (System/exit (if (zero? f) 0 1)))

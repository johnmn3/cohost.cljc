;; Eve-in-cohost, unit 7: the tiered-atom layer probe (JVM seat, one process).
;;   dev/u7/clj-eve.sh probe_tier.clj <fresh-dir>
;; Exercises cohost.atom's kinds through the layer directly (the shim
;; carries the meta unchanged: (atom x :meta m) -> new-Atom -> make-atom):
;; the default :mem kind, :disk atoms (create, join by path+name, CAS,
;; watches, validator), the loud failures (unrepresentable root at
;; creation / publish / promotion, missing place, unknown kind,
;; downgrade, re-point), promotion mem -> disk and disk -> net, and a
;; :net atom over Eve's own gateway + presigned in-memory object store
;; (the network-atoms README shape), with a disk writer of the gateway's
;; domain seen by the net client.
(require 'cohost.hosts.auto)
(require '[clojure.string :as str]
         '[cohost.atom :as al]
         '[cohost.atom-eve :as hosting]
         '[eve.atom :as ea]
         '[eve.net.gateway :as gw]
         '[eve.process :as proc])

(hosting/install!)
(al/set-fill! true)

(def base (or (first *command-line-args*) (str "/tmp/u7-probe-tier-" (System/nanoTime))))
(.mkdirs (java.io.File. base))

(def checks (atom 0))
(def failures (atom []))
(defn check [label expected actual]
  (swap! checks inc)
  (if (= expected actual)
    (println "PASS" label "=>" (pr-str actual))
    (do (swap! failures conj label)
        (println "FAIL" label "expected" (pr-str expected) "got" (pr-str actual)))))
(defn check-true [label actual] (check label true (boolean actual)))
(defn err-of [thunk]
  (try (thunk) :no-throw
       (catch Throwable e {:error (:cohost.atom/error (ex-data e)) :msg (ex-message e)})))

;; -- :mem stays the default ----------------------------------------------
(def m (al/make-atom 1))
(check "no meta -> :mem" :mem (al/kind m))
(def m2 (al/make-atom 1 {:meta {:doc "x"}}))
(check "meta without kind -> :mem" :mem (al/kind m2))

;; -- :disk: create, swap, CAS, vals ----------------------------------------
(def dpath (str base "/d1"))
(def dmeta {:eve/kind :disk :eve/path dpath :eve/name "u7/counter"})
(def d (al/make-atom {:n 0} {:meta dmeta}))
(check "disk kind" :disk (al/kind d))
(check "disk deref" {:n 0} (al/deref-atom d))
(check "disk swap" {:n 1} (al/swap-atom! d update :n inc))
(check "disk swap args" {:n 11} (al/swap-atom! d update :n + 10))
(check "disk reset" {:n 5} (al/reset-atom! d {:n 5}))
(check "disk cas hit" true (al/compare-and-set-atom! d {:n 5} {:n 6}))
(check "disk cas miss" false (al/compare-and-set-atom! d {:n 5} {:n 7}))
(check "disk after cas" {:n 6} (al/deref-atom d))
(check "disk swap-vals" [{:n 6} {:n 7}] (al/swap-vals-atom! d update :n inc))
(check "disk reset-vals" [{:n 7} {:n 0}] (al/reset-vals-atom! d {:n 0}))
(check-true "domain files on disk" (.exists (java.io.File. (str dpath ".root"))))
(check "meta kept" dmeta (al/meta-of d))

;; -- watches + validator on a disk atom ------------------------------------
(def seen (atom []))
(al/add-watch-atom! d :w (fn [k _ o n] (swap! seen conj [k o n])))
(al/swap-atom! d update :n inc)
(check "disk watch pair" [[:w {:n 0} {:n 1}]] @seen)
(al/remove-watch-atom! d :w)
(al/set-validator-atom! d (fn [v] (< (:n v) 100)))
(check "disk validator refuses" "Invalid reference state"
       (try (al/reset-atom! d {:n 500}) :no-throw (catch Throwable e (ex-message e))))
(check "disk value after refused" {:n 1} (al/deref-atom d))
(al/set-validator-atom! d nil)

;; -- a second layer atom naming the same place joins it ---------------------
(def d-join (al/make-atom {:n :ignored} {:meta dmeta}))
(check "join sees the existing root (init ignored)" {:n 1} (al/deref-atom d-join))
(al/swap-atom! d-join update :n inc)
(check "first handle sees the joiner's swap" {:n 2} (al/deref-atom d))

;; -- loud failures ------------------------------------------------------------
(def f0 (fn [x] x))
(check "unrepresentable at creation"
       :unrepresentable (:error (err-of #(al/make-atom {:f f0} {:meta (assoc dmeta :eve/name "u7/bad")}))))
(check-true "the error names the value"
            (str/includes? (:msg (err-of #(al/make-atom {:f f0} {:meta (assoc dmeta :eve/name "u7/bad2")}))) "user$f0"))
;; a swap that puts a fn into the atom's own Eve map: Eve's assoc under the
;; shared write may refuse first (its own loud error), else the layer does
(let [e (err-of #(al/swap-atom! d assoc :f f0))]
  (println "publish refusal:" (pr-str e))
  (check-true "unrepresentable at publish: throws" (map? e)))
(check "unrepresentable at publish (host value, layer check)" :unrepresentable
       (:error (err-of #(al/swap-atom! d (fn [_] {:f f0})))))
(check "value unchanged after refused publish" {:n 2} (al/deref-atom d))
(check "unrepresentable reset" :unrepresentable (:error (err-of #(al/reset-atom! d (Object.)))))
(check "missing :eve/path" :missing-place (:error (err-of #(al/make-atom 1 {:meta {:eve/kind :disk :eve/name "x/y"}}))))
(check "missing :eve/name" :missing-place (:error (err-of #(al/make-atom 1 {:meta {:eve/kind :disk :eve/path dpath}}))))
(check "unknown kind" :unknown-kind (:error (err-of #(al/make-atom 1 {:meta {:eve/kind :cloud}}))))
(check "downgrade disk -> mem" :downgrade (:error (err-of #(al/set-meta-atom! d {:doc "plain"}))))
(check "re-point a disk atom" :re-point (:error (err-of #(al/set-meta-atom! d (assoc dmeta :eve/name "u7/other")))))
(check "meta change within the same place is fine" :disk
       (do (al/alter-meta-atom d assoc [:doc "kept"]) (al/kind d)))
(check "still the same root" {:n 2} (al/deref-atom d))

;; -- promotion mem -> disk ------------------------------------------------------
(def p (al/make-atom {:k [1 2 3]}))
(al/swap-atom! p update :k conj 4)
(def pmeta {:eve/kind :disk :eve/path (str base "/d2") :eve/name "u7/promoted"})
(al/alter-meta-atom p merge [pmeta])
(check "promoted kind" :disk (al/kind p))
(check "promoted root kept" {:k [1 2 3 4]} (al/deref-atom p))
(al/swap-atom! p update :k conj 5)
(def p-join (al/make-atom nil {:meta pmeta}))
(check "a joiner sees the promoted atom's swaps" {:k [1 2 3 4 5]} (al/deref-atom p-join))
;; a mem atom holding a fn: promotion refused by name, the atom unharmed
(def hp (al/make-atom {:handler f0}))
(check "promotion of an unrepresentable root"
       :unrepresentable (:error (err-of #(al/set-meta-atom! hp (assoc pmeta :eve/name "u7/never")))))
(check "refused promotion leaves it :mem" :mem (al/kind hp))
(check-true "and working" (identical? f0 (:handler (al/swap-atom! hp assoc :x 1))))
(check-true "and its target was never created"
            (nil? (get (ea/read-pinned (ea/persistent-atom-domain (str base "/d2")) identity) "u7/never")))
;; promotion into an occupied target
(def occ (al/make-atom {:other true}))
(check "promotion into an occupied target"
       :promotion-target-occupied (:error (err-of #(al/set-meta-atom! occ pmeta))))
(check "occupied refusal leaves it :mem" :mem (al/kind occ))

;; -- :net over Eve's gateway (in-process, presigned in-memory store) -------
(defn mem-store [secret]
  (let [blobs (atom {})]
    (fn [{:keys [method url body]}]
      (let [[_ k q] (re-find #"/blobs/([^?]+)\?(.*)" url)
            pm (into {} (map #(vec (str/split % #"=" 2))) (str/split q #"&"))
            mode ({"PUT" "write" "GET" "read" "HEAD" "head"} method)
            ok? (and mode (= mode (pm "mode"))
                     (gw/hmac-ok? secret k mode (parse-long (pm "exp")) (pm "sig") (quot (proc/now-ms) 1000)))]
        (cond (not ok?) {:status 403 :headers {} :body ""}
              (= method "PUT") (do (swap! blobs assoc k body) {:status 200 :headers {} :body ""})
              (= method "HEAD") {:status (if (contains? @blobs k) 200 404) :headers {} :body ""}
              :else (if-let [b (get @blobs k)] {:status 200 :headers {} :body b} {:status 404 :headers {} :body ""}))))))
(def store (mem-store "store-secret"))
(def gdom (ea/persistent-atom-domain (str base "/gw")))
(def gdisk (ea/lookup-or-create-mmap-atom! gdom "app/net" {:n 0}))
(def g (gw/gateway gdom {:endpoint "http://gw.test" :domain-id "d1" :api-key "k"
                         :presign (gw/hmac-presigner {:url "http://store.test/blobs/" :secret "store-secret"
                                                      :now-ms proc/now-ms})
                         :platform {:request store}}))
(def platform {:request (fn [req] (if (str/starts-with? (:url req) "http://gw.test") ((:handle g) req) (store req)))
               :sleep proc/sleep-ms! :now-ms proc/now-ms :seed 1})
(def nmeta {:eve/kind :net :eve/endpoint "http://gw.test" :eve/domain-id "d1" :eve/name "app/net"
            :eve/api-key "k" :eve/platform platform})
(def n (al/make-atom {:n :ignored} {:meta nmeta}))
(check "net kind" :net (al/kind n))
(check "net joins the gateway's root" {:n 0} (al/deref-atom n))
(check "net swap" {:n 1} (al/swap-atom! n update :n inc))
(check "the gateway's disk atom sees the net swap" {:n 1} (into {} @gdisk))
(swap! gdisk update :n + 10)
(check "the net atom sees a disk writer's swap" {:n 11} (al/deref-atom n))
(check "net cas hit" true (al/compare-and-set-atom! n {:n 11} {:n 12}))
(check "net cas miss" false (al/compare-and-set-atom! n {:n 11} {:n 13}))
(check "net unrepresentable publish" :unrepresentable (:error (err-of #(al/swap-atom! n (fn [_] {:f f0})))))
(check "net downgrade -> disk" :downgrade
       (:error (err-of #(al/set-meta-atom! n {:eve/kind :disk :eve/path dpath :eve/name "u7/x"}))))
;; disk -> net promotion: a disk atom of the gateway's domain moves up
(def gd2 (al/make-atom {:v 1} {:meta {:eve/kind :disk :eve/path (str base "/gw") :eve/name "app/up"}}))
(al/set-meta-atom! gd2 (assoc nmeta :eve/name "app/up"))
(check "disk -> net promoted kind" :net (al/kind gd2))
(check "disk -> net root kept" {:v 1} (al/deref-atom gd2))
(check "disk -> net swap lands in the gateway's disk atom" {:v 2}
       (do (al/swap-atom! gd2 update :v inc)
           (into {} @(ea/lookup-or-create-mmap-atom! gdom "app/up" nil))))

;; -- digests: one root, two Eve digests --------------------------------------
(let [{:keys [seal sha256]} (hosting/root-digests d)]
  (println "DIGESTS u7/counter:" (pr-str {:value (al/deref-atom d) :seal seal :sha256 sha256}))
  (check-true "seal present" (integer? (:digest seal)))
  (check-true "sha256 = 8 words" (= 8 (count sha256))))

(println (str "PROBE-TIER: " @checks " checks, " (count @failures) " failures"))
(when (seq @failures) (println "FAILED:" @failures))
(shutdown-agents)
(System/exit (if (seq @failures) 1 0))

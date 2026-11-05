;; unit 3 gate probe, host-side JVM seat: run with
;; clojure -Sdeps '{:paths ["src" "/home/hatch/src/eve/src"]}' -M probe_memory.clj
;;
;; Exercises the mixed-representation contract through the shim's own
;; dispatch vars (cohost.shim/Util-equiv, Util-hasheq, -equiv, -hasheq,
;; -hashCode) -- the equiv/hasheq paths stock core actually calls.
(require 'cohost.hosts.jvm 'cohost.shim 'cohost.memory 'cohost.memory-eve)
(alias 'shim 'cohost.shim)
(alias 'mem 'cohost.memory)
(alias 'me 'cohost.memory-eve)

(def checks (atom []))
(defn check [nm v] (swap! checks conj [nm v]) (println (if v "PASS" "FAIL") nm))

(def ue shim/Util-equiv)
(def uh shim/Util-hasheq)

(defn both-orders
  "equiv symmetric through both shim equiv paths, hasheq equal
   through both shim hash paths and hashCode."
  [label e h]
  (check (str label ": H=E (Util-equiv)") (ue h e))
  (check (str label ": E=H (Util-equiv)") (ue e h))
  (check (str label ": H=E (-equiv)") (shim/-equiv h e))
  (check (str label ": E=H (-equiv)") (shim/-equiv e h))
  (check (str label ": hasheq equal (Util-hasheq)") (= (uh h) (uh e)))
  (check (str label ": hasheq equal (-hasheq)") (= (shim/-hasheq h) (shim/-hasheq e)))
  (check (str label ": hashCode equal") (= (shim/-hashCode h) (shim/-hashCode e))))

;; --- the five gate kinds, H vs E -------------------------------------------

(def evec (me/eve-vector [1 2 3]))
(both-orders "vector" evec [1 2 3])
(check "vector: E /= different H" (not (ue evec [1 2 4])))
(check "vector: E vs H list twin (sequential equiv)" (ue evec (list 1 2 3)))
(check "vector: E hasheq = H list hasheq (ordered seq hash)" (= (uh evec) (uh (list 1 2 3))))

(def emap (me/eve-map {:a 1 :b [1 2]}))
(both-orders "map" emap {:a 1 :b [1 2]})
(check "map: E /= different H" (not (ue emap {:a 1 :b [1 3]})))

(def eset (me/eve-set #{1 2 3}))
(both-orders "set" eset #{1 2 3})
(check "set: E /= different H" (not (ue eset #{1 2})))

(def elist (me/eve-list (list 1 2 3)))
(both-orders "list" elist (list 1 2 3))
(check "list: E vs H vector twin (sequential equiv)" (ue elist [1 2 3]))

;; atom-deref values: root snapshots of a real Eve heap atom
(def ea (me/eve-atom [1 2 3]))
(def snap1 (me/atom-root-snapshot ea))
(both-orders "atom root (vector)" snap1 [1 2 3])
(swap! ea conj 4)
(def snap2 (me/atom-root-snapshot ea))
(check "atom root: snapshot is a snapshot (old /= new root)" (not (ue snap1 [1 2 3 4])))
(both-orders "atom root after swap" snap2 [1 2 3 4])
(def ea2 (me/eve-atom 42))
(both-orders "atom root (scalar)" (me/atom-root-snapshot ea2) 42)

;; --- E vs E: two stand-ins, separately built equal roots --------------------

(def evec2 (me/eve-vector [1 2 3]))
(check "E=E: distinct stand-ins, equal roots (Util-equiv)" (ue evec evec2))
(check "E=E: hasheq equal" (= (uh evec) (uh evec2)))
(check "E=E: not identical? (D2 kept)" (not (identical? evec evec2)))
(def emap2 (me/eve-map {:a 1 :b [1 2]}))
(check "E=E: maps" (ue emap emap2))

;; --- nested: Eve values inside Eve values -----------------------------------

(def enested (me/eve-map {:nums [1 2 3] :m {:x 1}}))
(both-orders "nested map" enested {:nums [1 2 3] :m {:x 1}})
(def evecmap (me/eve-vector [{:a 1} [2 3]]))
(both-orders "vector of collections" evecmap [{:a 1} [2 3]])

;; --- empties -----------------------------------------------------------------

(both-orders "empty vector" (me/eve-vector []) [])
(both-orders "empty map" (me/eve-map {}) {})
(both-orders "empty set" (me/eve-set #{}) #{})
(both-orders "empty list" (me/eve-list (list)) (list))

;; --- lookup in collections keyed either way ----------------------------------
;; Key placement at the seam is the dispatch's own work: a bucket map
;; over Util-hasheq with Util-equiv scans -- the same two answers
;; stock's hash collections are built from, and the answers unit 4's
;; Eve-backed collections will place keys by. (On the JVM seat a host
;; PersistentHashMap's internals are the host's own and bypass the
;; shim; the contract is therefore exercised at the dispatch layer.)

(defn seam-assoc [m k v]
  (let [h (uh k)
        bucket (get m h [])
        hit (some (fn [[bk _]] (ue bk k)) bucket)]
    (assoc m h (if hit
                 (mapv (fn [[bk bv]] (if (ue bk k) [bk v] [bk bv])) bucket)
                 (conj bucket [k v])))))

(defn seam-get [m k]
  (let [h (uh k)]
    (some (fn [[bk bv]] (when (ue bk k) bv)) (get m h))))

(let [m (seam-assoc {} [1 2 3] :by-host-key)]
  (check "lookup: H key in, E key finds" (= :by-host-key (seam-get m evec))))
(let [m (seam-assoc {} evec :by-eve-key)]
  (check "lookup: E key in, H key finds" (= :by-eve-key (seam-get m [1 2 3])))
  (check "lookup: E key in, E key finds" (= :by-eve-key (seam-get m evec2))))
(let [m (seam-assoc {} {:a 1 :b [1 2]} :host-map-key)]
  (check "lookup: H map key in, E map key finds" (= :host-map-key (seam-get m emap2))))

;; --- non-Eve values: the old answers, unchanged -------------------------------

(check "plain values: Util-equiv is host =" (and (ue [1 2] [1 2]) (not (ue [1 2] [1 3]))))
(check "plain values: Util-hasheq is host hash" (= (uh {:a 1}) (hash {:a 1})))
(check "plain values: -equiv is host =" (shim/-equiv "x" "x"))
(check "stand-in vs nil" (and (not (ue evec nil)) (not (ue nil evec))))
(check "stand-in vs scalar" (not (ue evec 3)))

(println)
(let [fails (filter (complement second) @checks)]
  (println (str (count (filter second @checks)) "/" (count @checks) " PASS"))
  (when (seq fails) (println "FAILURES:" (map first fails)))
  (System/exit (if (seq fails) 1 0)))

;; Unit 4 layer probe, JVM seat: boot with the Eve collection
;; fill forced on (arg "off" forces it off instead), then drive
;; the booted core through boot/eval-str and check, host-side,
;; that the values crossing back are Eve-backed and behave.
;; Run: clojure -M:eve probe_collections.clj [off]
(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter 'cohost.memory 'cohost.memory-eve)
(alias 'boot 'cohost.boot)
(alias 'host 'cohost.adapter)
(alias 'memory 'cohost.memory)

(def off? (= "off" (first *command-line-args*)))
(def checks (atom []))
(defn check [nm v] (swap! checks conj [nm (boolean v)]) (println (if v "PASS" "FAIL") nm))
(defn ev [src] (boot/eval-str src))
(defn ev-err [src] (try (boot/eval-str src) ::no-throw
                        (catch Throwable e e)))

(defn run-checks! [report]
  (check "boot errors 0" (zero? (clojure.core/count (:errors report))))
  (check (if off? "report fill off" "report fill on")
         (= (if off? :off :on) (:eve-collections report)))

  (if off?
    (do (check "off: literal vector is host-backed" (not (memory/eve-backed? (ev "[1 2 3]"))))
        (check "off: literal map is host-backed" (not (memory/eve-backed? (ev "{:a 1}")))))
    (do
      ;; -- the creators answer Eve-backed values ------------------
      ;; (literals in eval'd source are host-reader constants on
      ;; every seat -- the reader is the host's; the fill's surface
      ;; is the creator/construction surface, per the unit brief)
      (check "literal vector is a host-reader constant" (not (memory/eve-backed? (ev "[1 2 3]"))))
      (check "vector fn Eve-backed" (memory/eve-backed? (ev "(vector 1 2 3)")))
      (check "vector kind" (= :vector (memory/eve-kind (ev "(vector 1 2 3)"))))
      (check "vec Eve-backed" (memory/eve-backed? (ev "(vec (list 1 2))")))
      (check "hash-map Eve-backed" (memory/eve-backed? (ev "(hash-map :a 1)")))
      (check "array-map Eve-backed" (memory/eve-backed? (ev "(array-map :a 1)")))
      (check "hash-set Eve-backed" (memory/eve-backed? (ev "(hash-set 1 2)")))
      (check "list fn Eve-backed" (memory/eve-backed? (ev "(list 1 2)")))
      (check "into Eve vector Eve-backed" (memory/eve-backed? (ev "(into (vector) (range 10))")))
      (check "into Eve map Eve-backed" (memory/eve-backed? (ev "(into (hash-map) [[:a 1]])")))
      (check "assoc on Eve map Eve-backed" (memory/eve-backed? (ev "(assoc (hash-map) :a 1)")))
      (check "conj on Eve vector Eve-backed" (memory/eve-backed? (ev "(conj (vector) 1)")))
      (check "EMPTY vector static Eve-backed"
             (memory/eve-backed? @(resolve 'cohost.shim/PersistentVector-EMPTY)))
      (check "EMPTY array-map static Eve-backed"
             (memory/eve-backed? @(resolve 'cohost.shim/PersistentArrayMap-EMPTY)))
      (check "nested Eve-backed outer"
             (memory/eve-backed? (ev "(hash-map :v (vector 1 2) :s (hash-set 3))")))

      ;; -- the seam contract ---------------------------------------
      (check "booted = host twin" (= (ev "(vector 1 2 3)") [1 2 3]))
      (check "host = booted twin" (= [1 2 3] (ev "(vector 1 2 3)")))
      (check "booted hash = host hash" (= (hash (ev "(vector 1 2 3)")) (hash [1 2 3])))
      (check "booted map hash = host" (= (hash (ev "(hash-map :a 1)")) (hash {:a 1})))
      (check "in-world cross equality" (ev "(= (vector 1 2) [1 2])"))
      (check "in-world get" (= 2 (ev "(get (vector 1 2 3) 1)")))
      (check "in-world find" (= [:a 1] (ev "(find (hash-map :a 1) :a)")))
      (check "in-world contains" (ev "(contains? (hash-set 1 2) 2)"))
      (check "pr-str of Eve vector" (= "[1 2 3]" (pr-str (ev "(vector 1 2 3)"))))
      (check "pr-str of Eve map" (= "{:a 1}" (pr-str (ev "(hash-map :a 1)"))))

      ;; -- ops through the seam ------------------------------------
      (check "rseq" (= '(3 2 1) (ev "(rseq (vector 1 2 3))")))
      (check "subvec" (= [2 3] (ev "(subvec (vector 1 2 3) 1)")))
      (check "subvec stays Eve-backed" (memory/eve-backed? (ev "(subvec (vector 1 2 3) 1)")))
      (check "compare vectors" (= -1 (ev "(compare (vector 1 2) (vector 1 3))")))
      (check "sort by compare" (= [[1] [2]] (ev "(sort [(vector 2) (vector 1)])")))
      (check "sort numbers" (= [1 2 3] (ev "(sort (vector 3 1 2))")))
      (check "pop" (= [1 2] (ev "(pop (vector 1 2 3))")))
      (check "peek" (= 3 (ev "(peek (vector 1 2 3))")))
      (check "dissoc" (= {:b 2} (ev "(dissoc (hash-map :a 1 :b 2) :a)")))
      (check "disj" (= #{2} (ev "(disj (hash-set 1 2) 1)")))
      (check "empty of Eve map" (= {} (ev "(empty (hash-map :a 1))")))
      (check "empty stays Eve-backed" (memory/eve-backed? (ev "(empty (hash-map :a 1))")))
      (check "reduce over Eve vector" (= 6 (ev "(reduce + (vector 1 2 3))")))
      (check "map over Eve vector" (= [2 3 4] (ev "(map inc (vector 1 2 3))")))
      (check "count Eve map" (= 2 (ev "(count (hash-map :a 1 :b 2))")))
      (check "nth not-found" (= :nf (ev "(nth (vector 1 2) 9 :nf)")))
      (check "vector called as fn" (= 2 (ev "((vector 1 2 3) 1)")))
      (check "map called as fn" (= 1 (ev "((hash-map :a 1) :a)")))
      (check "set called as fn" (= 2 (ev "((hash-set 1 2) 2)")))

      ;; -- meta -----------------------------------------------------
      (check "meta round trip" (= {:a 1} (ev "(meta (with-meta (vector 1 2) {:a 1}))")))
      (check "meta survives assoc" (= {:a 1} (ev "(meta (assoc (with-meta (vector 1 2) {:a 1}) 0 9))")))
      (check "meta value still equal" (ev "(= (with-meta (vector 1 2) {:a 1}) [1 2])"))
      (check "meta value hash" (= (hash (ev "(with-meta (vector 1 2) {:a 1})")) (hash [1 2])))
      (check "meta on map" (= {:m 2} (ev "(meta (with-meta (hash-map :a 1) {:m 2}))")))
      (check "meta on set" (= {:m 3} (ev "(meta (with-meta (hash-set 1) {:m 3}))")))
      (check "meta on list" (= {:m 4} (ev "(meta (with-meta (list 1) {:m 4}))")))
      (check "with-meta nil unwraps" (memory/eve-backed? (ev "(with-meta (with-meta (vector 1) {:a 1}) nil)")))

      ;; -- guarded transients ---------------------------------------
      (check "transient conj!" (= [1 2 3] (ev "(persistent! (conj! (transient (vector 1 2)) 3))")))
      (check "transient result Eve-backed"
             (memory/eve-backed? (ev "(persistent! (conj! (transient (vector 1 2)) 3))")))
      (check "transient assoc!" (= {:a 1 :b 2} (ev "(persistent! (assoc! (transient (hash-map :a 1)) :b 2))")))
      (check "transient dissoc!" (= {:b 2} (ev "(persistent! (dissoc! (transient (hash-map :a 1 :b 2)) :a))")))
      (check "transient disj!" (= #{2} (ev "(persistent! (disj! (transient (hash-set 1 2)) 1))")))
      (check "transient pop!" (= [1] (ev "(persistent! (pop! (transient (vector 1 2))))")))
      (check "transient count" (= 2 (ev "(count (transient (vector 1 2)))")))
      (check "transient get" (= 1 (ev "(get (transient (hash-map :a 1)) :a)")))
      (check "into big uses transients, Eve-backed"
             (and (memory/eve-backed? (ev "(into (vector) (range 200))"))
                  (= 200 (ev "(count (into (vector) (range 200)))"))))
      (check "use after persistent! is the sealed error"
             (= :cohost.memory/transient-sealed
                (:type (ex-data (ev-err "(let [t (transient (vector 1))] (persistent! t) (conj! t 2))")))))
      (check "seq of transient is ClassCastException"
             (= :cce (ev "(try (seq (transient (vector 1))) :no (catch ClassCastException e :cce))")))
      (check "conj on transient is ClassCastException"
             (= :cce (ev "(try (conj (transient (vector 1)) 2) :no (catch ClassCastException e :cce))")))
      (check "assoc on transient is ClassCastException"
             (= :cce (ev "(try (assoc (transient (hash-map :a 1)) :b 2) :no (catch ClassCastException e :cce))")))
      (check "nth of transient" (= 2 (ev "(nth (transient (vector 1 2)) 1)")))

      ;; -- the codec universe boundary -------------------------------
      (check "char element is the named unrepresentable error"
             (= :cohost.memory/unrepresentable
                (:type (ex-data (ev-err "(vector \\a)")))))
      (check "fn key is the named unrepresentable error"
             (= :cohost.memory/unrepresentable
                (:type (ex-data (ev-err "(hash-map (fn [x] x) 1)")))))
      (check "ratio element is the named unrepresentable error"
             (= :cohost.memory/unrepresentable
                (:type (ex-data (ev-err "(vector 1/2)")))))
      (check "unrepresentable lookup key answers not-found"
             (= :nf (ev "(get (hash-map :a 1) \\a :nf)")))
      (check "unrepresentable contains? answers false"
             (= false (ev "(contains? (hash-map :a 1) \\a)")))
      (check "non-integer vector key answers not-found"
             (= :nf (ev "(get (vector 1 2) \\a :nf)")))))

  (let [fails (clojure.core/filter (fn [[_ v]] (not v)) @checks)]
    (println (str "PROBE " (clojure.core/count @checks) " checks, " (clojure.core/count fails) " FAIL"))
    (System/exit (if (empty? fails) 0 1)))
)

(def report (boot/boot! {:eve-collections (not off?)}))
(boot/make-user-ns!)
(run-checks! report)

;; Unit 4 layer probe, JVM seat: boot with the Eve collection
;; fill forced on (arg "off" forces it off instead), then drive
;; the booted core through boot/eval-str and check, host-side,
;; that the values crossing back are Eve-backed and behave.
;; Run: clojure -Sdeps '{:paths ["src" "resources" "/home/hatch/src/eve/src"]}' -M probe_collections.clj [off]
;; (launch shape as the gates: no -M:eve, see UNIT4-STATUS's
;; eduction diagnostic.)
;;
;; Construction note: the fill's surface is the shim's creator/
;; construction surface. Small-arity vector (<= 6 args) and
;; 0-arity hash-map are literal-bodied in core.clj's own text --
;; analyzer territory, host-answered on every seat -- so Eve
;; values here are built through the variadic creators:
;; (vector 1 2 3 4 5 6 7) is the probe's Eve vector.
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

(def V7 "(vector 1 2 3 4 5 6 7)")

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
      (check "vector fn Eve-backed" (memory/eve-backed? (ev V7)))
      (check "vector kind" (= :vector (memory/eve-kind (ev V7))))
      (check "LazilyPersistentVector/create Eve-backed"
             (memory/eve-backed? (ev "(clojure.lang.LazilyPersistentVector/create (list 1 2))")))
      (check "hash-map Eve-backed" (memory/eve-backed? (ev "(hash-map :a 1)")))
      (check "array-map Eve-backed" (memory/eve-backed? (ev "(array-map :a 1)")))
      (check "hash-set Eve-backed" (memory/eve-backed? (ev "(hash-set 1 2)")))
      (check "list fn Eve-backed" (memory/eve-backed? (ev "(list 1 2)")))
      (check "into Eve vector Eve-backed" (memory/eve-backed? (ev (str "(into " V7 " (range 10))"))))
      (check "into Eve map Eve-backed" (memory/eve-backed? (ev "(into (hash-map :x 0) [[:a 1]])")))
      (check "assoc on Eve map Eve-backed" (memory/eve-backed? (ev "(assoc (hash-map :x 0) :a 1)")))
      (check "conj on Eve vector Eve-backed" (memory/eve-backed? (ev (str "(conj " V7 " 8)"))))
      (check "EMPTY vector static Eve-backed"
             (memory/eve-backed? @(resolve 'cohost.shim/PersistentVector-EMPTY)))
      (check "EMPTY array-map static Eve-backed"
             (memory/eve-backed? @(resolve 'cohost.shim/PersistentArrayMap-EMPTY)))
      (check "nested Eve-backed outer"
             (memory/eve-backed? (ev "(hash-map :v (vector 1 2) :s (hash-set 3))")))

      ;; -- the seam contract ---------------------------------------
      (check "booted = host twin" (= (ev V7) [1 2 3 4 5 6 7]))
      (check "host = booted twin" (= [1 2 3 4 5 6 7] (ev V7)))
      (check "booted hash = host hash" (= (hash (ev V7)) (hash [1 2 3 4 5 6 7])))
      (check "booted map hash = host" (= (hash (ev "(hash-map :a 1)")) (hash {:a 1})))
      (check "in-world cross equality" (ev (str "(= " V7 " [1 2 3 4 5 6 7])")))
      (check "in-world get" (= 2 (ev (str "(get " V7 " 1)"))))
      (check "in-world find" (= [:a 1] (ev "(find (hash-map :a 1) :a)")))
      (check "in-world contains" (ev "(contains? (hash-set 1 2) 2)"))
      (check "pr-str of Eve vector" (= "[1 2 3 4 5 6 7]" (pr-str (ev V7))))
      (check "pr-str of Eve map" (= "{:a 1}" (pr-str (ev "(hash-map :a 1)"))))

      ;; -- ops through the seam ------------------------------------
      ;; rseq through the seam's -rseq var: core's rseq on this
      ;; seat is a real interop call and lands on Eve's
      ;; unimplemented Reversible method (an Eve-side gap, named
      ;; in UNIT4-STATUS); the seam var is the fill's surface
      (check "rseq" (= '(7 6 5 4 3 2 1) (ev (str "(cohost.shim/-rseq " V7 ")"))))
      (check "subvec" (= [2 3] (ev "(subvec (vector 0 1 2 3 4 5 6) 2 4)")))
      ;; subvec is the host adapter's RT funnel, not a creator:
      ;; on this seat it answers the host-strata vector (correct
      ;; value, host backing) -- a named strata remainder, so the
      ;; probe records the fact instead of claiming the fill
      (check "subvec result is host-strata (named)"
             (not (memory/eve-backed? (ev "(subvec (vector 0 1 2 3 4 5 6) 2 4)"))))
      ;; compare through the seam's Util-compare var: core's
      ;; compare carries an :inline, so the host analyzer inlines
      ;; program-level (compare ...) to a real Util.compare call
      ;; (a checkcast to Comparable, which Eve's bare types do
      ;; not implement) -- the JVM-analyzer surface again; the
      ;; shim var is the seam's compare surface and answers
      (check "compare vectors" (= -1 (ev "(cohost.shim/Util-compare (vector 1 2 9 9 9 9 9) (vector 1 3 9 9 9 9 9))")))
      (check "sort by seam compare" (= [[1 0 0 0 0 0 0] [2 0 0 0 0 0 0]]
                                       (ev "(sort cohost.shim/Util-compare [(vector 2 0 0 0 0 0 0) (vector 1 0 0 0 0 0 0)])")))
      (check "sort numbers" (= [1 2 3 9 9 9 9] (ev "(sort (vector 3 1 2 9 9 9 9))")))
      (check "pop" (= [1 2 3 4 5 6] (ev (str "(pop " V7 ")"))))
      (check "peek" (= 7 (ev (str "(peek " V7 ")"))))
      (check "dissoc" (= {:b 2} (ev "(dissoc (hash-map :a 1 :b 2) :a)")))
      (check "disj" (= #{2} (ev "(disj (hash-set 1 2) 1)")))
      (check "empty of Eve map" (= {} (ev "(empty (hash-map :a 1))")))
      (check "empty stays Eve-backed" (memory/eve-backed? (ev "(empty (hash-map :a 1))")))
      (check "reduce over Eve vector" (= 28 (ev (str "(reduce + " V7 ")"))))
      (check "map over Eve vector" (= [2 3 4 5 6 7 8] (ev (str "(map inc " V7 ")"))))
      (check "count Eve map" (= 2 (ev "(count (hash-map :a 1 :b 2))")))
      (check "nth not-found" (= :nf (ev (str "(nth " V7 " 9 :nf)"))))
      (check "vector called as fn" (= 2 (ev (str "(" V7 " 1)"))))
      (check "map called as fn" (= 1 (ev "((hash-map :a 1) :a)")))
      (check "set called as fn" (= 2 (ev "((hash-set 1 2) 2)")))

      ;; -- meta, through the seam's -withMeta -----------------------
      ;; (core's with-meta on this seat is a real interop call and
      ;; lands on Eve's stub method -- the named JVM-seat gap in
      ;; UNIT4-STATUS; the seam's own surface is what is checked
      ;; here)
      (check "meta round trip" (= {:a 1} (ev (str "(meta (cohost.shim/-withMeta " V7 " {:a 1}))"))))
      (check "meta survives assoc" (= {:a 1} (ev (str "(meta (assoc (cohost.shim/-withMeta " V7 " {:a 1}) 0 9))"))))
      (check "meta value still equal" (ev (str "(= (cohost.shim/-withMeta " V7 " {:a 1}) [1 2 3 4 5 6 7])")))
      (check "meta value hash" (= (hash (ev (str "(cohost.shim/-withMeta " V7 " {:a 1})"))) (hash [1 2 3 4 5 6 7])))
      (check "meta on map" (= {:m 2} (ev "(meta (cohost.shim/-withMeta (hash-map :a 1) {:m 2}))")))
      (check "meta on set" (= {:m 3} (ev "(meta (cohost.shim/-withMeta (hash-set 1) {:m 3}))")))
      (check "meta on list" (= {:m 4} (ev "(meta (cohost.shim/-withMeta (list 1) {:m 4}))")))
      (check "with-meta nil unwraps"
             (memory/eve-backed? (ev (str "(cohost.shim/-withMeta (cohost.shim/-withMeta " V7 " {:a 1}) nil)"))))

      ;; -- guarded transients ---------------------------------------
      (check "transient conj!" (= [1 2 3 4 5 6 7 8] (ev (str "(persistent! (conj! (transient " V7 ") 8))"))))
      (check "transient result Eve-backed"
             (memory/eve-backed? (ev (str "(persistent! (conj! (transient " V7 ") 8))"))))
      (check "transient assoc!" (= {:a 1 :b 2} (ev "(persistent! (assoc! (transient (hash-map :a 1)) :b 2))")))
      (check "transient dissoc!" (= {:b 2} (ev "(persistent! (dissoc! (transient (hash-map :a 1 :b 2)) :a))")))
      (check "transient disj!" (= #{2} (ev "(persistent! (disj! (transient (hash-set 1 2)) 1))")))
      (check "transient pop!" (= [1 2 3 4 5 6] (ev (str "(persistent! (pop! (transient " V7 ")))"))))
      ;; count/nth on a VECTOR layer transient go through the
      ;; seam funnels: core's count/nth carry :inlines, so the
      ;; host analyzer inlines program-level calls to real
      ;; RT.count/RT.nth, which meet the transient's stand-in
      ;; map raw (the JVM-analyzer distance, named in
      ;; UNIT4-STATUS). Map/set transients are Eve-native and
      ;; take core's calls directly (the checks around this one)
      (check "transient count" (= 7 (ev (str "(cohost.shim/RT-count (transient " V7 "))"))))
      (check "transient get" (= 1 (ev "(get (transient (hash-map :a 1)) :a)")))
      (check "into big stays Eve-backed"
             (and (memory/eve-backed? (ev (str "(into " V7 " (range 200))")))
                  (= 207 (ev (str "(count (into " V7 " (range 200)))")))))
      (check "use after persistent! is the sealed error"
             (= :cohost.memory/transient-sealed
                (:type (ex-data (ev-err (str "(let [t (transient " V7 ")] (persistent! t) (conj! t 2))"))))))
      (check "seq of transient is ClassCastException"
             (= :cce (ev (str "(try (seq (transient " V7 ")) :no (catch ClassCastException e :cce))"))))
      (check "conj on transient is ClassCastException"
             (= :cce (ev (str "(try (conj (transient " V7 ") 2) :no (catch ClassCastException e :cce))"))))
      (check "assoc on transient is ClassCastException"
             (= :cce (ev "(try (assoc (transient (hash-map :a 1)) :b 2) :no (catch ClassCastException e :cce))")))
      (check "nth of transient" (= 2 (ev (str "(cohost.shim/RT-nth (transient " V7 ") 1)"))))

      ;; -- domain residents (Nov 5 ~11:02 ET ruling) ----------------
      ;; read through the seam's funnels (RT-get/RT-nth/RT-contains):
      ;; core's get/nth/contains? carry :inlines, so the host
      ;; analyzer inlines program-level calls to real RT.*, which
      ;; hand Eve the RAW key (Eve then serializes a resident key
      ;; and throws, or returns the raw reference for an element)
      ;; -- the JVM-analyzer distance again, named in
      ;; UNIT4-STATUS. The seam's funnels probe-convert and
      ;; resolve, and are the surface checked here
      (check "char element round-trips" (= \a (ev "(cohost.shim/RT-nth (vector \\a \\b \\c \\d \\e \\f \\g) 0)")))
      (check "fn value round-trips and is callable"
             (= 42 (ev "(let [f (fn [x] (+ x 1))] ((cohost.shim/RT-get (hash-map :f f) :f) 41))")))
      (check "var key round-trips"
             (= 5 (ev "(do (def u4-probe-x 1) (cohost.shim/RT-get (hash-map (var u4-probe-x) 5) (var u4-probe-x)))")))
      (check "ratio element round-trips" (= 1/2 (ev "(cohost.shim/RT-nth (vector 1/2 2 3 4 5 6 7) 0)")))
      (check "char key answers by value" (= 1 (ev "(cohost.shim/RT-get (hash-map \\a 1) \\a)")))
      (check "never-stored fn key answers not-found"
             (= :nf (ev "(cohost.shim/RT-get (hash-map :a 1) (fn [x] x) :nf)")))
      (check "non-resident key contains? answers false"
             (= false (ev "(cohost.shim/RT-contains (hash-map :a 1) (fn [x] x))")))
      (check "non-integer vector key answers not-found"
             (= :nf (ev (str "(cohost.shim/RT-get " V7 " \\a :nf)"))))))

  (let [fails (clojure.core/filter (fn [[_ v]] (not v)) @checks)]
    (println (str "PROBE " (clojure.core/count @checks) " checks, " (clojure.core/count fails) " FAIL"))
    (System/exit (if (empty? fails) 0 1)))
)

(def report (boot/boot! {:eve-collections (not off?)}))
(boot/make-user-ns!)
(run-checks! report)

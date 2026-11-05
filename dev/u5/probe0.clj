;; feasibility probe: Eve heap atoms on the JVM seat
(require '[eve.atom :as ea] '[eve.vec :as evec] '[eve.deftype-proto.alloc :as alloc])
(defn timed [label f] (let [t0 (System/nanoTime) r (f) dt (/ (- (System/nanoTime) t0) 1e6)] (println label "=" (pr-str r) (format "(%.1f ms)" dt)) r))
;; domain open cost
(def d1 (timed "atom-domain open #1" #(ea/atom-domain {})))
(def d2 (timed "atom-domain open #2" #(ea/atom-domain {})))
;; anon atom in default domain
(def a1 (timed "anon atom create" #(ea/atom 0)))
(timed "deref" #(deref a1))
(timed "swap! inc x1" #(swap! a1 inc))
(time (dotimes [_ 100] (swap! a1 inc)))
(println "after 101 incs:" @a1)
;; fn in atom
(def af (ea/atom nil))
(println "store fn:" (try (reset! af (fn [x] (inc x))) "OK" (catch Throwable e (str "THROW " (ex-message e)))))
(println "deref fn:" (try (let [f @af] (f 41)) (catch Throwable e (str "THROW " (ex-message e)))))
;; host object in atom
(def ao (ea/atom nil))
(println "store StringBuilder:" (try (reset! ao (StringBuilder. "hi")) "OK" (catch Throwable e (str "THROW " (ex-message e)))))
(println "deref sb:" (try (str @ao) (catch Throwable e (str "THROW " (ex-message e)))))
;; map with fn inside (multimethod shape)
(def am (ea/atom {}))
(println "store {:methods {1 fn}}:" (try (swap! am assoc-in [:methods 1] (fn [x] :f)) "OK" (catch Throwable e (str "THROW " (ex-message e)))))
;; atom in atom
(def aa (ea/atom nil))
(println "store atom in atom:" (try (reset! aa a1) "OK" (catch Throwable e (str "THROW " (ex-message e)))))
;; keyword/string/coll roots
(def ac (ea/atom [1 2 {:a "x"}]))
(println "coll root:" @ac (type @ac))
(println "swap coll:" (swap! ac conj 4))
;; named atoms in an explicit domain via lookup-or-create
(def named1 (ea/lookup-or-create-mmap-atom! d1 "cohost.test/one" 10))
(println "named in d1:" @named1)
(println "same name again identical?" (identical? named1 (ea/lookup-or-create-mmap-atom! d1 "cohost.test/one" 99)) "val still" @(ea/lookup-or-create-mmap-atom! d1 "cohost.test/one" 99))
;; compareAndSet on :clj?
(println "compareAndSet method?" (try (clojure.lang.Reflector/invokeInstanceMethod a1 "compareAndSet" (object-array [101 200])) (catch Throwable e (str "THROW " (.getSimpleName (class e)) " " (ex-message e)))))
(println "a1 now:" @a1)

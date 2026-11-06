(require 'cohost.memory 'cohost.memory-eve)
(def v (cohost.memory/make-vector [1 2 3]))
(def w (cohost.memory/with-meta* v {:m 1}))
(println :wrapped (pr-str w) :meta (meta w))
(println :assoc (pr-str (assoc w 0 9)) :meta-after (meta (assoc w 0 9)))
(println :eq (= w [1 2 3]) :hash (= (hash w) (hash [1 2 3])))
(println :rseq-w (rseq w) :cmp (cohost.memory/compare-values v [1 2 4]) :cmp-w (compare w [1 2 3]))
(println :tr (cohost.memory/t-persistent! (cohost.memory/t-conj (cohost.memory/as-transient v) 4)))
(println :tr-meta (meta (cohost.memory/t-persistent! (cohost.memory/as-transient w))))
(println :invoke (w 0) (w 9 :nf))
(println :list-rest (pr-str (rest (cohost.memory/make-list (list 1 2 3)))))
(println :set-invoke ((cohost.memory/make-set #{1 2}) 2))
(println :map-invoke ((cohost.memory/make-map [:a 1]) :a))
(println :unrep (try (cohost.memory/make-vector [1 (char 65)])
                     (catch clojure.lang.ExceptionInfo e (ex-data e))))
(println :lookup-unrep (cohost.memory/lookup (cohost.memory/make-map [:a 1]) (char 65) :nf))
(println :compare-unrep (try (cohost.memory/compare-values v [(char 65)])
                          (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
(println :scope-closed
         (let [t (atom nil)]
           (cohost.memory/with-transient-scope
             (fn [] (reset! t (cohost.memory/as-transient v))))
           (try (cohost.memory/t-conj @t 9) :NO-THROW
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
(println :sealed (let [t (cohost.memory/as-transient v)]
                   (cohost.memory/t-persistent! t)
                   (try (cohost.memory/t-conj t 9) :NO-THROW
                        (catch clojure.lang.ExceptionInfo e (.getMessage e)))))
(println :done)

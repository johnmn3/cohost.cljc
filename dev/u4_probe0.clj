;; u4 probe 0: bare Eve values inside the booted core, no shim changes.
;; run: clojure -Sdeps '{:paths ["src" "/home/hatch/src/eve/src"]}' -M dev/u4_probe0.clj
(require 'cohost.hosts.jvm 'cohost.boot 'cohost.memory 'cohost.memory-eve 'cohost.adapter)
(alias 'boot 'cohost.boot) (alias 'mem 'cohost.memory) (alias 'me 'cohost.memory-eve) (alias 'host 'cohost.adapter)

(def report (boot/boot! {:root "resources/cohost/clojure-1.12.0"}))
(println "boot errors:" (:errors report))
(boot/make-user-ns!)

(intern 'user 'EV (mem/root (me/eve-vector [1 2 3])))
(intern 'user 'EM (mem/root (me/eve-map {:a 1 :b 2})))
(intern 'user 'ES (mem/root (me/eve-set #{1 2 3})))
(intern 'user 'EL (mem/root (me/eve-list (list 1 2 3))))

(defn t [label src]
  (println (str label " => " (host/try-all (fn [] (pr-str (boot/eval-str src))) (fn [e] (str "ERR " (host/error-message e)))))))

(doseq [[label src]
        [["vec conj" "(conj EV 4)"]
         ["vec nth" "(nth EV 1)"]
         ["vec count" "(count EV)"]
         ["vec seq pr" "(pr-str (seq EV))"]
         ["vec =" "(= EV [1 2 3])"]
         ["vec hash eq" "(= (hash EV) (hash [1 2 3]))"]
         ["vec assoc" "(assoc EV 0 9)"]
         ["vec peek/pop" "[(peek EV) (pop EV)]"]
         ["vec rseq" "(rseq EV)"]
         ["vec compare" "(compare EV [1 2 4])"]
         ["vec subvec" "(subvec EV 1)"]
         ["vec transient" "(persistent! (conj! (transient EV) 4))"]
         ["vec into" "(into EV [4 5])"]
         ["vec vector?" "(vector? EV)"]
         ["vec class" "(class EV)"]
         ["vec meta" "(meta (with-meta EV {:a 1}))"]
         ["vec pr-str" "(pr-str EV)"]
         ["vec IFn" "(EV 1)"]
         ["vec reduce" "(reduce + EV)"]
         ["vec empty" "(empty EV)"]
         ["vec get" "(get EV 1)"]
         ["map assoc" "(assoc EM :c 3)"]
         ["map get" "(get EM :a)"]
         ["map dissoc" "(dissoc EM :a)"]
         ["map =" "(= EM {:a 1 :b 2})"]
         ["map hash eq" "(= (hash EM) (hash {:a 1 :b 2}))"]
         ["map count" "(count EM)"]
         ["map keys pr" "(pr-str (keys EM))"]
         ["map seq pr" "(pr-str (seq EM))"]
         ["map first entry" "[(key (first EM)) (val (first EM))]"]
         ["map class" "(class EM)"]
         ["map transient" "(persistent! (assoc! (transient EM) :c 3))"]
         ["map into" "(into EM [[:c 3]])"]
         ["map IFn" "(EM :a)"]
         ["map meta" "(meta (with-meta EM {:a 1}))"]
         ["map empty" "(empty EM)"]
         ["map find" "(find EM :a)"]
         ["map merge" "(merge EM {:c 3})"]
         ["set conj" "(conj ES 4)"]
         ["set disj" "(disj ES 1)"]
         ["set contains" "(contains? ES 2)"]
         ["set =" "(= ES #{1 2 3})"]
         ["set hash eq" "(= (hash ES) (hash #{1 2 3}))"]
         ["set class" "(class ES)"]
         ["set transient" "(persistent! (conj! (transient ES) 4))"]
         ["set IFn" "(ES 2)"]
         ["set empty" "(empty ES)"]
         ["list first/rest" "[(first EL) (rest EL)]"]
         ["list conj" "(conj EL 0)"]
         ["list =" "(= EL (list 1 2 3))"]
         ["list = vec" "(= EL [1 2 3])"]
         ["list hash eq" "(= (hash EL) (hash (list 1 2 3)))"]
         ["list count" "(count EL)"]
         ["list class" "(class EL)"]
         ["list peek/pop" "[(peek EL) (pop EL)]"]
         ["list pr-str" "(pr-str EL)"]
         ["cross: eve vec in host map lookup" "(get {[1 2 3] :x} EV)"]
         ["cross: host key in eve map" "(get (assoc EM [1 2 3] :v) [1 2 3])"]
         ["cross: eve key in eve map" "(get (assoc EM EV :v) EV)"]
         ["sorted?" "(sorted? EM)"]]]
  (t label src))
(println "done")
(System/exit 0)

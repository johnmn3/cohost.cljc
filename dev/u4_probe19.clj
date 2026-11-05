(require 'cohost.memory 'cohost.memory-eve)
(alias 'memory 'cohost.memory)
(def em (memory/make-map [:a 1]))
(def es (memory/make-set #{1 2}))
(println "before print-dup, fn with Eve map body:"
         (try ((eval (list 'fn [] em))) (catch Throwable e (str "ERR " (.getMessage e)))))
(defmethod print-dup eve.map.EveHashMap [x w] (.write w (pr-str (cohost.memory-eve/canonicalize x))))
(defmethod print-dup eve.set.EveHashSet [x w] (.write w (pr-str (cohost.memory-eve/canonicalize x))))
(println "after print-dup, fn with Eve map body:"
         (try (let [v ((eval (list 'fn [] em)))] (str (pr-str v) " | " (type v)))
              (catch Throwable e (str "ERR " (.getMessage e)))))
(println "after print-dup, fn with Eve set body:"
         (try (let [v ((eval (list 'fn [] es)))] (str (pr-str v) " | " (type v)))
              (catch Throwable e (str "ERR " (.getMessage e)))))
(println :done)

;; corpus driver: boot with :eve-collections per argv[0]
;; ("on"/"off"), run the corpus stock side, write rows EDN to
;; argv[1]. Compare two runs with dev/u4_corpus_diff.clj.
(require 'cohost.hosts.jvm 'cohost.boot 'cohost.adapter 'cohost.corpus)
(alias 'boot 'cohost.boot)
(def on? (= "on" (first *command-line-args*)))
(def out (second *command-line-args*))
(def report (boot/boot! {:eve-collections on?}))
(println "BOOT errors:" (clojure.core/count (:errors report)) "fill:" (:eve-collections report))
(boot/make-user-ns!)
(cohost.corpus/run-corpus!
 {:path "/home/hatch/src/atom.broker/vendor/jolt-conformance/corpus.edn"
  :side :stock
  :out out})
(shutdown-agents)

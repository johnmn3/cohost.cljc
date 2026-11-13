;; Integration fixture for the in-process :clj leg. Not part of bb clj-body:test.
;;
;; The :clj arm prints CLJ-ARM and a Java VM name. Champaca evaluates that
;; arm inside the jolt process cohost :jolt started. The :jolt arm is the
;; other branch of the same conditional; with :clj ranked first it is not
;; the branch the host runs.
#?(:clj (println "CLJ-ARM" (System/getProperty "java.vm.name"))
   :jolt (println "JOLT-ARM"))

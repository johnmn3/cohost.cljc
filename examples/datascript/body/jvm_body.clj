;; DataScript-on-cohost example, the JVM body (the caller).
;; Follows the Phase-0 two-body gate pattern: installs the file-domain
;; hosting over MB_DIR, mints the mailbox atoms in the same order as
;; the peer body, writes the lib harness's contract dataset into the
;; shared Eve domain, then asks the peer body — a different runtime —
;; to answer two DataScript questions against the database IT opened
;; on the same domain, through cohost.bodies/call. Prints every value;
;; run-gate.sh asserts.
(require '[cohost.atom-eve-file :as hosting]
         '[cohost.bodies :as bodies]
         '[eve.atom :as eve-atom]
         '[ds-harness.contract-check])

(def dir (System/getenv "MB_DIR"))
(hosting/install! {:dir dir})

;; the write phase: the datascript.cljc repo's own contract driver,
;; in-process (its atom lives in the shared domain at <dir>/dsdb)
(ds-harness.contract-check/-main "write" (str dir "/dsdb"))

(def domain (eve-atom/persistent-atom-domain (str dir "/dsdb")))
(def a (eve-atom/lookup-or-create-mmap-atom! domain "ds-harness/contract-db" nil))
(println "JVM-BODY SEAL=" (pr-str (eve-atom/seal a)))

(def mb (bodies/mailbox-atoms dir [:jvm :peer]))
(def sleep (fn [ms] (Thread/sleep ms)))
(def r1 (bodies/call mb :jvm :peer
                     "(count (datascript.core/datoms @db* :eavt))"
                     {:ns 'ds-example.lpy-body :sleep sleep :timeout-ms 300000}))
(println "JVM-BODY CALL1-DATOMS=" (pr-str r1))
(def r2 (bodies/call mb :jvm :peer
                     "(vec (sort (datascript.core/q (quote [:find [?n ...] :where [?e :name ?n]]) @db*)))"
                     {:ns 'ds-example.lpy-body :sleep sleep :timeout-ms 300000}))
(println "JVM-BODY CALL2-NAMES=" (pr-str r2))
(hosting/close-hosting!)
(println "JVM-BODY DONE")

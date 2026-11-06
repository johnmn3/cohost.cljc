;; Bisect 5: agent-var-handle path in the tx-loaded context
(require 'cohost.hosts.auto)
(require '[cohost.hosts.glj :as glj-host])
(glj-host/install!)
(require '[cohost.tx :as tx]
         '[cohost.thread :as thread]
         '[cohost.adapter :as host])
(println "cohost.core loaded?" (some? (find-ns 'cohost.core)))
(println "interned-var =>"
         (try (pr-str (host/interned-var 'cohost.core '*agent*))
              (catch Throwable e (str "THREW: " (ex-message e)))))
(println "resolve-core *agent* =>"
         (try (pr-str (host/resolve-core '*agent*))
              (catch Throwable e (str "THREW: " (ex-message e)))))
(println "DONE")
(System/exit 0)

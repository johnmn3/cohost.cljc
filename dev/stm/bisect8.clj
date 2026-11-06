;; Bisect 8: does clojure.core/*agent* exist on glojure + binding it works?
(require 'cohost.hosts.auto)
(require '[cohost.hosts.glj :as glj-host])
(glj-host/install!)
(require '[cohost.tx :as tx])
(defn try* [label f]
  (try (println label "=>" (pr-str (f)))
       (catch Throwable e (println label "THREW:" (ex-message e)))))
(try* "clojure.core publics *agent*" #(get (ns-publics 'clojure.core) '*agent*))
(try* "clojure.core/*agent* root" #(var-get (get (ns-publics 'clojure.core) '*agent*)))
(try* "bind it and read" #(binding [*agent* :sentinel] *agent*))
(println "DONE")
(System/exit 0)

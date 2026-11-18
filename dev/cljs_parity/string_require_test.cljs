(ns string-require-test
  "A graph namespace in the shape of eve's node adapters: string
   requires of node builtins (one plain name, one node:-prefixed),
   used from ordinary defns. Loaded by string_require_proof.clj
   through boot!'s :load-ns, never required from JVM code."
  (:require ["crypto" :as nc]
            ["node:fs" :as fs]))

(defn sha256-hex
  "The hex SHA-256 of s, over node's crypto."
  [s]
  (.digest (.update (.createHash nc "sha256") s) "hex"))

(defn fs-there?
  "Whether path p exists, over node's fs."
  [p]
  (.existsSync fs p))

(defonce promise-outcome (atom nil))

(defn promise-sum!
  "Promise.all over two resolved promises; the sum lands in
   promise-outcome when the realm's pump turns. Promise.all throws
   'called on non-object' when the call loses its receiver (the
   shape eve.process-slab's init! hits in the member graph)."
  []
  (-> (js/Promise.all (into-array [(js/Promise.resolve 20)
                                   (js/Promise.resolve 22)]))
      (.then (fn [xs] (reset! promise-outcome (reduce + 0 xs))))))

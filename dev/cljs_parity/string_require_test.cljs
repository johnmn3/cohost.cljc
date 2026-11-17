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

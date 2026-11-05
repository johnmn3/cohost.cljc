(ns eve-paths
  "Computes the -Sdeps map that puts Eve's ClojureScript host row on the
  classpath for cohost's cljs compiles.

  Why this exists: Eve is a tools.deps git dep (see deps.edn :cljs alias),
  but a dep contributes only its root :paths -- and Eve's root :paths are
  its JVM host row. Eve's consumption contract (eve/hosts.edn, eve's
  doc/adopter-tiers.md) is: a host is Eve's src plus one row of adapter
  roots from hosts.edn, placed on the classpath after src. Eve's own
  builds select the row through deps.edn aliases (:node-test), which a
  consumer cannot select for a dependency. So cohost's cljs build
  composes the row itself, from the pinned checkout's own hosts.edn --
  the same composition Eve's bb.edn performs for its hosts
  (bb classpath <host>: src + the hosts.edn roots, absolute).

  The pin lives only in deps.edn; the checkout is located the way
  tools.deps lays it out: $GITLIBS/libs/eve/eve/<sha> (GITLIBS env, else
  ~/.gitlibs). If the checkout is missing, the dep has not been fetched
  yet: run any clojure -Spath/-M:cljs once and retry."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn eve-checkout-dir
  "Absolute path of the pinned Eve checkout for the git dep in deps.edn."
  [repo]
  (let [deps (edn/read-string (slurp (io/file repo "deps.edn")))
        sha (get-in deps [:aliases :cljs :extra-deps 'eve/eve :git/sha])
        gitlibs (or (System/getenv "GITLIBS")
                    (str (System/getProperty "user.home") "/.gitlibs"))]
    (when-not sha
      (throw (ex-info "no eve/eve git dep pinned in deps.edn :cljs alias" {})))
    (io/file gitlibs "libs" "eve" "eve" sha)))

(defn eve-cljs-sdeps
  "The -Sdeps EDN string adding Eve's hosts.edn :cljs adapter roots
  (absolute paths into the pinned checkout) to the classpath."
  [repo]
  (let [eve-root (eve-checkout-dir repo)
        hosts-file (io/file eve-root "hosts.edn")]
    (when-not (.exists hosts-file)
      (throw (ex-info (str "Eve checkout not materialized at " eve-root
                           " -- run `clojure -Spath -X:cljs` once to fetch the git dep, then retry.")
                      {:eve-root (str eve-root)})))
    (let [hosts (edn/read-string (slurp hosts-file))
          roots (mapv #(str (io/file eve-root %)) (:cljs hosts))]
      (pr-str {:paths roots}))))

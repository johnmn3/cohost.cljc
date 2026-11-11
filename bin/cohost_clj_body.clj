(ns cohost.clj-body
  "The :clj leg's runtime is the stock `clojure` CLI.

   cohost does not inspect a JVM. `clojure` on PATH keeps the existing
   launch. Otherwise, when `jolt` is on PATH, the leg is jolt's :clj
   handler on the champaca path. That boot is still a stub.

   COHOST_CLJ_BODY=clojure|jolt forces one of those. Unset follows PATH.

   Champaca's guest-private JVM/Chez internals are waived from the memory
   law (John, 00:50 ET) and are not implemented here. This side retains
   nothing: no scanner, header counter, journal, or arena. A value that
   outlives the call would be an atom."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn- on-path?
  "True when `name` resolves as a command. Does not run it."
  [name]
  (boolean (fs/which name)))

(defn- read-override
  "COHOST_CLJ_BODY -> nil, :clojure, :jolt, or an error map.
   Unset or blank is nil. Comparison is case-insensitive."
  []
  (let [raw (System/getenv "COHOST_CLJ_BODY")
        token (some-> raw str/trim)]
    (if (str/blank? token)
      nil
      (case (str/lower-case token)
        "clojure" :clojure
        "jolt" :jolt
        {:code 1
         :lines [(str "cohost: COHOST_CLJ_BODY must be clojure or jolt (got "
                      (pr-str raw) ").")]}))))

(defn select-clj-body
  "-> {:body :clojure|:jolt} or {:body :error :code n :lines [...]}."
  []
  (let [override (read-override)]
    (if (map? override)
      {:body :error :code (:code override) :lines (:lines override)}
      (let [clojure? (on-path? "clojure")
            jolt? (on-path? "jolt")]
        (cond
          (= :clojure override)
          (if clojure?
            {:body :clojure}
            {:body :error :code 1
             :lines ["cohost: COHOST_CLJ_BODY=clojure but `clojure` is not on PATH."]})

          (= :jolt override)
          (if jolt?
            {:body :jolt}
            {:body :error :code 1
             :lines ["cohost: COHOST_CLJ_BODY=jolt but `jolt` is not on PATH."]})

          clojure? {:body :clojure}
          jolt? {:body :jolt}
          :else {:body :error :code 1
                 :lines ["cohost: :clj runs on `clojure`, which is not on PATH."
                         "  jolt is not on PATH either, so the champaca backup is unavailable."]})))))

(defn jolt-clj-plan
  "The launch the champaca spike has to make real. :boot :stub means this
   tree does not execute it. Arguments are what the clojure CLI launch
   would have received, without the leading clojure token."
  [cwd args]
  (array-map :handler :jolt-clj
             :runtime :champaca
             :boot :stub
             :cwd (str cwd)
             :args (vec args)))

(defn launch-jolt-clj!
  "Report the champaca :clj plan on stderr and return 1.
   jolt is not started. Callers System/exit the code."
  [cwd args]
  (let [plan (jolt-clj-plan cwd args)]
    (binding [*out* *err*]
      (println "cohost: :clj body selected jolt's :clj handler on the champaca path.")
      (println "  champaca boot is not proven yet (stub). The native :jolt adapter is not this path.")
      (println (str "  plan: " (pr-str plan))))
    1))

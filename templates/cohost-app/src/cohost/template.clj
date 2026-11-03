(ns cohost.template
  "deps-new :data-fn of the cohost/app template."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn- template-path []
  (some-> (io/resource "cohost/app/template.edn") str))

(defn cohost-sha
  "The commit of johnmn3/cohost.cljc this template came from: the sha in its
   gitlibs path when deps-new runs it as a git dep, else the HEAD of the
   checkout it sits in (bin/cohost's -T from a clone)."
  []
  (let [p (template-path)]
    (or (some->> p (re-find #"/([0-9a-f]{40})/templates/cohost-app/") second)
        (let [dir (some-> p (str/replace #"^file:" "") io/file
                          .getParentFile .getParentFile .getParentFile .getParentFile .getParentFile)
              r (when dir (sh/sh "git" "-C" (str dir) "rev-parse" "HEAD"))]
          (when (and r (zero? (:exit r))) (str/trim (:out r)))))))

(defn data
  "The generated :build alias depends on cohost.build at this commit, as a git
   dep on the public repo: the project builds with no cohost checkout."
  [_]
  {:cohost-sha (or (cohost-sha) (throw (ex-info "cohost/app: cannot tell which cohost commit this template is" {})))})

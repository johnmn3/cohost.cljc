;; bb lint:reader-order — the reader conditionals in cohost's own sources, checked
;; for the hosts that read them. A host takes the FIRST arm whose feature it has,
;; in written order, and two hosts also have :clj: jolt ({:jolt :clj ...}) and
;; ClojureWasm ({:cljw :clj :default}). So in a conditional with a :clj arm,
;; :jolt and :cljw must both be there, ahead of it, or that host silently reads
;; the JVM's arm. Also checked: an arm after :default is never read, and a
;; feature no host has (a misspelled :cjlw) never matches.
(require '[edamame.core :as e]
         '[babashka.fs :as fs]
         '[clojure.walk :as walk])

(def known #{:clj :cljs :jolt :lpy :cljw :phel :lg :cljrs :jank :default})
(def read-clj #{:jolt :cljw})

(defn conditionals [path]
  (let [rcs (atom [])]
    (walk/postwalk (fn [x] (when (reader-conditional? x) (swap! rcs conj x)) x)
                   (e/parse-string-all (slurp (str path))
                                       {:all true :read-cond :preserve
                                        :auto-resolve (fn [a] (if (= :current a) 'user a))
                                        :readers (fn [_] identity)}))
    @rcs))

(defn problems [rc]
  (let [ks (vec (take-nth 2 (:form rc)))
        at (fn [k] (.indexOf ks k))
        clj (at :clj)
        dflt (at :default)]
    (concat
     (for [k ks :when (not (known k))] (str "unknown feature " k))
     (when (and (>= dflt 0) (< dflt (dec (count ks))))
       [(str "arms after :default are never read: " (pr-str (subvec ks (inc dflt))))])
     (when (>= clj 0)
       (for [k (sort read-clj) :when (not (< -1 (at k) clj))]
         (str k " must come before :clj (it reads :clj arms too)"))))))

(let [files (sort (map str (fs/glob "src" "**.{clj,cljc,cljs}")))
      bad (for [f files rc (conditionals f) p (problems rc)]
            (str f ":" (:row (meta (:form rc))) ": " p "  in " (if (:splicing? rc) "#?@" "#?") (pr-str (:form rc))))]
  (doseq [b bad] (println b))
  (println (str "lint:reader-order: " (count files) " files, "
                (reduce + (map (comp count conditionals) files)) " conditionals, "
                (count bad) " problems"))
  (when (seq bad) (System/exit 1)))

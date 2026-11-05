;; Eve-in-cohost, unit 5: the dosync census. Where do the battery
;; and the stock library corpus actually use the transactional
;; surface (ref / dosync / commute / ref-set / alter / ensure /
;; sync)? Counts are by FORM, walked from parsed sources (not
;; text matches): a call site is a form whose head is one of the
;; names. For each dosync/sync block, the census also records
;; which transactional ops appear inside it.
;;
;; Q2 is ruled (no STM for now); this census is the unit's Ref
;; deliverable -- it says what a later STM would have to serve.
;;
;; Corpus: src/cohost/battery.cljc + every .clj under
;; resources/cohost/clojure-1.12.0/ (the vendored stock sources
;; the boot and the library checks run).

(require '[clojure.java.io :as io]
         '[clojure.walk :as walk])

(def tx-names '#{ref dosync commute ref-set alter ensure sync})

(defn file-forms [f]
  (with-open [r (clojure.lang.LineNumberingPushbackReader. (io/reader f))]
    (loop [out []]
      (let [form (try (read {:eof ::eof :read-cond :allow :features #{:clj}} r)
                      (catch Exception _ ::err))]
        (cond (= form ::eof) out
              (= form ::err) out
              :else (recur (conj out [(.getLineNumber r) form])))))))

(defn subforms [form]
  (let [acc (atom [])]
    (walk/postwalk (fn [x] (swap! acc conj x) x) form)
    @acc))

(defn call-heads [form]
  (into []
        (comp (filter seq?)
              (map first)
              (filter symbol?)
              (filter tx-names))
        (subforms form)))

(def corpus
  (into ["src/cohost/battery.cljc"]
        (comp (filter #(.isFile %))
              (filter #(clojure.string/ends-with? (.getName %) ".clj"))
              (map #(.getPath %)))
        (file-seq (io/file "resources/cohost/clojure-1.12.0"))))

(def sites
  (vec
   (for [path (sort corpus)
         [line form] (file-forms (io/file path))
         head (call-heads form)]
     {:file path :line line :head head :form form})))

(println "files scanned:" (count corpus))
(println "total transactional call sites:" (count sites))
(println)
(println "== by name ==")
(doseq [[n c] (sort-by (comp - val) (frequencies (map :head sites)))]
  (println (str "  " n ": " c)))
(println)
(println "== by file ==")
(doseq [[f c] (sort-by (comp - val) (frequencies (map :file sites)))]
  (println (str "  " f ": " c)))
(println)
(println "== dosync / sync blocks, and what is inside them ==")
(doseq [s (filter #(contains? #{'dosync 'sync} (:head %)) sites)
        sub (filter (fn [x] (and (seq? x) (contains? #{'dosync 'sync} (first x))))
                    (subforms (:form s)))]
  (let [inner (frequencies (call-heads sub))]
    (println (str "  " (:file s) ":" (:line s) " " (first sub)
                  " contains " (pr-str (dissoc inner 'dosync 'sync))))))
(println)
(println "== ref call sites ==")
(doseq [s (filter #(= 'ref (:head %)) sites)]
  (println (str "  " (:file s) ":" (:line s))))

(println)
(println "== the battery: its programs are string literals; each is")
(println "   read and walked as code ==")
(def battery-strings
  (into []
        (comp (mapcat (fn [[_ form]] (subforms form)))
              (filter string?))
        (file-forms (io/file "src/cohost/battery.cljc"))))
(def battery-sites
  (vec
   (for [s battery-strings
         :let [form (try (read-string {:read-cond :allow :features #{:clj}} s)
                         (catch Exception _ nil))]
         :when form
         head (call-heads form)]
     {:program s :head head :form form})))
(println "battery strings walked:" (count battery-strings))
(println "battery transactional call sites:" (count battery-sites))
(doseq [[n c] (sort-by (comp - val) (frequencies (map :head battery-sites)))]
  (println (str "  " n ": " c)))
(doseq [s battery-sites]
  (println (str "  site: " (:head s) " in " (pr-str (subs (:program s) 0 (min 72 (count (:program s))))))))
(System/exit 0)

;; CLJS parity stage 2, app side: the cohost-media frontend files as
;; .cljc. Checks, for each file named on the command line:
;;
;;   clj-reads    every form reads with the JVM reader at :clj
;;                features (the file is a well-formed cljc)
;;   cljs-reads   every form reads with the cljs reader at :cljs
;;   ns-shape     the :cljs-resolved ns form requires exactly the
;;                libraries the original .cljs required, and the
;;                :clj-resolved ns form requires none (the frontend's
;;                libraries are cljs-side, required via #?(:cljs ...))
;;
;;   dev/cljs_parity/run.sh app_cljc_check.clj <page.cljc> <tasks.cljc>
(require '[clojure.java.io :as io]
         '[cljs.vendor.clojure.tools.reader :as cljs-reader])

(defn read-all [read-one file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (loop [acc []]
      (let [f (read-one r)]
        (if (= ::eof f) acc (recur (conj acc f)))))))

(defn read-all-clj [file]
  (read-all (fn [r] (read {:eof ::eof :read-cond :allow :features #{:clj}} r)) file))

(defn read-all-cljs [file]
  (let [opts {:read-cond :allow :features #{:cljs} :eof ::eof}]
    (read-all (fn [r] (cljs-reader/read opts r)) file)))

(defn required-libs [ns-form]
  ;; the lib specs of every :require clause in a resolved ns form
  (into #{}
        (comp (filter seq?)
              (filter #(= :require (first %)))
              (mapcat rest)
              (map (fn [spec] (if (sequential? spec) (first spec) spec))))
        ns-form))

(def expected
  {"tasks" '#{clojure.string eve.net.platform eve.thread eve.thread.net
              eve.thread.rung cohost-media.core}
   "page"  '#{clojure.string eve.process eve.thread eve.thread-host
              eve.thread.domain eve.thread.meta eve.thread.net
              eve.thread.rung cohost-media.core cohost-media.tasks
              reagami.core}})

(def results (atom []))
(defn check [label pass detail]
  (swap! results conj pass)
  (println (if pass "PASS" "FAIL") label (when-not pass (str " -- " detail))))

(doseq [file *command-line-args*]
  (let [base (last (clojure.string/split file #"/"))
        kind (first (clojure.string/split base #"\."))
        clj-forms (try (read-all-clj file) (catch Throwable t (str "THREW: " (ex-message t))))
        cljs-forms (try (read-all-cljs file) (catch Throwable t (str "THREW: " (ex-message t))))]
    (check (str base " clj-reads") (vector? clj-forms) (str clj-forms))
    (check (str base " cljs-reads") (vector? cljs-forms) (str cljs-forms))
    (when (vector? clj-forms)
      (check (str base " clj-side requires none")
             (empty? (required-libs (first clj-forms)))
             (str (required-libs (first clj-forms)))))
    (when (vector? cljs-forms)
      (check (str base " cljs-side requires the original libs")
             (= (get expected kind) (required-libs (first cljs-forms)))
             (str "got " (required-libs (first cljs-forms)))))))

(let [rs @results]
  (println "APP-CLJC-CHECK:" (count (filter identity rs)) "/" (count rs) "passed")
  (System/exit (if (every? identity rs) 0 1)))

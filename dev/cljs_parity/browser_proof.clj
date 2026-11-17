;; CLJS parity stage 3: the browser thread type. From a plain cohost
;; program: start a browser thread giving only the bootstream
;; location -- the seat starts its server and the browser, the page
;; boots stock cljs via the shim, forms evaluated in the page realm
;; return their results to the driver, and the thread stops cleanly.
;; CORS is measured from inside the page against a second origin
;; served by the same machinery (start-server!): denied with no
;; grant in its config, allowed once the page's origin is granted.
;;
;;   EVE_CHROME=/path/to/chrome dev/cljs_parity/run.sh browser_proof.clj
;;
;; The driver's own HTTP checks use curl (a child process), as in
;; browser_server_proof.clj: JVM clients from this lineage get
;; direct connections reset (Shape 4); the browser is a child
;; process and reaches the server the same way curl does.
(require 'cohost.hosts.auto)
(require '[cohost.thread :as thread]
         '[cohost.cljs-pool :as pool]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str])
(import '[java.nio.file Files]
        '[java.nio.file.attribute FileAttribute])

(def checks (atom []))
(defn check [label ok & [detail]]
  (swap! checks conj {:label label :pass (boolean ok) :detail detail})
  (println (if ok "PASS" "FAIL") label (when (and (not ok) detail) detail)))

(defn curl-headers
  "Response headers of a GET from a curl child: lower-cased names."
  [url]
  (let [hf (java.io.File/createTempFile "cohost-proof-headers" ".txt")
        {:keys [exit]} (shell/sh "curl" "-sS" "--max-time" "10" "-D" (.getPath hf) "-o" "/dev/null" url)]
    (when (zero? exit)
      (into {} (for [line (rest (str/split-lines (slurp hf)))
                    :when (str/includes? line ":")
                    :let [[k v] (str/split line #":" 2)]]
                [(str/lower-case (str/trim k)) (str/trim v)])))))

;; --- the bootstream the thread is given (its only argument) -------------
(def dir (Files/createTempDirectory "cohost-bootstream-proof-" (into-array FileAttribute [])))
(spit (io/file (.toFile dir) "index.html")
      "<!DOCTYPE html>\n<html><head><title>browser proof</title></head><body>PROOF</body></html>\n")
(spit (io/file (.toFile dir) "asset.txt") "ASSET-DATA")

(def ex (pool/executor 1 {:seat :browser :bootstream (.toString dir)}))

(defn sub [ns-sym form & args]
  (thread/deref-future (apply thread/submit-form ex ns-sym form args)))

(defn call* [qsym & args]
  (thread/deref-future (apply thread/submit-call ex qsym args)))

(def t0 (System/currentTimeMillis))
(def boot-result
  (try (call* 'cljs.core/+ 1 2)
       (catch Throwable t (str "THREW: " (ex-message t) " " (ex-message (ex-cause t))))))
(println "BOOT (first submit, server+browser+boot) ms:" (- (System/currentTimeMillis) t0))
(check "boot-plus" (= 3 boot-result) (str "got=" (pr-str boot-result)))

;; --- the seat checks, in the page realm ---------------------------------
(def seat-checks
  [["apply-vec"   #(sub 'cljs.user '(fn [xs] (reduce + xs)) [1 2 3 4]) 10]
   ["map-result"  #(sub 'cljs.user '(fn [m] (assoc m :c 3)) {:a 1 :b 2}) {:a 1 :b 2 :c 3}]
   ["string-join" #(call* 'clojure.string/join "-" ["a" "b" "c"]) "a-b-c"]
   ["set-union"   #(call* 'clojure.set/union #{1 2} #{2 3}) #{1 2 3}]
   ["reader"      #(call* 'cljs.reader/read-string "[1 :a \"x\"]") [1 :a "x"]]
   ["js-error"    #(sub 'cljs.user '(fn [] (try (throw (js/Error. "kaboom")) (catch :default e (.-message e))))) "kaboom"]
   ["def-persist" #(do (sub 'cljs.user '(fn [] (do (def proof-x 41) nil)))
                       (sub 'cljs.user '(fn [] (inc proof-x)))) 42]])
(doseq [[label thunk expected] seat-checks]
  (let [got (try (thunk) (catch Throwable t (str "THREW: " (ex-message t))))]
    (check label (= expected got) (str "expected=" (pr-str expected) " got=" (pr-str got)))))

;; --- the page is a real browser page ------------------------------------
(def origin (sub 'cljs.user '(fn [] (.-origin (.-location js/window)))))
(check "page-origin" (and (string? origin)
                          (some? (re-matches #"http://127\.0\.0\.1:\d+" origin)))
       (str "origin=" (pr-str origin)))
(check "cross-origin-isolated"
       (= true (sub 'cljs.user '(fn [] (aget js/global "crossOriginIsolated")))))
(check "shared-array-buffer"
       (= true (sub 'cljs.user '(fn [] (not (nil? (aget js/global "SharedArrayBuffer")))))))
(let [headers (curl-headers (str origin "/"))]
  (check "thread-server-isolation-headers"
         (and (= "same-origin" (get headers "cross-origin-opener-policy"))
              (= "require-corp" (get headers "cross-origin-embedder-policy")))
         (str "headers=" (pr-str headers))))

;; --- fetches from inside the page ---------------------------------------
(defn page-fetch!
  "Fetch url from the page (headers a string->string map); the
   outcome lands in the realm as [:ok text] or [:err message];
   answer it, waiting for the promise chain to settle."
  [url headers]
  (sub 'cljs.user '(fn [] (def proof-fetch-outcome nil)))
  (sub 'cljs.user
       '(fn [url hdrs]
          (-> (.fetch js/global url (js-obj "headers" (clj->js hdrs)))
              (.then (fn [r] (.text r)))
              (.then (fn [t] (def proof-fetch-outcome [:ok t])))
              (.catch (fn [e] (def proof-fetch-outcome [:err (.-message e)]))))
          nil)
       url headers)
  (loop [n 0]
    (let [v (sub 'cljs.user '(fn [] proof-fetch-outcome))]
      (cond (some? v) v
            (> n 60) ::timeout
            :else (do (Thread/sleep 250) (recur (inc n)))))))

(check "same-origin-asset-fetch"
       (= [:ok "ASSET-DATA"] (page-fetch! "/asset.txt" {})))

;; --- CORS against a second origin, from the page ------------------------
(def data-handler
  (fn [req] (when (= "/data" (:uri req))
              {:status 200 :headers {"Content-Type" "text/plain"} :body "B-DATA"})))

(def b1 (pool/start-server! {:handler data-handler}))
(try
  (let [outcome (page-fetch! (str (:url b1) "/data") {"X-Proof" "1"})]
    (check "cors-denied-without-grant" (= :err (first outcome))
           (str "outcome=" (pr-str outcome))))
  (finally ((:stop! b1))))

(def b2 (pool/start-server! {:handler data-handler
                             :server {:cors {:origins [origin] :headers ["X-Proof"]}}}))
(try
  (let [outcome (page-fetch! (str (:url b2) "/data") {"X-Proof" "1"})]
    (check "cors-allowed-with-grant" (= [:ok "B-DATA"] outcome)
           (str "outcome=" (pr-str outcome))))
  (finally ((:stop! b2))))

;; --- a clean stop ---------------------------------------------------------
(thread/shutdown-executor! ex)
(Thread/sleep 500)
(let [{:keys [exit]} (shell/sh "curl" "-sS" "--max-time" "5" (str origin "/__cohost/ping"))]
  (check "server-stopped" (not (zero? exit))))
(let [{:keys [exit]} (shell/sh "pgrep" "-f" "cohost-browser-chrome-")]
  (check "browser-stopped" (not (zero? exit))))

(def n-pass (count (filter :pass @checks)))
(println "BROWSER-PROOF:" n-pass "/" (count @checks) "passed")
(System/exit (if (= n-pass (count @checks)) 0 1))

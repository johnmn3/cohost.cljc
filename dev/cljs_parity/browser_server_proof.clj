;; CLJS parity stage 3, the web half: the browser seat's server with
;; no browser behind it (cohost.cljs-pool/start-server!). The default
;; config serves the built-in bootstream WITH the cross-origin
;; isolation headers; a named bootstream is served with the realm
;; host script injected and its directory as the static root; the
;; CORS header pipeline grants exactly the origins configured.
;;
;;   dev/cljs_parity/run.sh browser_server_proof.clj
(require '[cohost.cljs-pool :as pool]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str])
(import '[java.nio.file Files]
        '[java.nio.file.attribute FileAttribute])

;; The proof's HTTP client is curl, a child process -- measured on
;; this box: a JVM client from this process lineage gets its direct
;; TCP connections reset even to 127.0.0.1 (a raw java.net.Socket
;; included; the Shape-4 wall), while curl answers fine and the
;; server (the accept side) serves it correctly. The browser the
;; seat starts is a child process for the same reason the demo's
;; members could transact while its launcher's clients could not.
(defn req
  "One HTTP round trip: {:status :headers :body}, header names
   lower-cased."
  ([url] (req url {}))
  ([url headers] (req url headers "GET" nil))
  ([url headers method body]
   (let [hf (java.io.File/createTempFile "cohost-proof-headers" ".txt")
         args (cond-> ["curl" "-sS" "--max-time" "10" "-D" (.getPath hf) "-X" method]
                (seq headers) (into (mapcat (fn [[k v]] ["-H" (str k ": " v)]) headers))
                body (into ["--data-binary" body])
                true (conj url))
         {:keys [exit out err]} (apply shell/sh args)]
     (when-not (zero? exit)
       (throw (ex-info (str "curl failed for " url ": " err) {:url url})))
     (let [lines (str/split-lines (slurp hf))
           status (Integer/parseInt (second (str/split (first lines) #" +")))]
       {:status status
        :headers (into {} (for [line (rest lines)
                                :when (str/includes? line ":")
                                :let [[k v] (str/split line #":" 2)]]
                            [(str/lower-case (str/trim k)) (str/trim v)]))
        :body out}))))

(def checks (atom []))
(defn check [label ok & [detail]]
  (swap! checks conj {:label label :pass (boolean ok) :detail detail})
  (println (if ok "PASS" "FAIL") label (when (and (not ok) detail) detail)))

;; --- the default server: no config at all -------------------------------
(def d (pool/start-server! {}))
(try
  (let [r (req (:url d))]
    (check "default-bootstream-200" (= 200 (:status r)) (str "status=" (:status r)))
    (check "default-bootstream-injects-realm"
           (str/includes? (:body r) "<script src=\"/__cohost/realm.js\"></script>"))
    (check "default-isolation-coop"
           (= "same-origin" (get (:headers r) "cross-origin-opener-policy"))
           (str "headers=" (pr-str (:headers r))))
    (check "default-isolation-coep"
           (= "require-corp" (get (:headers r) "cross-origin-embedder-policy")))
    (check "default-cache-no-cache"
           (= "no-cache" (get (:headers r) "cache-control"))))
  (let [r (req (str (:url d) "/__cohost/realm.js"))]
    (check "realm-js-served" (and (= 200 (:status r))
                                  (str/includes? (:body r) "__cohostLoadFile"))))
  (let [r (req (str (:url d) "/__cohost/ping"))]
    (check "ping" (= "ok" (:body r))))
  (let [r (req (str (:url d) "/__cohost/file?path=base.js"))]
    (check "goog-file-service" (and (= 200 (:status r))
                                    (str/includes? (:body r) "goog"))))
  (finally ((:stop! d))))

;; --- isolation off ------------------------------------------------------
(def ni (pool/start-server! {:server {:isolation false}}))
(try
  (let [r (req (:url ni))]
    (check "isolation-off-no-coop"
           (nil? (get (:headers r) "cross-origin-opener-policy"))))
  (finally ((:stop! ni))))

;; --- a named bootstream: injection + static root + handler --------------
(def dir (Files/createTempDirectory "cohost-bootstream-proof-" (into-array FileAttribute [])))
(spit (io/file (.toFile dir) "index.html")
      "<!DOCTYPE html>\n<html><head><title>proof</title></head><body>BOOTSTREAM</body></html>\n")
(spit (io/file (.toFile dir) "asset.txt") "ASSET-DATA")
(def b (pool/start-server! {:bootstream (.toString dir)
                            :handler (fn [req]
                                       (when (= "/api" (:uri req))
                                         {:status 200
                                          :headers {"Content-Type" "text/plain"}
                                          :body "API-DATA"}))}))
(try
  (let [r (req (:url b))]
    (check "bootstream-served" (str/includes? (:body r) "BOOTSTREAM"))
    (check "bootstream-injected"
           (str/includes? (:body r) "<script src=\"/__cohost/realm.js\"></script>")))
  (let [r (req (str (:url b) "/index.html"))]
    (check "bootstream-by-name-injected"
           (str/includes? (:body r) "<script src=\"/__cohost/realm.js\"></script>")))
  (let [r (req (str (:url b) "/asset.txt"))]
    (check "static-asset" (= "ASSET-DATA" (:body r))))
  (let [r (req (str (:url b) "/api"))]
    (check "handler-endpoint" (= "API-DATA" (:body r)))
    (check "handler-under-pipeline"
           (= "require-corp" (get (:headers r) "cross-origin-embedder-policy"))))
  (let [r (req (str (:url b) "/nope.txt"))]
    (check "missing-404" (= 404 (:status r))))
  (finally ((:stop! b))))

;; --- the CORS pipeline, measured at the header level --------------------
;; (The browser-enforced denial/grant is the browser proof's leg; this
;; leg measures what the server emits, origin by origin.)
(def page-origin "http://127.0.0.1:9999")
(def c (pool/start-server! {:server {:cors {:origins [page-origin]
                                            :headers ["X-Proof"]}}}))
(try
  (let [r (req (:url c) {"Origin" page-origin})]
    (check "cors-granted-origin"
           (= page-origin (get (:headers r) "access-control-allow-origin"))
           (str "headers=" (pr-str (:headers r)))))
  (let [r (req (:url c) {"Origin" "http://stranger.example"})]
    (check "cors-ungranted-origin"
           (nil? (get (:headers r) "access-control-allow-origin"))))
  (let [r (req (:url c) {"Origin" page-origin
                         "Access-Control-Request-Method" "GET"
                         "Access-Control-Request-Headers" "X-Proof"}
               "OPTIONS" nil)]
    (check "cors-preflight"
           (and (= 204 (:status r))
                (= page-origin (get (:headers r) "access-control-allow-origin"))
                (str/includes? (get (:headers r) "access-control-allow-methods" "") "GET")
                (= "X-Proof" (get (:headers r) "access-control-allow-headers")))
           (str "status=" (:status r) " headers=" (pr-str (:headers r)))))
  (finally ((:stop! c))))

(def n-pass (count (filter :pass @checks)))
(println "SERVER-PROOF:" n-pass "/" (count @checks) "passed")
(System/exit (if (= n-pass (count @checks)) 0 1))

;; Eve-in-cohost unit 9 gate, JVM seat: the java.net HTTP surface and Ring,
;; behind cohost's shim, answered by Eve's HTTP layer.
;;
;;   clojure -Sdeps '{:paths ["src" "<eve>/adapters/http/java/src" "<eve>/adapters/http-server/jdk/src"]
;;                    :deps {ring/ring-jetty-adapter {:mvn/version "1.12.2"}}}' -M eve_http_check.clj
;;
;; (<eve>: a checkout of github.com/SeniorCareMarket/eve.) cohost.http-eve
;; installs Eve's eve.http / eve.http-server as cohost.http's leaves; the
;; stock core is booted on the JVM seat; every program below is one source
;; string, evaluated twice with the program unchanged:
;;   REF    -- plain JVM Clojure (the JDK's own java.net classes), in a
;;             namespace cohost never touched;
;;   COHOST -- the booted user namespace: the codemod sends java.net's
;;             constructors, statics and members to the shim, the shim to
;;             cohost.http, cohost.http to Eve's leaf.
;; Client side: each program runs REF and COHOST against the same Jetty
;; server (ring-jetty-adapter, the JVM's reference Ring host) and COHOST
;; again against the same handler served by Eve (cohost.ring) -- three
;; answers, all equal. Server side: one Ring handler source, served by
;; Jetty from plain JVM Clojure and by Eve's HTTP server from the booted
;; namespace, asked the same requests by the JDK's own HttpClient; the
;; answers compared.
;; The harness has its own namespace: the booted `user` namespace refers
;; cohost.core's macros, which would send this file's own interop to the shim.
(ns eve-http-check
  (:require [cohost.hosts.jvm]
            [cohost.boot :as boot]
            [cohost.policy]
            [cohost.adapter :as host]
            [cohost.http-eve]
            [cohost.ring]
            [ring.adapter.jetty]))

(println "Eve http leaves installed:" (cohost.http-eve/install!))
(def report (boot/boot! {:host-lacks cohost.policy/thread-names}))
(boot/print-report report)
(boot/make-user-ns!)
(in-ns 'eve-http-check)

(defn cohost-eval [src]
  (host/try-all (fn [] [:ok (boot/eval-str src {:session? false})])
                (fn [e] [:error (str (host/error-message e))])))

(def ref-ns (create-ns 'eve-http-ref))
(binding [*ns* ref-ns] (refer-clojure) (require '[clojure.java.io :as io]))
(defn ref-eval [src]
  (try [:ok (binding [*ns* ref-ns] (load-string src))]
       (catch Throwable e [:error (str (.getMessage e))])))

;; --- the Ring handler, one source for both servers -----------------------
(def handler-src
  "(fn [req]
     (case (:uri req)
       \"/hello\" {:status 200 :headers {\"Content-Type\" \"text/plain\"} :body \"hello\"}
       \"/echo\" {:status 200
                 :headers {\"Content-Type\" \"text/plain\" \"X-Method\" (name (:request-method req))}
                 :body (pr-str [(:request-method req) (:uri req) (:query-string req)
                                (slurp (:body req)) (get-in req [:headers \"x-echo\"])
                                (:scheme req) (integer? (:server-port req))])}
       \"/lines\" {:status 200 :headers {\"Content-Type\" \"text/plain\"} :body \"a\\nb\\nc\\n\"}
       \"/seq\" {:status 200 :headers {\"Content-Type\" \"text/plain\"} :body (list \"x\" \"y\" \"z\")}
       \"/json\" {:status 201 :headers {\"Content-Type\" \"application/json\"} :body \"{\\\"a\\\":1}\"}
       \"/utf8\" {:status 200 :headers {\"Content-Type\" \"text/plain; charset=utf-8\"} :body \"héllo ☃\"}
       \"/empty\" {:status 204 :headers {} :body nil}
       {:status 404 :headers {\"Content-Type\" \"text/plain\"} :body \"nope\"}))")

(def ref-handler (second (ref-eval handler-src)))
(def cohost-handler (let [[k v] (cohost-eval handler-src)] (if (= :ok k) v (throw (ex-info (str "handler: " v) {})))))

(def jetty (ring.adapter.jetty/run-jetty ref-handler {:port 0 :join? false :host "127.0.0.1"}))
(def jetty-port (.getLocalPort ^org.eclipse.jetty.server.ServerConnector (first (.getConnectors ^org.eclipse.jetty.server.Server jetty))))
(def eve-srv (cohost.ring/run-server cohost-handler {:port 0}))
(def jetty-base (str "http://127.0.0.1:" jetty-port))
(def eve-base (str "http://127.0.0.1:" (:port eve-srv)))

(def checks (atom []))
(defn verdict! [label pass detail]
  (swap! checks conj pass)
  (println (if pass "PASS" "FAIL") label (if pass (str "=> " (pr-str detail)) detail)))

;; --- client side -------------------------------------------------------
(def setup "(require '[clojure.java.io :as io])")
(ref-eval setup)
(cohost-eval setup)

(defn client-check [label src]
  (let [with-base (fn [base] (str "(let [base \"" base "\"] " src ")"))
        r (ref-eval (with-base jetty-base))
        c (cohost-eval (with-base jetty-base))
        ce (cohost-eval (with-base eve-base))]
    (verdict! (str "client: " label) (and (= :ok (first r)) (= r c ce))
              (if (= :ok (first r) (first c) (first ce)) (second r)
                  (str " REF " (pr-str r) "\n      COHOST " (pr-str c) "\n      COHOST x Eve server " (pr-str ce))))
    (when (and (= :ok (first r)) (not= r c ce))
      (println "      REF" (pr-str r) "\n      COHOST" (pr-str c) "\n      COHOST x Eve server" (pr-str ce)))))

(client-check "HttpClient GET ofString"
  "(let [c (java.net.http.HttpClient/newHttpClient)
         r (.send c (.build (.GET (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base \"/hello\")))))
                  (java.net.http.HttpResponse$BodyHandlers/ofString))]
     [(.statusCode r) (.body r) (.orElse (.firstValue (.headers r) \"Content-Type\") nil) (.getPath (.uri r))])")
(client-check "HttpClient POST ofString, request header, query"
  "(let [c (java.net.http.HttpClient/newHttpClient)
         req (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base \"/echo?q=1&r=two\")))
                 (.header \"X-Echo\" \"echoed\")
                 (.POST (java.net.http.HttpRequest$BodyPublishers/ofString \"posted body\"))
                 (.build))
         r (.send c req (java.net.http.HttpResponse$BodyHandlers/ofString))]
     [(.statusCode r) (read-string (.body r)) (.get (.firstValue (.headers r) \"x-method\")) (.method req)])")
(client-check "HttpClient builder: newBuilder/build, PUT, DELETE"
  "(let [c (.build (java.net.http.HttpClient/newBuilder))
         put (.send c (.build (.PUT (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base \"/echo\")))
                                    (java.net.http.HttpRequest$BodyPublishers/ofString \"p\")))
                    (java.net.http.HttpResponse$BodyHandlers/ofString))
         del (.send c (.build (.DELETE (.uri (java.net.http.HttpRequest/newBuilder) (java.net.URI/create (str base \"/echo\")))))
                    (java.net.http.HttpResponse$BodyHandlers/ofString))]
     [(read-string (.body put)) (read-string (.body del))])")
(client-check "HttpClient 404, 201, 204"
  "(let [c (java.net.http.HttpClient/newHttpClient)
         fetch (fn [p] (.send c (.build (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base p))))
                            (java.net.http.HttpResponse$BodyHandlers/ofString)))]
     (mapv (fn [p] (let [r (fetch p)] [(.statusCode r) (.body r)])) [\"/missing\" \"/json\" \"/empty\"]))")
(client-check "HttpClient UTF-8 body, ofString and ofByteArray"
  "(let [c (java.net.http.HttpClient/newHttpClient)
         req (.build (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base \"/utf8\"))))]
     [(.body (.send c req (java.net.http.HttpResponse$BodyHandlers/ofString)))
      (String. (.body (.send c req (java.net.http.HttpResponse$BodyHandlers/ofByteArray))) \"UTF-8\")
      (count (.body (.send c req (java.net.http.HttpResponse$BodyHandlers/ofByteArray))))])")
(client-check "HttpClient ofInputStream through slurp"
  "(let [c (java.net.http.HttpClient/newHttpClient)
         r (.send c (.build (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base \"/lines\"))))
                  (java.net.http.HttpResponse$BodyHandlers/ofInputStream))]
     (slurp (.body r)))")
(client-check "HttpHeaders: firstValue absent, allValues, map lookup"
  "(let [c (java.net.http.HttpClient/newHttpClient)
         r (.send c (.build (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base \"/json\"))))
                  (java.net.http.HttpResponse$BodyHandlers/ofString))
         h (.headers r)]
     [(.isPresent (.firstValue h \"x-none\")) (.orElse (.firstValue h \"x-none\") :none)
      (vec (.allValues h \"content-type\")) (vec (get (.map h) \"content-type\"))])")
(client-check "HttpClient connection refused is an IOException"
  "(try (.send (java.net.http.HttpClient/newHttpClient)
              (.build (java.net.http.HttpRequest/newBuilder (java.net.URI/create \"http://127.0.0.1:1/\")))
              (java.net.http.HttpResponse$BodyHandlers/ofString))
        :sent
        (catch java.io.IOException e :io-exception))")
(client-check "HttpURLConnection GET"
  "(let [c (.openConnection (java.net.URL. (str base \"/hello\")))]
     [(.getResponseCode c) (.getResponseMessage c) (slurp (.getInputStream c))
      (.getHeaderField c \"content-type\") (.getContentType c) (.getRequestMethod c)])")
(client-check "HttpURLConnection POST through io/writer on getOutputStream"
  "(let [c (.openConnection (java.net.URL. (str base \"/echo?via=conn\")))]
     (.setDoOutput c true)
     (.setRequestProperty c \"X-Echo\" \"prop\")
     (with-open [w (io/writer (.getOutputStream c))] (.write w \"written\"))
     [(.getResponseCode c) (read-string (slurp (.getInputStream c))) (.getRequestMethod c)])")
(client-check "HttpURLConnection PUT via setRequestMethod"
  "(let [c (.openConnection (java.net.URL. (str base \"/echo\")))]
     (.setRequestMethod c \"PUT\")
     (.setDoOutput c true)
     (with-open [o (.getOutputStream c)] (.write o (.getBytes \"put-bytes\" \"UTF-8\")))
     (read-string (slurp (.getInputStream c))))")
(client-check "HttpURLConnection 404: FileNotFoundException, error stream"
  "(let [c (.openConnection (java.net.URL. (str base \"/missing\")))]
     [(.getResponseCode c) (.getResponseMessage c)
      (try (.getInputStream c) :opened (catch java.io.FileNotFoundException e :file-not-found))
      (slurp (.getErrorStream c))])")
(client-check "slurp a URL"
  "(slurp (java.net.URL. (str base \"/hello\")))")
(client-check "clojure.java.io/reader + line-seq over a URL"
  "(with-open [r (io/reader (java.net.URL. (str base \"/lines\")))] (vec (line-seq r)))")
(client-check "clojure.java.io/input-stream over a URL; a seq body"
  "(with-open [in (io/input-stream (java.net.URL. (str base \"/seq\")))] (slurp in))")
(client-check "slurp a URL with a UTF-8 body"
  "(slurp (java.net.URL. (str base \"/utf8\")) :encoding \"UTF-8\")")
(client-check "URL.openStream"
  "(with-open [in (.openStream (java.net.URL. (str base \"/hello\")))] (slurp in))")
(client-check "slurp a URL: connection refused is an IOException"
  "(try (slurp (java.net.URL. \"http://127.0.0.1:1/\")) (catch java.io.IOException e :io-exception))")
(client-check "URL accessors"
  "(let [u (java.net.URL. \"http://user@example.com:8080/a/b.txt?x=1&y=%20#frag\")]
     [(.getProtocol u) (.getHost u) (.getPort u) (.getDefaultPort u) (.getPath u) (.getQuery u) (.getFile u)
      (.getRef u) (.getAuthority u) (.getUserInfo u) (str u) (.toExternalForm u)
      (let [v (java.net.URL. \"https://example.org\")] [(.getPort v) (.getPath v) (.getQuery v) (.getRef v) (.getDefaultPort v)])])")
(client-check "URI accessors, resolve, toURL / toURI"
  "(let [u (java.net.URI/create \"http://example.com:81/dir/page?a=b%20c#top\")]
     [(.getScheme u) (.getHost u) (.getPort u) (.getPath u) (.getQuery u) (.getRawQuery u) (.getFragment u)
      (.isAbsolute u) (str (.resolve u \"other\")) (str (.resolve u \"/root\")) (str (.toURL u))
      (str (.toURI (java.net.URL. \"http://example.com/x\"))) (str (java.net.URI. \"rel/path\"))
      (.isAbsolute (java.net.URI. \"rel/path\")) (.getPath (java.net.URI. \"rel/path\"))])")
(client-check "uri?, instance?, class, MalformedURLException"
  "[(uri? (java.net.URI/create \"http://x\")) (uri? \"http://x\")
    (instance? java.net.URL (java.net.URL. \"http://x\")) (instance? java.net.URI (java.net.URI/create \"http://x\"))
    (.getName (class (java.net.URL. \"http://x\"))) (.getName (class (java.net.URI/create \"http://x\")))
    (try (java.net.URL. \"no-protocol\") (catch java.net.MalformedURLException e (.getMessage e)))
    (try (java.net.URL. \"zzz://x\") (catch java.net.MalformedURLException e (.getMessage e)))
    (= (java.net.URL. \"http://x/a\") (java.net.URL. \"http://x/a\"))]")

;; --- server side -------------------------------------------------------
(def jdk-client (java.net.http.HttpClient/newHttpClient))
(defn ask [base [method path body headers]]
  (let [b (reduce (fn [b [k v]] (.header b k v))
                  (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base path)))
                  headers)
        b (.method b method (if body (java.net.http.HttpRequest$BodyPublishers/ofString body)
                                (java.net.http.HttpRequest$BodyPublishers/noBody)))
        r (.send jdk-client (.build b) (java.net.http.HttpResponse$BodyHandlers/ofString))
        h (.headers r)]
    [(.statusCode r) (.orElse (.firstValue h "content-type") nil) (.orElse (.firstValue h "x-method") nil) (.body r)]))

(doseq [[label req] [["GET /hello" ["GET" "/hello" nil {}]]
                     ["POST /echo with body, query, header" ["POST" "/echo?k=v" "the body" {"X-Echo" "hdr"}]]
                     ["PUT /echo" ["PUT" "/echo" "put" {}]]
                     ["DELETE /echo, no body" ["DELETE" "/echo" nil {}]]
                     ["GET /lines" ["GET" "/lines" nil {}]]
                     ["GET /seq (seq body)" ["GET" "/seq" nil {}]]
                     ["GET /json (201)" ["GET" "/json" nil {}]]
                     ["GET /utf8" ["GET" "/utf8" nil {}]]
                     ["GET /empty (204, nil body)" ["GET" "/empty" nil {}]]
                     ["GET /missing (404)" ["GET" "/missing" nil {}]]]]
  (let [j (ask jetty-base req)
        e (ask eve-base req)]
    (verdict! (str "server: " label) (= j e)
              (if (= j e) j (str "\n      JETTY " (pr-str j) "\n      EVE   " (pr-str e))))))

(.stop ^org.eclipse.jetty.server.Server jetty)
((:stop eve-srv))
(let [cs @checks]
  (println (str "unit 9 gate (JVM seat): " (count (filter true? cs)) "/" (count cs) " PASS")))
(shutdown-agents)
(System/exit (if (every? true? @checks) 0 1))

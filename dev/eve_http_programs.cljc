;; The unit 9 gate's programs (eve_http_check.clj, the JVM seat; dev/eve_http_node.cljs,
;; the ClojureScript seat): one source per program, evaluated unchanged on every
;; seat. Each client program sees `base`, the server's root URL.
(ns eve-http-programs)

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

(def client
  "[label source] -- the client programs"
  [
   ["HttpClient GET ofString"
  "(let [c (java.net.http.HttpClient/newHttpClient)
         r (.send c (.build (.GET (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base \"/hello\")))))
                  (java.net.http.HttpResponse$BodyHandlers/ofString))]
     [(.statusCode r) (.body r) (.orElse (.firstValue (.headers r) \"Content-Type\") nil) (.getPath (.uri r))])"]
   ["HttpClient POST ofString, request header, query"
  "(let [c (java.net.http.HttpClient/newHttpClient)
         req (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base \"/echo?q=1&r=two\")))
                 (.header \"X-Echo\" \"echoed\")
                 (.POST (java.net.http.HttpRequest$BodyPublishers/ofString \"posted body\"))
                 (.build))
         r (.send c req (java.net.http.HttpResponse$BodyHandlers/ofString))]
     [(.statusCode r) (read-string (.body r)) (.get (.firstValue (.headers r) \"x-method\")) (.method req)])"]
   ["HttpClient builder: newBuilder/build, PUT, DELETE"
  "(let [c (.build (java.net.http.HttpClient/newBuilder))
         put (.send c (.build (.PUT (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base \"/echo\")))
                                    (java.net.http.HttpRequest$BodyPublishers/ofString \"p\")))
                    (java.net.http.HttpResponse$BodyHandlers/ofString))
         del (.send c (.build (.DELETE (.uri (java.net.http.HttpRequest/newBuilder) (java.net.URI/create (str base \"/echo\")))))
                    (java.net.http.HttpResponse$BodyHandlers/ofString))]
     [(read-string (.body put)) (read-string (.body del))])"]
   ["HttpClient 404, 201, 204"
  "(let [c (java.net.http.HttpClient/newHttpClient)
         fetch (fn [p] (.send c (.build (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base p))))
                            (java.net.http.HttpResponse$BodyHandlers/ofString)))]
     (mapv (fn [p] (let [r (fetch p)] [(.statusCode r) (.body r)])) [\"/missing\" \"/json\" \"/empty\"]))"]
   ["HttpClient UTF-8 body, ofString and ofByteArray"
  "(let [c (java.net.http.HttpClient/newHttpClient)
         req (.build (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base \"/utf8\"))))]
     [(.body (.send c req (java.net.http.HttpResponse$BodyHandlers/ofString)))
      (String. (.body (.send c req (java.net.http.HttpResponse$BodyHandlers/ofByteArray))) \"UTF-8\")
      (count (.body (.send c req (java.net.http.HttpResponse$BodyHandlers/ofByteArray))))])"]
   ["HttpClient ofInputStream through slurp"
  "(let [c (java.net.http.HttpClient/newHttpClient)
         r (.send c (.build (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base \"/lines\"))))
                  (java.net.http.HttpResponse$BodyHandlers/ofInputStream))]
     (slurp (.body r)))"]
   ["HttpHeaders: firstValue absent, allValues, map lookup"
  "(let [c (java.net.http.HttpClient/newHttpClient)
         r (.send c (.build (java.net.http.HttpRequest/newBuilder (java.net.URI/create (str base \"/json\"))))
                  (java.net.http.HttpResponse$BodyHandlers/ofString))
         h (.headers r)]
     [(.isPresent (.firstValue h \"x-none\")) (.orElse (.firstValue h \"x-none\") :none)
      (vec (.allValues h \"content-type\")) (vec (get (.map h) \"content-type\"))])"]
   ["HttpClient connection refused is an IOException"
  "(try (.send (java.net.http.HttpClient/newHttpClient)
              (.build (java.net.http.HttpRequest/newBuilder (java.net.URI/create \"http://127.0.0.1:1/\")))
              (java.net.http.HttpResponse$BodyHandlers/ofString))
        :sent
        (catch java.io.IOException e :io-exception))"]
   ["HttpURLConnection GET"
  "(let [c (.openConnection (java.net.URL. (str base \"/hello\")))]
     [(.getResponseCode c) (.getResponseMessage c) (slurp (.getInputStream c))
      (.getHeaderField c \"content-type\") (.getContentType c) (.getRequestMethod c)])"]
   ["HttpURLConnection POST through io/writer on getOutputStream"
  "(let [c (.openConnection (java.net.URL. (str base \"/echo?via=conn\")))]
     (.setDoOutput c true)
     (.setRequestProperty c \"X-Echo\" \"prop\")
     (with-open [w (io/writer (.getOutputStream c))] (.write w \"written\"))
     [(.getResponseCode c) (read-string (slurp (.getInputStream c))) (.getRequestMethod c)])"]
   ["HttpURLConnection PUT via setRequestMethod"
  "(let [c (.openConnection (java.net.URL. (str base \"/echo\")))]
     (.setRequestMethod c \"PUT\")
     (.setDoOutput c true)
     (with-open [o (.getOutputStream c)] (.write o (.getBytes \"put-bytes\" \"UTF-8\")))
     (read-string (slurp (.getInputStream c))))"]
   ["HttpURLConnection 404: FileNotFoundException, error stream"
  "(let [c (.openConnection (java.net.URL. (str base \"/missing\")))]
     [(.getResponseCode c) (.getResponseMessage c)
      (try (.getInputStream c) :opened (catch java.io.FileNotFoundException e :file-not-found))
      (slurp (.getErrorStream c))])"]
   ["slurp a URL"
  "(slurp (java.net.URL. (str base \"/hello\")))"]
   ["clojure.java.io/reader + line-seq over a URL"
  "(with-open [r (io/reader (java.net.URL. (str base \"/lines\")))] (vec (line-seq r)))"]
   ["clojure.java.io/input-stream over a URL; a seq body"
  "(with-open [in (io/input-stream (java.net.URL. (str base \"/seq\")))] (slurp in))"]
   ["slurp a URL with a UTF-8 body"
  "(slurp (java.net.URL. (str base \"/utf8\")) :encoding \"UTF-8\")"]
   ["URL.openStream"
  "(with-open [in (.openStream (java.net.URL. (str base \"/hello\")))] (slurp in))"]
   ["slurp a URL: connection refused is an IOException"
  "(try (slurp (java.net.URL. \"http://127.0.0.1:1/\")) (catch java.io.IOException e :io-exception))"]
   ["URL accessors"
  "(let [u (java.net.URL. \"http://user@example.com:8080/a/b.txt?x=1&y=%20#frag\")]
     [(.getProtocol u) (.getHost u) (.getPort u) (.getDefaultPort u) (.getPath u) (.getQuery u) (.getFile u)
      (.getRef u) (.getAuthority u) (.getUserInfo u) (str u) (.toExternalForm u)
      (let [v (java.net.URL. \"https://example.org\")] [(.getPort v) (.getPath v) (.getQuery v) (.getRef v) (.getDefaultPort v)])])"]
   ["URI accessors, resolve, toURL / toURI"
  "(let [u (java.net.URI/create \"http://example.com:81/dir/page?a=b%20c#top\")]
     [(.getScheme u) (.getHost u) (.getPort u) (.getPath u) (.getQuery u) (.getRawQuery u) (.getFragment u)
      (.isAbsolute u) (str (.resolve u \"other\")) (str (.resolve u \"/root\")) (str (.toURL u))
      (str (.toURI (java.net.URL. \"http://example.com/x\"))) (str (java.net.URI. \"rel/path\"))
      (.isAbsolute (java.net.URI. \"rel/path\")) (.getPath (java.net.URI. \"rel/path\"))])"]
   ["uri?, instance?, class, MalformedURLException"
  "[(uri? (java.net.URI/create \"http://x\")) (uri? \"http://x\")
    (instance? java.net.URL (java.net.URL. \"http://x\")) (instance? java.net.URI (java.net.URI/create \"http://x\"))
    (.getName (class (java.net.URL. \"http://x\"))) (.getName (class (java.net.URI/create \"http://x\")))
    (try (java.net.URL. \"no-protocol\") (catch java.net.MalformedURLException e (.getMessage e)))
    (try (java.net.URL. \"zzz://x\") (catch java.net.MalformedURLException e (.getMessage e)))
    (= (java.net.URL. \"http://x/a\") (java.net.URL. \"http://x/a\"))]"]])

(def server
  "[label [method path body headers]] -- the requests the Ring handler is asked"
  [["GET /hello" ["GET" "/hello" nil {}]]
                     ["POST /echo with body, query, header" ["POST" "/echo?k=v" "the body" {"X-Echo" "hdr"}]]
                     ["PUT /echo" ["PUT" "/echo" "put" {}]]
                     ["DELETE /echo, no body" ["DELETE" "/echo" nil {}]]
                     ["GET /lines" ["GET" "/lines" nil {}]]
                     ["GET /seq (seq body)" ["GET" "/seq" nil {}]]
                     ["GET /json (201)" ["GET" "/json" nil {}]]
                     ["GET /utf8" ["GET" "/utf8" nil {}]]
                     ["GET /empty (204, nil body)" ["GET" "/empty" nil {}]]
                     ["GET /missing (404)" ["GET" "/missing" nil {}]]])

(def jvm-only
  "Client programs that need what only the JVM seat has today: the host's
   clojure.java.io (no seat but the JVM has one yet), or the host's byte
   arrays and output streams (the adapter's :byte-streams). A named
   remainder of unit 9, not a pass."
  #{"HttpClient UTF-8 body, ofString and ofByteArray"
    "HttpURLConnection POST through io/writer on getOutputStream"
    "HttpURLConnection PUT via setRequestMethod"
    "clojure.java.io/reader + line-seq over a URL"
    "clojure.java.io/input-stream over a URL; a seq body"})

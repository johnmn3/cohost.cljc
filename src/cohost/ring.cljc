(ns cohost.ring
  "Ring on every Eve target host (MEMORY-PLAN.md, unit 9, the server side
   as amended 2026-11-05 ~11:32 ET): a Ring-spec handler -- a fn of the
   request map answering the response map -- served by Eve's HTTP server
   layer (the :http-serve leaf: eve.http-server/serve!), so a Ring
   application runs unchanged on every seat that gives cohost.http that
   leaf. run-server is the adapter, in the place ring-jetty's run-jetty or
   http-kit's run-server takes in a JVM program; the application's handler
   is untouched.

   The request map follows the Ring spec (SPEC.md, 1.x) from what Eve's
   server hands a handler ({:method :url :headers :body :body-b64}):
   :server-port, :server-name, :uri, :query-string, :scheme,
   :request-method, :protocol, :headers, :body. Labeled distances, each
   what Eve's leaf carries today, not a choice made here:
   - :remote-addr is absent: the leaf's request has no peer address.
   - :headers holds one value per name (the leaf keeps the first); a
     response header given a seq of values is sent as one comma-joined
     value (the leaf adds one value per name).
   - :protocol is \"HTTP/1.1\", the leaf's server's protocol; the request's
     own version is not carried.
   - :scheme is :http: the leaf serves plain HTTP."
  (:require [cohost.adapter :as host]
            [cohost.http :as http]))

(defn- idx [s sub] (let [i (host/index-of s sub 0)] (when (and i (>= i 0)) i)))

(defn- split-url
  "[uri query-string] of the request target as sent"
  [url]
  (if-let [i (idx url "?")] [(subs url 0 i) (subs url (inc i))] [url nil]))

(defn- server-name [headers]
  (when-let [h (get headers "host")]
    ;; host[:port]; an IPv6 literal keeps its brackets
    (if (and (pos? (count h)) (= "[" (subs h 0 1)))
      (subs h 0 (inc (or (idx h "]") (dec (count h)))))
      (if-let [i (idx h ":")] (subs h 0 i) h))))

(defn- request-body [req]
  (if-let [bs (host/byte-streams)]
    ((:input bs) ((:b64-bytes bs) (or (:body-b64 req) "")))
    (http/input-stream (:body req))))

(defn ->request
  "The Ring request map for a request Eve's server handed over on port."
  [req port]
  (let [[uri qs] (split-url (str (:url req)))
        headers (:headers req)]
    {:server-port port
     :server-name (or (server-name headers) "localhost")
     :uri uri
     :query-string qs
     :scheme :http
     :request-method (keyword (host/lower-case (str (:method req))))
     :protocol "HTTP/1.1"
     :headers headers
     :body (request-body req)}))

(defn- header-value [v]
  (if (and (sequential? v) (not (string? v))) (apply str (interpose "," (map str v))) (str v)))

(defn ->response
  "Eve's server response for a Ring response map: a String body as text,
   a seq of Strings concatenated, an InputStream or a File as its bytes,
   nil as no body."
  [resp]
  (let [b (:body resp)
        bs (host/byte-streams)
        bytes (when (and bs (some? b) (not (string? b)) (not (seq? b)) (not (sequential? b)))
                ((:read-all bs) b))]
    (merge {:status (or (:status resp) 200)
            :headers (into {} (map (fn [[k v]] [(str k) (header-value v)])) (:headers resp))}
           (cond (nil? b) {:body ""}
                 (string? b) {:body b}
                 bytes {:body ((:bytes-b64 bs) bytes) :body-as :b64}
                 (http/is? :cohost.http/input-stream b) {:body (http/stream-drain! b)}
                 (or (seq? b) (sequential? b)) {:body (apply str (map str b))}
                 :else (throw (host/ex* 'java.lang.IllegalArgumentException
                                        (str "cohost.ring: unsupported response body: " (pr-str b))))))))

(defn run-server
  "Serve the Ring handler on Eve's HTTP server: opts {:port (0: any free
   one) :host (default 127.0.0.1) :threads}. Answers {:port the bound port
   :stop a fn ending the server}."
  ([handler] (run-server handler {}))
  ([handler opts]
   (when-not (http/server-available?)
     (throw (ex-info "cohost.ring: Eve's http-server leaf is not available on this host"
                     {:cohost/unsupported "cohost.ring/run-server"})))
   (let [port (atom (:port opts))
         srv ((host/http-serve-leaf) (select-keys opts [:port :host :threads])
              (fn [req] (->response (handler (->request req @port)))))]
     (reset! port (:port srv))
     srv)))

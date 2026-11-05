(ns cohost.http
  "Eve's HTTP layer, hosted in cohost (MEMORY-PLAN.md, unit 9): the one
   implementation of the java.net HTTP surface Clojure programs call --
   java.net.http's HttpClient / HttpRequest / HttpResponse (with their
   BodyPublishers, BodyHandlers and HttpHeaders), java.net.URI, and
   java.net.URL / HttpURLConnection (the path clojure.java.io rides) --
   that sits behind cohost's Java shim, backed by Eve's own HTTP layer.

   The leaves are Eve's adapters, through the host adapter's contract:
   :http-request is eve.http/request (one synchronous round trip,
   {:method :url :headers :body :as :body-as} -> {:status :headers :body}
   or {:error message}) and :http-serve is eve.http-server/serve! (the
   server side, which cohost.ring puts a Ring handler on). A seat's fill
   installs them (cohost.http-eve on the JVM seat). Where they are absent,
   available? is false and the shim leaves java.net to the host, as before.

   Values of the layer are plain maps tagged :cohost/kind, as cohost.thread's
   are; the shim dispatches the classes' members on the tag. A value with
   mutable state (an HttpURLConnection, an InputStream on a host without
   byte streams of its own) holds it in an atom, never in a field.
   Equality is the map's: a named distance from the Java classes, as unit 1
   named it for the thread layer's values."
  (:require [cohost.adapter :as host]))

;; ---------------------------------------------------------------------------
;; string helpers (the layer runs on every host: no clojure.string here)

(defn- lc [s] (host/lower-case (str s)))
(defn- idx [s sub] (let [i (host/index-of s sub 0)] (when (and i (>= i 0)) i)))
(defn- includes? [s sub] (some? (idx s sub)))
(defn- starts-with? [s p] (and (>= (count s) (count p)) (= p (subs s 0 (count p)))))
(defn- split-once
  "[before after] around the first sep, or [s nil]"
  [s sep]
  (if-let [i (idx s sep)] [(subs s 0 i) (subs s (+ i (count sep)))] [s nil]))
(defn- last-slash [s] (loop [i (dec (count s))] (cond (neg? i) -1 (= "/" (subs s i (inc i))) i :else (recur (dec i)))))
(defn- join-comma [vs] (apply str (interpose "," vs)))

;; ---------------------------------------------------------------------------
;; leaves

(defn available?
  "True when the seat gives the layer Eve's request leaf."
  []
  (boolean (host/http-request-leaf)))

(defn server-available?
  "True when the seat gives the layer Eve's http-server leaf."
  []
  (boolean (host/http-serve-leaf)))

(defn- streams [] (host/byte-streams))

(defn- eve-request
  "One round trip through Eve's leaf; a transport failure is the
   java.io.IOException the JDK's clients throw for it."
  [req]
  (let [resp ((host/http-request-leaf) req)]
    (if-let [e (:error resp)]
      (throw (host/ex* 'java.io.IOException e))
      resp)))

;; ---------------------------------------------------------------------------
;; values

(def kinds
  "The layer's value kinds -> the JVM class token each stands for."
  {::uri 'java.net.URI
   ::url 'java.net.URL
   ::client 'java.net.http.HttpClient
   ::client-builder 'java.net.http.HttpClient$Builder
   ::request-builder 'java.net.http.HttpRequest$Builder
   ::request 'java.net.http.HttpRequest
   ::publisher 'java.net.http.HttpRequest$BodyPublisher
   ::handler 'java.net.http.HttpResponse$BodyHandler
   ::response 'java.net.http.HttpResponse
   ::headers 'java.net.http.HttpHeaders
   ::optional 'java.util.Optional
   ::connection 'java.net.HttpURLConnection
   ::input-stream 'java.io.InputStream})

(defn value?
  "True when x is a value of this layer."
  [x]
  (and (map? x) (contains? kinds (get x :cohost/kind))))

(defn kind-of [x] (when (map? x) (get x :cohost/kind)))
(defn class-token [x] (get kinds (kind-of x)))
(defn is? [k x] (and (map? x) (= k (get x :cohost/kind))))

;; ---------------------------------------------------------------------------
;; URI / URL

(def ^:private uri-re
  ;; scheme ":" [ "//" [userinfo "@"] host [":" port] ] path [ "?" query ] [ "#" fragment ]
  #"^([a-zA-Z][a-zA-Z0-9+.\-]*):(?://(?:([^@/?#]*)@)?(\[[^\]]*\]|[^:/?#]*)(?::(\d*))?)?([^?#]*)(?:\?([^#]*))?(?:#(.*))?$")

(defn- parse [s]
  (when-let [[_ scheme userinfo hst port path query fragment] (re-matches uri-re s)]
    {:scheme scheme
     :user-info userinfo
     :host (when (and hst (not= "" hst)) hst)
     :port (if (and port (not= "" port)) (host/parse-long* port) -1)
     :path (or path "")
     :query query
     :fragment fragment
     :raw s}))

(defn new-uri
  "java.net.URI(String) / URI.create: a hierarchical or opaque URI; a
   string with no scheme is a relative reference (its path is the string)."
  [s]
  (when (nil? s) (throw (host/ex* 'java.lang.NullPointerException nil)))
  (let [s (str s)]
    (if-let [p (parse s)]
      (host/http-value (assoc p :cohost/kind ::uri))
      (if (or (includes? s " ") (includes? s "\\"))
        (throw (host/ex* 'java.lang.IllegalArgumentException (str "Illegal character in path at index 0: " s)))
        (let [[pq fragment] (split-once s "#")
              [path query] (split-once pq "?")]
          (host/http-value {:cohost/kind ::uri :scheme nil :user-info nil :host nil :port -1
                            :path path :query query :fragment fragment :raw s}))))))

(def ^:private url-protocols #{"http" "https" "file" "jar" "ftp" "mailto"})

(defn new-url
  "java.net.URL(String): an absolute URL of a protocol the JDK knows,
   else the JDK's MalformedURLException."
  ([s]
   (when (nil? s) (throw (host/ex* 'java.net.MalformedURLException nil)))
   (let [s (str s)
         p (parse s)]
     (cond (nil? p) (throw (host/ex* 'java.net.MalformedURLException (str "no protocol: " s)))
           (not (contains? url-protocols (lc (:scheme p))))
           (throw (host/ex* 'java.net.MalformedURLException (str "unknown protocol: " (lc (:scheme p)))))
           :else (host/http-value (assoc p :cohost/kind ::url :scheme (lc (:scheme p)))))))
  ([protocol hst port file]
   (new-url (str protocol "://" hst (when (and port (not= -1 port)) (str ":" port)) file))))

(defn uri->url [u]
  (when-not (:scheme u) (throw (host/ex* 'java.lang.IllegalArgumentException "URI is not absolute")))
  (new-url (:raw u)))
(defn url->uri [u] (assoc u :cohost/kind ::uri))

(defn url-string [u] (:raw u))
(defn url-file [u] (str (:path u) (when (:query u) (str "?" (:query u)))))
(defn authority [u]
  (when (:host u)
    (str (when (:user-info u) (str (:user-info u) "@")) (:host u)
         (when (not= -1 (:port u)) (str ":" (:port u))))))

(defn uri-resolve
  "URI.resolve(String): a reference against this base (the common cases:
   absolute, absolute-path, and relative-path references)."
  [base ref]
  (let [r (new-uri ref)]
    (cond (:scheme r) r
          (starts-with? (str ref) "//") (new-uri (str (:scheme base) ":" ref))
          (starts-with? (str ref) "/") (new-uri (str (:scheme base) "://" (authority base) ref))
          :else (let [p (:path base)
                      dir (subs p 0 (inc (last-slash p)))]
                  (new-uri (str (:scheme base) "://" (authority base) dir ref))))))

;; ---------------------------------------------------------------------------
;; headers, Optional

(defn- header-map
  "Header names lower-cased -> [values], in a sorted map (the JDK's
   HttpHeaders map is case-insensitive and ordered by name)."
  [pairs]
  (reduce (fn [m [k v]] (update m (lc k) (fnil conj []) (str v)))
          (sorted-map) pairs))

(defn headers [pairs] {:cohost/kind ::headers :map (header-map pairs)})
(defn headers-map [h] (:map h))
(defn optional [v] {:cohost/kind ::optional :value v})
(defn first-value [h nm] (optional (first (get (:map h) (lc nm)))))
(defn all-values [h nm] (vec (get (:map h) (lc nm))))
(defn optional-get [o]
  (if (nil? (:value o))
    (throw (host/ex* 'java.util.NoSuchElementException "No value present"))
    (:value o)))

;; ---------------------------------------------------------------------------
;; java.net.http: HttpClient, HttpRequest, BodyPublishers, BodyHandlers

(defn new-client-builder [] {:cohost/kind ::client-builder :opts {}})
(defn client-builder-set [b k v] (assoc-in b [:opts k] v))
(defn build-client [b] {:cohost/kind ::client :opts (:opts b)})
(defn new-client [] (build-client (new-client-builder)))

(defn new-request-builder
  ([] {:cohost/kind ::request-builder :uri nil :method "GET" :headers [] :publisher nil :opts {}})
  ([uri] (assoc (new-request-builder) :uri uri)))

(defn- check-header-name [nm]
  (when (contains? #{"connection" "content-length" "expect" "host" "upgrade"} (lc nm))
    (throw (host/ex* 'java.lang.IllegalArgumentException (str "restricted header name: \"" nm "\"")))))

(defn rb-uri [b uri] (assoc b :uri uri))
(defn rb-header [b nm v] (check-header-name nm) (update b :headers conj [nm v]))
(defn rb-set-header [b nm v]
  (check-header-name nm)
  (update b :headers (fn [hs] (conj (vec (remove #(= (lc (first %)) (lc nm)) hs)) [nm v]))))
(defn rb-headers [b kvs] (reduce (fn [b [k v]] (rb-header b k v)) b (partition 2 kvs)))
(defn rb-method [b m pub] (assoc b :method (str m) :publisher pub))
(defn rb-opt [b k v] (assoc-in b [:opts k] v))
(defn build-request [b]
  (when (nil? (:uri b)) (throw (host/ex* 'java.lang.IllegalStateException "uri is null")))
  {:cohost/kind ::request :uri (:uri b) :method (:method b) :headers (headers (:headers b))
   :publisher (:publisher b) :opts (:opts b)})

(defn publisher-of-string [s] {:cohost/kind ::publisher :text (str s)})
(defn publisher-of-bytes [bs] {:cohost/kind ::publisher :bytes bs})
(defn publisher-none [] {:cohost/kind ::publisher})

(defn handler-of [as] {:cohost/kind ::handler :as as})

(defn- wire-headers
  "A request's headers as Eve's leaf takes them: one value per name
   (several values joined with a comma, as HTTP allows)."
  [hm]
  (into {} (map (fn [[k vs]] [k (join-comma vs)])) hm))

(defn- body-args [pub]
  (cond (nil? pub) {}
        (contains? pub :bytes) {:body (:bytes pub) :body-as :bytes}
        (contains? pub :text) {:body (:text pub)}
        :else {}))

(defn input-stream
  "An InputStream over a response body: the host's own over the bytes
   where it has byte streams, else the layer's over the text."
  [body]
  (if-let [bs (streams)]
    ((:input bs) body)
    {:cohost/kind ::input-stream :state (atom {:text (str body) :pos 0})}))

(defn- uri-string [u] (if (string? u) u (:raw u)))

(defn send-request
  "HttpClient.send: one round trip through Eve's leaf. The handler decides
   the body: ofString the text, ofByteArray / ofInputStream the bytes (on a
   host with byte streams), discarding nil."
  [client req handler]
  (let [as (:as handler)
        want-bytes? (and (contains? #{:bytes :input-stream} as) (streams))
        resp (eve-request (merge {:method (:method req)
                                  :url (uri-string (:uri req))
                                  :headers (wire-headers (:map (:headers req)))}
                                 (body-args (:publisher req))
                                 (when want-bytes? {:as :bytes})))
        body (:body resp)]
    {:cohost/kind ::response
     :status (:status resp)
     :headers (headers (seq (:headers resp)))
     :body (case as
             :string body
             :bytes body
             :input-stream (input-stream body)
             :discard nil
             body)
     :request req
     :uri (:uri req)}))

;; ---------------------------------------------------------------------------
;; java.net.URL.openConnection: HttpURLConnection

(def reason-phrases
  {200 "OK" 201 "Created" 202 "Accepted" 204 "No Content" 206 "Partial Content"
   301 "Moved Permanently" 302 "Found" 303 "See Other" 304 "Not Modified" 307 "Temporary Redirect" 308 "Permanent Redirect"
   400 "Bad Request" 401 "Unauthorized" 403 "Forbidden" 404 "Not Found" 405 "Method Not Allowed" 409 "Conflict"
   410 "Gone" 415 "Unsupported Media Type" 422 "Unprocessable Entity" 429 "Too Many Requests"
   500 "Internal Server Error" 501 "Not Implemented" 502 "Bad Gateway" 503 "Service Unavailable" 504 "Gateway Timeout"})

(defn open-connection [url]
  (when-not (contains? #{"http" "https"} (:scheme url))
    (throw (host/ex* 'java.lang.UnsupportedOperationException
                     (str "cohost.http: URL.openConnection for protocol " (:scheme url)))))
  {:cohost/kind ::connection
   :url url
   :state (atom {:method "GET" :props [] :do-output false :out nil :response nil :opts {}})})

(defn conn-state [c] @(:state c))
(defn conn-set! [c k v] (swap! (:state c) assoc k v) nil)
(defn conn-add-prop! [c k v] (swap! (:state c) update :props conj [k v]) nil)
(defn conn-set-prop! [c k v]
  (swap! (:state c) update :props
         (fn [ps] (conj (vec (remove #(= (lc (first %)) (lc k)) ps)) [k v])))
  nil)
(defn conn-get-prop [c k]
  (some (fn [[pk v]] (when (= (lc pk) (lc k)) v)) (:props (conn-state c))))

(defn conn-set-method! [c m]
  (let [m (str m)]
    (when-not (contains? #{"GET" "POST" "HEAD" "OPTIONS" "PUT" "DELETE" "TRACE"} m)
      (throw (host/ex* 'java.net.ProtocolException (str "Invalid HTTP method: " m))))
    (when (:response (conn-state c))
      (throw (host/ex* 'java.net.ProtocolException "Can't reset method: already connected")))
    (conn-set! c :method m)))

(defn conn-output-stream [c]
  (let [st (conn-state c)]
    (when-not (:do-output st)
      (throw (host/ex* 'java.net.ProtocolException "cannot write to a URLConnection if doOutput=false - call setDoOutput(true)")))
    (or (:out st)
        (let [bs (streams)
              o (if bs ((:output bs)) (atom ""))]
          ;; the JDK turns a GET with a body into a POST
          (swap! (:state c) (fn [s] (cond-> (assoc s :out o) (= "GET" (:method s)) (assoc :method "POST"))))
          o))))

(defn conn-response
  "The connection's response, fetched through Eve's leaf on first ask (the
   JDK connects at getResponseCode / getInputStream / getHeaderField)."
  [c]
  (or (:response (conn-state c))
      (let [st (conn-state c)
            bs (streams)
            out (:out st)
            body (cond (nil? out) nil
                       bs {:body ((:output-bytes bs) out) :body-as :bytes}
                       :else {:body @out})
            resp (eve-request (merge {:method (:method st)
                                      :url (:raw (:url c))
                                      :headers (wire-headers (header-map (:props st)))}
                                     body
                                     (when bs {:as :bytes})))
            r {:status (:status resp) :headers (header-map (seq (:headers resp))) :body (:body resp)}]
        (swap! (:state c) assoc :response r)
        r)))

(defn conn-input-stream [c]
  (let [r (conn-response c)]
    (if (>= (:status r) 400)
      (throw (host/ex* (if (= 404 (:status r)) 'java.io.FileNotFoundException 'java.io.IOException)
                       (if (= 404 (:status r))
                         (:raw (:url c))
                         (str "Server returned HTTP response code: " (:status r) " for URL: " (:raw (:url c))))))
      (input-stream (:body r)))))

(defn conn-error-stream [c]
  (let [r (conn-response c)]
    (when (>= (:status r) 400) (input-stream (:body r)))))

(defn conn-header-field [c nm]
  (when (some? nm) (first (get (:headers (conn-response c)) (lc nm)))))

;; a layer InputStream (a host without byte streams of its own): text
(defn stream-text [s] (let [{:keys [text pos]} @(:state s)] (subs text pos)))
(defn stream-drain! [s] (let [t (stream-text s)] (swap! (:state s) assoc :pos (count (:text @(:state s)))) t))

(defn open-stream
  "URL.openStream: openConnection().getInputStream()."
  [url]
  (conn-input-stream (open-connection url)))

(defn slurp-value
  "The text of a layer value slurp is asked for: a URL's or URI's body
   (fetched through Eve's leaf), an HttpURLConnection's, or a layer
   InputStream's rest."
  [x]
  (case (kind-of x)
    ::url (let [r (conn-response (open-connection x))] (if (string? (:body r)) (:body r) nil))
    ::uri (slurp-value (uri->url x))
    ::input-stream (stream-drain! x)
    nil))

(ns cohost.net
    "The network door to a shared domain, hosted in cohost: a gateway that
   serves a domain's atoms to the members that reach it over the network
   (a browser's workers above all), the presigned object store its blobs
   travel by, and the HTTP server both are served on. The machinery is
   Eve's network layer; this surface is the clojure-shaped delivery of
   it, the way cohost.pool delivers Eve's domains: the Eve names stay on
   this side of the wall."
    (:require [eve.http-server :as hs]
              [eve.net.bytes :as b]
              [eve.net.gateway :as gw]
              [eve.thread.net :as tn]))

(defn serve-http!
      "Serve handler on 127.0.0.1:port with :threads request threads:
   {:port :stop ...} -- call (:stop it) to stop. A request is
   {:method :url :body :body-b64 ...}; an answer {:status :headers
   :body}, its bytes as {:body <base64> :body-as :b64} when they are
   not text."
      [opts handler]
      (hs/serve! opts handler))

(defn gateway
      "A gateway over domain (a cohost.pool domain): {:handle (fn [req]
   resp) ...} serving the domain's atoms on the broker routes. opts:
   :endpoint (its own base URL, as clients reach it), :domain-id,
   :api-key, :presign (see hmac-presigner), :platform ({:request f} --
   how it reaches its store), :deny (fn [name old new] reason-or-nil;
   hello-over-disk-only is the standing one)."
      [domain opts]
      (gw/gateway domain opts))

(defn hmac-presigner
      "The presigner a gateway's :presign takes for an HMAC-checked store:
   (hmac-presigner {:url store-url :secret s :now-ms clock-fn}) answers
   (fn [key mode ttl] url)."
      [opts]
      (gw/hmac-presigner opts))

(defn hmac-ok?
      "Is sig the valid signature of key for mode (\"read\"/\"write\"/\"head\")
   expiring at exp (seconds), under secret, at now-seconds?"
      [secret k mode exp sig now-seconds]
      (gw/hmac-ok? secret k mode exp sig now-seconds))

(def hello-over-disk-only
     "A gateway's :deny: a runtime's hello -- its proof that it reached
   the domain by its path -- may not be written over the network; every
   other move passes."
     tn/hello-over-disk-only)

(defn bytes->b64
      "Standard base64 (padded) of a byte sequence."
      [bs]
      (b/to-b64 bs))

(defn b64->bytes
      "The bytes a base64 string names."
      [s]
      (b/from-b64 s))

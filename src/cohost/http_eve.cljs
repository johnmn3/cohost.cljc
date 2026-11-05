(ns cohost.http-eve
  "ClojureScript-seat hosting for cohost.http (MEMORY-PLAN.md, unit 9), the
   counterpart of cohost/http_eve.clj: Eve's own HTTP layer for Node --
   eve.http over Node's fetch on a worker thread, the caller parked on
   Atomics.wait (synchronous, as every host's eve.http is), and
   eve.http-server over Node's http module -- installed as cohost.http's
   leaves. Loaded only by a build that puts Eve's node adapters on its
   source path (eve/adapters/http/node/src, eve/adapters/http-server/node/src);
   cohost's own cljs builds never see it.

   Bodies stay text on this seat (the adapter gives no :byte-streams):
   a URL's InputStream is cohost.http's own stream value, and the boot's
   http fill gives the core the slurp this host's core lacks."
  (:require [cohost.adapter :as adapter]
            [cohost.http :as http]
            [eve.http :as eve-http]
            [eve.http-server :as eve-http-server]))

(defrecord Locator [scheme user-info host port path query fragment raw]
  ;; a URL / URI is its text to str, as java.net's are
  Object
  (toString [_] (str raw)))

(defn install!
  "Install Eve's http leaves over the current host adapter."
  []
  (adapter/install! (assoc (adapter/current)
                           :http-request eve-http/request
                           :http-serve eve-http-server/serve!
                           :http-value map->Locator))
  (http/available?))

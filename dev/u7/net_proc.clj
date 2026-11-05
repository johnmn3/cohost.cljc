;; Eve-in-cohost unit 7: the net kind across processes, Eve's own local shape
;; (network-atoms README "Over sockets"): an object store and a gateway, each
;; served by eve.http-server over a socket; the gateway is a member of a disk
;; domain (the network atom contains its disk tier: the gateway's domain).
;;   dev/u7/clj-eve.sh dev/u7/net_proc.clj serve  <dir> <seconds>  ; store + gateway, prints the endpoint
;;   dev/u7/clj-eve.sh dev/u7/net_proc.clj client <dir> <endpoint> <n>
;;                                       ; a cohost layer atom, kind :net by meta, n swaps, prints
;;   dev/u7/clj-eve.sh dev/u7/net_proc.clj disk   <dir> <n>        ; a cohost :disk atom of the
;;                                       ; gateway's domain (same atom), n swaps, prints
(require 'cohost.hosts.auto)
(require '[clojure.string :as str]
         '[cohost.atom :as al]
         '[cohost.atom-eve :as hosting]
         '[eve.atom :as ea]
         '[eve.net.gateway :as gw]
         '[eve.net.platform]
         '[eve.process :as proc]
         '[eve.http-server :as http])

(hosting/install!)
(al/set-fill! true)

(defn mem-store [secret]
  (let [blobs (atom {})]
    (fn [{:keys [method url body]}]
      (let [[_ k q] (re-find #"/blobs/([^?]+)\?(.*)" url)
            pm (into {} (map #(vec (str/split % #"=" 2))) (str/split q #"&"))
            mode ({"PUT" "write" "GET" "read" "HEAD" "head"} method)
            ok? (and mode (= mode (pm "mode"))
                     (gw/hmac-ok? secret k mode (parse-long (pm "exp")) (pm "sig") (quot (proc/now-ms) 1000)))]
        (cond (not ok?) {:status 403 :headers {} :body ""}
              (= method "PUT") (do (swap! blobs assoc k body) {:status 200 :headers {} :body ""})
              (= method "HEAD") {:status (if (contains? @blobs k) 200 404) :headers {} :body ""}
              :else (if-let [b (get @blobs k)] {:status 200 :headers {} :body b} {:status 404 :headers {} :body ""}))))))

(def nm "app/shared")
(def init {:count 0 :net 0 :disk 0})
(defn bump [m k] (assoc m k (inc (get m k 0))))
(defn plain [v] (into {} v))

(let [[mode dir x y] *command-line-args*
      db (str dir "/gwdb")]
  (case mode
    "serve"
    (let [store (mem-store "store-secret")
          store-srv (http/serve! {:port 0} store)
          store-url (str "http://127.0.0.1:" (:port store-srv) "/blobs/")
          d (ea/persistent-atom-domain db)
          _ (ea/lookup-or-create-mmap-atom! d nm init)
          g (gw/gateway d {:endpoint "unused" :domain-id "d1" :api-key "k"
                           :presign (gw/hmac-presigner {:url store-url :secret "store-secret" :now-ms proc/now-ms})
                           :platform (eve.net.platform/platform)})
          gw-srv (http/serve! {:port 0} (:handle g))]
      (spit (str dir "/endpoint") (str "http://127.0.0.1:" (:port gw-srv)))
      (println "SERVING" (str "http://127.0.0.1:" (:port gw-srv))) (flush)
      (Thread/sleep (* 1000 (parse-long x)))
      (println "GATEWAY-STATS" (pr-str (gw/stats g)))
      ((:stop gw-srv)) ((:stop store-srv))
      (ea/close-atom-domain! d))
    "client"
    (let [a (al/make-atom init {:meta {:eve/kind :net :eve/endpoint x :eve/domain-id "d1"
                                       :eve/name nm :eve/api-key "k"}})
          n (parse-long y)]
      (dotimes [_ n] (al/swap-atom! a (fn [m] (bump (bump m :count) :net))))
      (println (str "RESULT " (pr-str {:side :net-client :kind (al/kind a) :value (plain (al/deref-atom a))}))))
    "disk"
    (let [a (al/make-atom init {:meta {:eve/kind :disk :eve/path db :eve/name nm}})
          n (parse-long x)]
      (dotimes [_ n] (al/swap-atom! a (fn [m] (bump (bump m :count) :disk))))
      (println (str "RESULT " (pr-str {:side :disk-member :kind (al/kind a) :value (plain (al/deref-atom a))
                                       :digests (hosting/root-digests a)})))
      (hosting/close-disk-domain! db))))
(shutdown-agents)
(System/exit 0)

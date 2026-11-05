(ns u8-worker-probe
  "Unit 8 de-risk probe: one Eve in-process (SAB) atom domain shared by the
   main realm and a worker realm started through Eve's own Node thread
   adapter (eve.thread-host fat-kernel mesh). The worker joins the SAME
   domain from the SharedArrayBuffers handed to it (Eve's slab handover:
   process-slab/init-worker-slabs! + the domain root/reader-map SABs) and
   both realms swap one atom concurrently; the final value must be exact."
  (:require ["node:worker_threads" :as wt]
            [eve.atom :as a]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.wasm :as wasm]
            [eve.process-slab :as ps]
            [eve.region :as region]
            [eve.thread-host :as th]))

(def N 2000)

(defn- join-domain [^js e]
  (ps/init-worker-slabs! (.-slabs e) (.-root e) (.-overflow e))
  (let [root-r (region/js-sab-region (.-droot e))
        rmap-r (region/js-sab-region (.-rmap e))
        sio (alloc/heap-domain-sio! {})
        slot (#'a/mmap-claim-slot! root-r)
        ds (#'a/make-domain-state root-r rmap-r sio nil slot nil)]
    (a/MmapAtomDomain. ds (atom {}))))

(defn worker-entry [boot]
  (let [d (join-domain (wt/getEnvironmentData "cohost.eve"))
        ea (a/lookup-or-create-mmap-atom! d "u8/counter" nil)]
    (println "U8-PROBE worker" (:self boot) "sees" (pr-str @ea))
    (dotimes [_ N] (swap! ea update :n inc))
    (swap! ea assoc :worker-wrote {:made-in-worker (:self boot) :v [1 2 3]})
    (println "U8-PROBE worker done")))

(defn -main [& _]
  (when-not (th/run-thread!)
    (ps/init!)
    (let [d (a/atom-domain {})
          ds (.-domain-state d)
          ea (a/lookup-or-create-mmap-atom! d "u8/counter" {:n 0})]
      (wt/setEnvironmentData "cohost.eve"
                             #js {:slabs (ps/get-all-slab-sabs) :root (ps/get-root-sab)
                                  :overflow (.-buffer ^js (:u8 (wasm/get-slab-instance 6)))
                                  :droot (.-sab ^js (:root-r ds)) :rmap (.-sab ^js (:rmap-r ds))})
      (let [t0 (js/Date.now)
            ids (th/start-threads! 1 'u8-worker-probe/worker-entry {} {})]
        (dotimes [_ N] (swap! ea update :n inc))
        (let [m (th/receive! 120000)]
          (println "U8-PROBE main got" (pr-str m) "after" (- (js/Date.now) t0) "ms")
          (println "U8-PROBE final" (pr-str @ea))
          (println "U8-PROBE RESULT" (if (= (* 2 N) (:n @ea)) "PASS" "FAIL")))))))

(set! *main-cli-fn* -main)

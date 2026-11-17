;; CLJS parity, node interop: a :node realm with a require root (the
;; seat option :require-root) does real node work -- npm packages and
;; their native code, resolved from the root's node_modules as a node
;; process started there would resolve them. A realm without the
;; option stays bare. Async results are read back through realm vars:
;; work crosses submit-form as data, and a promise's settled value is
;; the realm's own state, read by a later form.
;;
;;   dev/cljs_parity/run.sh node_interop.clj <fixture-app-dir>
;;
;; The fixture dir carries package.json + node_modules with sharp
;; and music-metadata (npm install). Checks:
;;   require-in-realm   (exists? js/require) under the rooted seat
;;   text-encoder       js/TextEncoder constructs in the realm (the
;;                      platform globals the host lends its context)
;;   process-cwd        the realm process's cwd is the root
;;   console-survives   a realm console.log (stderr now) leaves the
;;                      protocol stream intact for the next eval
;;   timers-live        a realm setTimeout fires while the seat is
;;                      idle between evals (the async frame pump)
;;   sharp-call         sharp creates a PNG; its metadata reads back
;;                      format and dimensions, through the promise
;;   music-metadata-call parseBuffer over a 0.1 s WAV reads back its
;;                      container and duration, through the promise
;;   bare-realm         a seat without :require-root has no require
(require 'cohost.hosts.auto)
(require '[cohost.thread :as thread]
         '[cohost.cljs-pool :as pool])

(def fixture (first *command-line-args*))
(when-not fixture
  (println "usage: node_interop.clj <fixture-app-dir>")
  (System/exit 2))
(println "FIXTURE" fixture)

(def ex (pool/executor 1 {:seat :node :require-root fixture}))

(defn sub [ns-sym form & args]
  (thread/deref-future (apply thread/submit-form ex ns-sym form args)))

(def results (atom []))
(defn check [label got expected]
  (let [pass (= got expected)]
    (swap! results conj pass)
    (println (if pass "PASS" "FAIL") label
             (when-not pass (str " -- expected=" (pr-str expected) " got=" (pr-str got))))))

;; a 0.1 s WAV: 44-byte PCM header (8000 Hz mono 8-bit) + 800 samples
(def wav-bytes
  (let [n 800
        le32 (fn [v] [(bit-and v 0xff) (bit-and (bit-shift-right v 8) 0xff)
                      (bit-and (bit-shift-right v 16) 0xff) (bit-and (bit-shift-right v 24) 0xff)])
        le16 (fn [v] [(bit-and v 0xff) (bit-and (bit-shift-right v 8) 0xff)])
        ascii (fn [s] (mapv int s))]
    (vec (concat (ascii "RIFF") (le32 (+ 36 n)) (ascii "WAVE")
                 (ascii "fmt ") (le32 16) (le16 1) (le16 1)
                 (le32 8000) (le32 8000) (le16 1) (le16 8)
                 (ascii "data") (le32 n)
                 (repeat n 128)))))

(check "require-in-realm"
       (sub 'cljs.user '(fn [] (exists? js/require)))
       true)

;; the platform globals a bare vm context lacks (TextEncoder is the
;; one eve.encoding constructs at namespace load): the host lends
;; them, so construction works in the realm
(check "text-encoder"
       (sub 'cljs.user '(fn [] (.-length (.encode (js/TextEncoder.) "héllo"))))
       6)

(check "process-cwd"
       (sub 'cljs.user '(fn [] (.cwd js/process)))
       fixture)

(check "console-survives"
       (do (sub 'cljs.user '(fn [] (do (js/console.log "interop-noise") 7)))
           (sub 'cljs.user '(fn [] 8)))
       8)

(sub 'cljs.user '(fn [] (do (def interop-timer :pending)
                            (js/setTimeout (fn [] (def interop-timer :fired)) 400)
                            :kicked)))
(Thread/sleep 1500)
(check "timers-live"
       (sub 'cljs.user '(fn [] interop-timer))
       :fired)

(sub 'cljs.user
     '(fn [] (do (def interop-sharp :pending)
                 (let [sharp (js/require "sharp")]
                   (-> (sharp (clj->js {:create {:width 2 :height 3 :channels 4
                                                 :background {:r 10 :g 20 :b 30 :alpha 1}}}))
                       (.png)
                       (.toBuffer)
                       (.then (fn [buf] (.metadata (sharp buf))))
                       (.then (fn [m] (def interop-sharp
                                        (str (.-format m) " " (.-width m) "x" (.-height m)))))
                       (.catch (fn [e] (def interop-sharp (str "ERR " (.-message e)))))))
                 :kicked)))

(sub 'cljs.user
     '(fn [bytes]
        (do (def interop-mm :pending)
            (let [mm (js/require "music-metadata")]
              (-> (.parseBuffer mm (js/Buffer.from (clj->js bytes))
                                (clj->js {:mimeType "audio/wav"}))
                  (.then (fn [md] (def interop-mm
                                     (str (.-container (.-format md))
                                          " " (.-duration (.-format md))))))
                  (.catch (fn [e] (def interop-mm (str "ERR " (.-message e)))))))
            :kicked))
     wav-bytes)

(Thread/sleep 4000)
(check "sharp-call" (sub 'cljs.user '(fn [] interop-sharp)) "png 2x3")
(check "music-metadata-call" (sub 'cljs.user '(fn [] interop-mm)) "WAVE 0.1")

(def bare (pool/executor 1 {:seat :node}))
(check "bare-realm"
       (thread/deref-future
        (thread/submit-form bare 'cljs.user '(fn [] (exists? js/require))))
       false)
(thread/shutdown-executor! bare)
(thread/shutdown-executor! ex)

(let [rs @results]
  (println "NODE-INTEROP:" (count (filter identity rs)) "/" (count rs) "passed")
  (System/exit (if (every? identity rs) 0 1)))

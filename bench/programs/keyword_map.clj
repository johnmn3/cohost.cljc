;; n get+assoc pairs on a 16-key map with long keyword names (jank-14's measure:
;; every get and assoc hashes the keyword). The checksum stays under 2^20, so no
;; host's integer width changes the result.
(def ks [:keyword-map-sentinel-key-alpha :keyword-map-sentinel-key-bravo
         :keyword-map-sentinel-key-charlie :keyword-map-sentinel-key-delta
         :keyword-map-sentinel-key-echo :keyword-map-sentinel-key-foxtrot
         :keyword-map-sentinel-key-golf :keyword-map-sentinel-key-hotel
         :keyword-map-sentinel-key-india :keyword-map-sentinel-key-juliett
         :keyword-map-sentinel-key-kilo :keyword-map-sentinel-key-lima
         :keyword-map-sentinel-key-mike :keyword-map-sentinel-key-november
         :keyword-map-sentinel-key-oscar :keyword-map-sentinel-key-papa])

(defn -run [s]
  (let [n (parse-long s)]
    (loop [i 0 m (zipmap ks (range 16)) acc 0]
      (if (= i n)
        (+ acc (get m (nth ks 0)))
        (let [k (nth ks (mod i 16))
              v (get m k)]
          (recur (inc i) (assoc m k (mod (+ v 7) 1000)) (mod (+ acc v) 1000003)))))))

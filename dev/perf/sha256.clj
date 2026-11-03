;; sha256 in-process, so cache keys need no coreutils: macOS has no GNU `xargs -d`,
;; Windows no `sha256sum`. The outputs are byte for byte the ones the shell pipelines
;; gave, so stamps written by the old pipelines stay valid:
;;   (sha256-hex s)          = `printf %s s | sha256sum`, the hex field
;;   (sha256sum-listing fs)  = `printf '%s\n' fs... | xargs -d '\n' sha256sum`
;;                             (one "<hex>  <path>\n" line per file)
(ns cohost.dev.sha256)

(defn- hex [^bytes b]
  (let [sb (StringBuilder.)]
    (doseq [x b] (.append sb (format "%02x" (bit-and x 0xff))))
    (str sb)))

(defn sha256-hex
  "Hex sha256 of a string's UTF-8 bytes."
  [^String s]
  (hex (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))))

(defn sha256-file
  "Hex sha256 of a file's bytes."
  [path]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (java.io.FileInputStream. (str path))]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n) (.update d buf 0 n) (recur)))))
    (hex (.digest d))))

(defn sha256sum-listing
  "sha256sum's output for these files, in this order."
  [files]
  (apply str (map (fn [f] (str (sha256-file f) "  " f "\n")) files)))

;; sha256 in-process, so cache keys need no coreutils: macOS has no GNU `xargs -d`,
;; Windows no `sha256sum`. The outputs are byte for byte the ones the shell pipelines
;; gave, so stamps written by the old pipelines stay valid:
;;   (sha256-hex s)          = `printf %s s | sha256sum`, the hex field
;;   (sha256sum-listing fs)  = `printf '%s\n' fs... | xargs -d '\n' sha256sum`
;;                             (one "<hex>  <path>\n" line per file)
;;
;; The digest itself is resolved at runtime, per host. The two arms
;; name mutually exclusive host types (java.security.MessageDigest
;; on the JVM-shaped hosts -- under Jolt, its crypto library
;; provides that class -- and System.Security.Cryptography.SHA256
;; on ClojureCLR); neither type name can be compiled by the other
;; host's compiler, and .clj files take no reader conditionals, so
;; each arm lives in a string and is compiled by eval only on the
;; host that understands it. Resolution happens once, on first
;; use. The outputs do not depend on which arm ran: SHA-256 is
;; SHA-256 (the CLR arm's digest of "abc" is ba7816bf..., the
;; textbook value).
(ns cohost.dev.sha256)

(defn- try-eval
  "The value of the form read from s, or nil when this host cannot
  compile it (a missing type surfaces as a compile error)."
  [s]
  ;; Exception, not Throwable: ClojureCLR resolves no Throwable
  ;; classname, and every failure this guards (read, compile,
  ;; missing type) is an Exception on both hosts.
  (try (eval (read-string s)) (catch Exception _ nil)))

(def ^:private digest-string
  ;; A fn of one string -> its sha256 bytes (32), per host.
  (delay
    (or (try-eval "(fn [s] (.digest (java.security.MessageDigest/getInstance \"SHA-256\") (.getBytes s \"UTF-8\")))")
        (try-eval "(fn [s] (.ComputeHash (System.Security.Cryptography.SHA256/Create) (.GetBytes System.Text.Encoding/UTF8 s)))"))))

(def ^:private digest-file
  ;; A fn of one path -> the file's sha256 bytes (32), per host.
  ;; The JVM arm streams (as this file always did); the CLR arm
  ;; reads the file whole -- the hashed inputs are source files.
  (delay
    (or (try-eval "(fn [path] (let [d (java.security.MessageDigest/getInstance \"SHA-256\") buf (byte-array 65536)] (with-open [in (java.io.FileInputStream. (str path))] (loop [] (let [n (.read in buf)] (when (pos? n) (.update d buf 0 n) (recur))))) (.digest d)))")
        (try-eval "(fn [path] (.ComputeHash (System.Security.Cryptography.SHA256/Create) (System.IO.File/ReadAllBytes (str path))))"))))

(defn- arm [d]
  (or @d (throw (ex-info "cohost.dev.sha256: no digest arm compiled on this host" {}))))

(defn- hex [b]
  ;; String accumulation, not StringBuilder: .NET spells the
  ;; member Append, and one implementation serves both hosts.
  (apply str (map (fn [x] (format "%02x" (bit-and x 0xff))) b)))

(defn sha256-hex
  "Hex sha256 of a string's UTF-8 bytes."
  [s]
  (hex ((arm digest-string) s)))

(defn sha256-file
  "Hex sha256 of a file's bytes."
  [path]
  (hex ((arm digest-file) path)))

(defn sha256sum-listing
  "sha256sum's output for these files, in this order."
  [files]
  (apply str (map (fn [f] (str (sha256-file f) "  " f "\n")) files)))

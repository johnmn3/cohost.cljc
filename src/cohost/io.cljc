(ns cohost.io
  "java.io's file and stream surface for hosts with no java.io of their
   own (the JVM and jolt pass interop through to the real classes):
   File, the Reader/Writer and InputStream/OutputStream families, over
   the host adapter's file capabilities (:slurp / :spit, whole-file
   text) and in-memory state.

   Values of the layer are records, one type per class stock
   clojure.java.io's protocols dispatch on (File, InputStream,
   BufferedInputStream, OutputStream, BufferedOutputStream, Reader,
   BufferedReader, Writer, BufferedWriter): a host's protocol
   dispatch keys on the value's type, so kinds carried in ONE map
   type could never be told apart by it (measured on let-go).
   Records are the house shape for shim values (cohost.shim's date
   standins): member calls route through the shim, and protocol
   dispatch keys on the record type. The fine-grained kind
   (FileInputStream vs ByteArrayInputStream, StringReader vs
   FileReader) rides in a field, and the shim dispatches the
   classes' members on it. A value with mutable state holds it in
   an atom in a field. Equality is the record's.

   Text is the layer's medium, as in cohost.http: a reader's state is
   {:text :pos}; a byte stream's state is {:bytes :pos} with the bytes
   a vector of ints 0-255; a file stream reads its whole file at open
   (through :slurp, UTF-8) and a file output writes its whole content
   at close/flush (through :spit, UTF-8) -- the adapter's file caps
   are whole-file, so the layer is too. Charsets other than UTF-8 are
   refused by name at the wrapper constructors, in the shim."
  (:require [cohost.adapter :as host]))

;; ---------------------------------------------------------------------------
;; kinds and class tokens

(def kinds
  {::file                 'java.io.File
   ::input-stream         'java.io.InputStream
   ::buffered-input-stream 'java.io.BufferedInputStream
   ::file-input-stream    'java.io.FileInputStream
   ::byte-input-stream    'java.io.ByteArrayInputStream
   ::output-stream        'java.io.OutputStream
   ::buffered-output-stream 'java.io.BufferedOutputStream
   ::file-output-stream   'java.io.FileOutputStream
   ::byte-output-stream   'java.io.ByteArrayOutputStream
   ::reader               'java.io.Reader
   ::buffered-reader      'java.io.BufferedReader
   ::string-reader        'java.io.StringReader
   ::char-array-reader    'java.io.CharArrayReader
   ::input-stream-reader  'java.io.InputStreamReader
   ::file-reader          'java.io.FileReader
   ::pushback-reader      'java.io.PushbackReader
   ::writer               'java.io.Writer
   ::buffered-writer      'java.io.BufferedWriter
   ::string-writer        'java.io.StringWriter
   ::file-writer          'java.io.FileWriter
   ::output-stream-writer 'java.io.OutputStreamWriter})

;; One record type per dispatch class (see the ns docstring). Stream
;; values carry [kind state under]: kind the fine kind, state the
;; state atom, under a wrapped value (a pushback reader's reader;
;; nil elsewhere). File stands alone with [path parent]. The two
;; *Dispatch types are never constructed: they exist so a host's
;; type table can name a type for java.net.URL / java.net.URI in an
;; extend position (the values themselves are cohost.http's, whose
;; record type a host's protocol dispatch cannot tell from a map's).
(defrecord FileValue [path parent])
(defrecord InputStreamValue [kind state under])
(defrecord BufferedInputStreamValue [kind state under])
(defrecord OutputStreamValue [kind state under])
(defrecord BufferedOutputStreamValue [kind state under])
(defrecord ReaderValue [kind state under])
(defrecord BufferedReaderValue [kind state under])
(defrecord WriterValue [kind state under])
(defrecord BufferedWriterValue [kind state under])
(defrecord UrlDispatch [kind state under])
(defrecord UriDispatch [kind state under])

(defn value?
  "True when x is a value of this layer."
  [x]
  (or (instance? FileValue x)
      (instance? InputStreamValue x) (instance? BufferedInputStreamValue x)
      (instance? OutputStreamValue x) (instance? BufferedOutputStreamValue x)
      (instance? ReaderValue x) (instance? BufferedReaderValue x)
      (instance? WriterValue x) (instance? BufferedWriterValue x)))

(defn kind-of [x]
  (cond (instance? FileValue x) ::file
        (value? x) (:cohost/kind x)
        :else nil))

(defn state-of [x] (:state x))
(defn under-of [x] (:under x))
(defn class-token [x] (get kinds (kind-of x)))
(defn is? [k x] (= k (kind-of x)))

(def ^:private ctors
  {::input-stream          (fn [k s u] (->InputStreamValue k s u))
   ::file-input-stream     (fn [k s u] (->InputStreamValue k s u))
   ::byte-input-stream     (fn [k s u] (->InputStreamValue k s u))
   ::buffered-input-stream (fn [k s u] (->BufferedInputStreamValue k s u))
   ::output-stream         (fn [k s u] (->OutputStreamValue k s u))
   ::file-output-stream    (fn [k s u] (->OutputStreamValue k s u))
   ::byte-output-stream    (fn [k s u] (->OutputStreamValue k s u))
   ::buffered-output-stream (fn [k s u] (->BufferedOutputStreamValue k s u))
   ::reader                (fn [k s u] (->ReaderValue k s u))
   ::string-reader         (fn [k s u] (->ReaderValue k s u))
   ::char-array-reader     (fn [k s u] (->ReaderValue k s u))
   ::input-stream-reader   (fn [k s u] (->ReaderValue k s u))
   ::file-reader           (fn [k s u] (->ReaderValue k s u))
   ::buffered-reader       (fn [k s u] (->BufferedReaderValue k s u))
   ::pushback-reader       (fn [k s u] (->BufferedReaderValue k s u))
   ::writer                (fn [k s u] (->WriterValue k s u))
   ::string-writer         (fn [k s u] (->WriterValue k s u))
   ::file-writer           (fn [k s u] (->WriterValue k s u))
   ::output-stream-writer  (fn [k s u] (->WriterValue k s u))
   ::buffered-writer       (fn [k s u] (->BufferedWriterValue k s u))})

(defn value
  "A stream value of fine kind k over state atom s, wrapping u. The
   record also carries :cohost/kind in its extmap: the shim's member
   tables key on that entry, as they do for cohost.http's values."
  ([k s] (value k s nil))
  ([k s u] (assoc ((get ctors k) k s u) :cohost/kind k)))

(defn dispatch-class
  "The record class object standing for a java.io/java.net token in
   a host's extend positions (see the *Dispatch note above)."
  [token]
  (case token
    java.io.File FileValue
    java.io.InputStream InputStreamValue
    java.io.BufferedInputStream BufferedInputStreamValue
    java.io.OutputStream OutputStreamValue
    java.io.BufferedOutputStream BufferedOutputStreamValue
    java.io.Reader ReaderValue
    java.io.BufferedReader BufferedReaderValue
    java.io.Writer WriterValue
    java.io.BufferedWriter BufferedWriterValue
    java.net.URL UrlDispatch
    java.net.URI UriDispatch
    nil))

(def reader-kinds
  #{::reader ::buffered-reader ::string-reader ::char-array-reader
    ::input-stream-reader ::file-reader ::pushback-reader})
(def writer-kinds
  #{::writer ::buffered-writer ::string-writer ::file-writer
    ::output-stream-writer})
(def input-stream-kinds
  #{::input-stream ::buffered-input-stream ::file-input-stream
    ::byte-input-stream})
(def output-stream-kinds
  #{::output-stream ::buffered-output-stream ::file-output-stream
    ::byte-output-stream})

(defn reader-value? [x] (contains? reader-kinds (kind-of x)))
(defn writer-value? [x] (contains? writer-kinds (kind-of x)))
(defn input-stream-value? [x] (contains? input-stream-kinds (kind-of x)))
(defn output-stream-value? [x] (contains? output-stream-kinds (kind-of x)))

;; ---------------------------------------------------------------------------
;; UTF-8 codecs (the layer's one charset; pure, over UTF-16 units)

(defn utf8-encode
  "String -> vector of byte ints (0-255), UTF-8. A lone surrogate is
   encoded as its 3-byte form, as the JVM's modified UTF-8 does for
   unpaired units in some paths; paired surrogates encode the code
   point."
  [s]
  (let [n (count s)]
    (loop [i 0 out (transient [])]
      (if (>= i n)
        (persistent! out)
        (let [c (int (nth s i))]
          (cond
            (< c 0x80)
            (recur (inc i) (conj! out c))
            (< c 0x800)
            (recur (inc i) (-> out (conj! (bit-or 0xC0 (bit-shift-right c 6)))
                               (conj! (bit-or 0x80 (bit-and c 0x3F)))))
            (and (>= c 0xD800) (<= c 0xDBFF) (< (inc i) n)
                 (<= 0xDC00 (int (nth s (inc i))) 0xDFFF))
            (let [cp (+ 0x10000
                        (bit-shift-left (- c 0xD800) 10)
                        (- (int (nth s (inc i))) 0xDC00))]
              (recur (+ i 2) (-> out (conj! (bit-or 0xF0 (bit-shift-right cp 18)))
                                 (conj! (bit-or 0x80 (bit-and (bit-shift-right cp 12) 0x3F)))
                                 (conj! (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F)))
                                 (conj! (bit-or 0x80 (bit-and cp 0x3F))))))
            :else
            (recur (inc i) (-> out (conj! (bit-or 0xE0 (bit-shift-right c 12)))
                               (conj! (bit-or 0x80 (bit-and (bit-shift-right c 6) 0x3F)))
                               (conj! (bit-or 0x80 (bit-and c 0x3F)))))))))))

(defn utf8-decode
  "Vector of byte ints -> string, UTF-8. A malformed byte decodes as
   U+FFFD, as the JVM's decoder does by default."
  [bs]
  (let [n (count bs)]
    (loop [i 0 out []]
      (if (>= i n)
        (apply str (map char out))
        (let [b (nth bs i)]
          (cond
            (< b 0x80)
            (recur (inc i) (conj out b))
            (and (<= 0xC2 b 0xDF) (< (inc i) n))
            (recur (+ i 2) (conj out (bit-or (bit-shift-left (bit-and b 0x1F) 6)
                                             (bit-and (nth bs (inc i)) 0x3F))))
            (and (<= 0xE0 b 0xEF) (< (+ i 2) n))
            (recur (+ i 3) (conj out (bit-or (bit-shift-left (bit-and b 0x0F) 12)
                                             (bit-shift-left (bit-and (nth bs (inc i)) 0x3F) 6)
                                             (bit-and (nth bs (+ i 2)) 0x3F))))
            (and (<= 0xF0 b 0xF4) (< (+ i 3) n))
            (let [cp (bit-or (bit-shift-left (bit-and b 0x07) 18)
                             (bit-shift-left (bit-and (nth bs (inc i)) 0x3F) 12)
                             (bit-shift-left (bit-and (nth bs (+ i 2)) 0x3F) 6)
                             (bit-and (nth bs (+ i 3)) 0x3F))
                  v (- cp 0x10000)]
              (recur (+ i 4) (-> out (conj (bit-or 0xD800 (bit-shift-right v 10)))
                                   (conj (bit-or 0xDC00 (bit-and v 0x3FF))))))
            :else
            (recur (inc i) (conj out 0xFFFD))))))))

;; ---------------------------------------------------------------------------
;; File

(defn file
  "A java.io.File over a path string."
  ([path] (assoc (->FileValue (str path) nil) :cohost/kind ::file))
  ([parent child] (assoc (->FileValue (str (str parent) "/" (str child)) (str parent))
                         :cohost/kind ::file)))

(defn file-path [f] (:path f))
(defn file-parent [f] (:parent f))

(defn file-absolute?
  "java.io.File/isAbsolute for a path string: a leading separator, or a
   windows drive prefix."
  [path]
  (boolean
    (and path (pos? (count path))
         (or (= "/" (subs path 0 1))
             (and (>= (count path) 3)
                  (let [c (int (nth path 0))]
                    (or (<= (int \a) c (int \z)) (<= (int \A) c (int \Z))))
                  (= \: (nth path 1))
                  (contains? #{\/ \\} (nth path 2)))))))

(defn file-exists?
  "Existence is the adapter's answer: :slurp succeeds on a file that
   is there. A directory has no :slurp answer either -- the layer
   cannot tell a directory from an absent path, and says so: callers
   that need isDirectory get the named refusal in the shim, not a
   guessed boolean here."
  [f]
  (boolean (host/try-all (fn [] (host/slurp-file (file-path f)) :present)
                         (fn [_] nil))))

(defn file-text
  "The file's whole content as text (the :slurp cap), or throws the
   cap's error."
  [f]
  (host/slurp-file (file-path f)))

(defn write-file-text!
  "Write text as the file's whole content (the :spit cap)."
  [f text]
  (host/spit-file (file-path f) text))

;; ---------------------------------------------------------------------------
;; input streams (state {:bytes [ints] :pos n :closed? bool})

(defn input-stream-over-bytes
  [kind bs]
  (value kind (atom {:bytes (vec bs) :pos 0 :closed? false})))

(defn file-input-stream
  "The file's bytes, read whole at open through :slurp + UTF-8."
  [f]
  (input-stream-over-bytes ::file-input-stream (utf8-encode (file-text f))))

(defn byte-input-stream [bs]
  (input-stream-over-bytes ::byte-input-stream bs))

(defn buffered-input-stream
  "Buffering is a no-op over in-memory state; the kind (and so the
   class token) is the buffered one's."
  [in]
  (if (input-stream-value? in)
    (value ::buffered-input-stream (state-of in))
    in))

(defn read-byte!
  "InputStream.read: the next byte 0-255, or -1 at the end."
  [in]
  (let [st @(state-of in)]
    (when (:closed? st)
      (throw (host/ex* 'java.io.IOException "Stream closed")))
    (if (< (:pos st) (count (:bytes st)))
      (let [b (nth (:bytes st) (:pos st))]
        (swap! (state-of in) update :pos inc)
        b)
      -1)))

(defn read-bytes!
  "InputStream.read(byte[], off, len) over a vector of ints the shim
   turns into the caller's array: returns [n bytes-vector-slice]."
  [in len]
  (let [st @(state-of in)]
    (when (:closed? st)
      (throw (host/ex* 'java.io.IOException "Stream closed")))
    (let [avail (- (count (:bytes st)) (:pos st))
          n (min len avail)]
      (if (and (zero? n) (pos? len))
        [-1 []]
        (do (swap! (state-of in) update :pos + n)
            [n (subvec (:bytes st) (:pos st) (+ (:pos st) n))])))))

(defn available [in]
  (let [st @(state-of in)] (- (count (:bytes st)) (:pos st))))

(defn stream-bytes
  "All of a byte stream's remaining content as a vector of ints
   (ByteArrayOutputStream.toByteArray's source, the reader wrap)."
  [in]
  (let [st @(state-of in)] (subvec (:bytes st) (:pos st))))

(defn stream-text
  "A byte stream's remaining content decoded as UTF-8 text."
  [in]
  (utf8-decode (stream-bytes in)))

;; ---------------------------------------------------------------------------
;; output streams (state {:bytes [ints] :pos n :closed? bool :file f?})

(defn output-stream-over
  [kind file]
  (value kind (atom {:bytes [] :closed? false :file file})))

(defn byte-output-stream []
  (output-stream-over ::byte-output-stream nil))

(defn file-output-stream
  [f append?]
  (let [v (output-stream-over ::file-output-stream f)]
    (when append?
      (host/try-all (fn [] (swap! (state-of v) assoc :bytes (utf8-encode (file-text f))))))
    v))

(defn buffered-output-stream [out]
  (if (output-stream-value? out)
    (value ::buffered-output-stream (state-of out))
    out))

(defn append-byte! [out b]
  (let [st @(state-of out)]
    (when (:closed? st)
      (throw (host/ex* 'java.io.IOException "Stream closed")))
    (swap! (state-of out) update :bytes conj (bit-and b 0xFF))
    nil))

(defn append-bytes! [out bs]
  (doseq [b bs] (append-byte! out b))
  nil)

(defn output-bytes [out] (:bytes @(state-of out)))

(defn flush-output!
  "A file output stream writes its accumulated bytes, decoded as
   UTF-8 text, through :spit. Other streams hold their bytes in
   state; flushing them is a no-op."
  [out]
  (let [st @(state-of out)]
    (when-let [f (:file st)]
      (write-file-text! f (utf8-decode (:bytes st)))))
  nil)

(defn close-output! [out]
  (when-not (:closed? @(state-of out))
    (flush-output! out)
    (swap! (state-of out) assoc :closed? true))
  nil)

;; ---------------------------------------------------------------------------
;; readers (state {:text s :pos n :closed? bool}, pushback adds :buf)

(defn reader-over-text
  [kind text]
  (value kind (atom {:text (str text) :pos 0 :closed? false})))

(defn string-reader [s] (reader-over-text ::string-reader s))

(defn char-array-reader [chars]
  (reader-over-text ::char-array-reader (apply str chars)))

(defn input-stream-reader
  "A Reader over a byte stream's remaining content, decoded whole
   (UTF-8; the shim refuses another charset by name before here)."
  [in]
  (reader-over-text ::input-stream-reader (stream-text in)))

(defn file-reader
  "A FileReader over a file's whole text."
  [f]
  (reader-over-text ::file-reader (file-text f)))

(defn close-input! [in]
  (swap! (state-of in) assoc :closed? true)
  nil)

(defn buffered-reader [r]
  (if (and (reader-value? r) (not (is? ::pushback-reader r)))
    (value ::buffered-reader (state-of r))
    r))

(defn pushback-reader [r]
  (if (reader-value? r)
    (value ::pushback-reader (atom {:buf "" :closed? false}) r)
    r))

(defn- reader-text+pos
  "The effective [text pos] of a reader: for a pushback reader, the
   unread buffer followed by the underlying reader's UNREAD text,
   indexed from 0 (reader-advance! drains the buffer first)."
  [r]
  (if (is? ::pushback-reader r)
    (let [buf (:buf @(state-of r))
          ust @(state-of (under-of r))]
      [(str buf (subs (:text ust) (:pos ust))) 0])
    (let [st @(state-of r)] [(:text st) (:pos st)])))

(defn- reader-advance!
  "Move a reader n chars forward (pushback buffer drains first)."
  [r n]
  (if (is? ::pushback-reader r)
    (let [buf (:buf @(state-of r))
          from-buf (min n (count buf))]
      (swap! (state-of r) assoc :buf (subs buf from-buf))
      (when (> n from-buf)
        (swap! (state-of (under-of r)) update :pos + (- n from-buf))))
    (swap! (state-of r) update :pos + n))
  nil)

(defn reader-closed? [r]
  (if (is? ::pushback-reader r)
    (:closed? @(state-of r))
    (:closed? @(state-of r))))

(defn read-char!
  "Reader.read: the next char (a Character), or -1 at the end, as
   cohost.http's readers answer."
  [r]
  (when (reader-closed? r)
    (throw (host/ex* 'java.io.IOException "Stream closed")))
  (let [[text pos] (reader-text+pos r)]
    (if (< pos (count text))
      (do (reader-advance! r 1) (nth text pos))
      -1)))

(defn read-chars!
  "Reader.read(char[], off, len)'s content: [n string]."
  [r len]
  (when (reader-closed? r)
    (throw (host/ex* 'java.io.IOException "Stream closed")))
  (let [[text pos] (reader-text+pos r)
        avail (- (count text) pos)
        n (min len avail)]
    (if (and (zero? n) (pos? len))
      [-1 ""]
      (do (reader-advance! r n) [n (subs text pos (+ pos n))]))))

(defn read-line!
  "BufferedReader.readLine: the next line without its terminator
   (\\n, \\r\\n or \\r), nil at the end."
  [r]
  (when (reader-closed? r)
    (throw (host/ex* 'java.io.IOException "Stream closed")))
  (let [[text pos] (reader-text+pos r)
        n (count text)]
    (when (< pos n)
      (let [i (loop [i pos]
                (if (or (>= i n) (contains? #{"\n" "\r"} (subs text i (inc i))))
                  i (recur (inc i))))
            adv (cond (>= i n) (- n pos)
                      (and (= "\r" (subs text i (inc i))) (< (inc i) n)
                           (= "\n" (subs text (inc i) (+ i 2)))) (+ (- i pos) 2)
                      :else (inc (- i pos)))]
        (reader-advance! r adv)
        (subs text pos i)))))

(defn unread!
  "PushbackReader.unread: push chars back (a string or one char)."
  [r s]
  (when-not (is? ::pushback-reader r)
    (throw (host/ex* 'java.io.IOException "Unread not permitted")))
  (swap! (state-of r) update :buf str (str s))
  nil)

(defn close-reader! [r]
  (if (is? ::pushback-reader r)
    (do (swap! (state-of r) assoc :closed? true)
        (close-reader! (under-of r)))
    (swap! (state-of r) assoc :closed? true))
  nil)

;; ---------------------------------------------------------------------------
;; writers (state {:text s :closed? bool :out out-stream? :file f?})

(defn writer-over
  [kind file out]
  (value kind (atom {:text "" :closed? false :file file :out out})))

(defn string-writer [] (writer-over ::string-writer nil nil))

(defn output-stream-writer
  "A Writer over a byte output stream: its text is encoded UTF-8
   into the stream at write time."
  [out]
  (writer-over ::output-stream-writer nil out))

(defn file-writer
  "A Writer over a file: text accumulates and is written through
   :spit at close/flush. Append seeds the accumulation with the
   file's current text."
  [f append?]
  (let [v (writer-over ::file-writer f nil)]
    (when append?
      (host/try-all (fn [] (swap! (state-of v) assoc :text (file-text f)))))
    v))

(defn buffered-writer [w]
  (if (writer-value? w) (value ::buffered-writer (state-of w)) w))

(defn append-text! [w t]
  (let [st @(state-of w)]
    (when (:closed? st)
      (throw (host/ex* 'java.io.IOException "Stream closed")))
    (if-let [out (:out st)]
      (append-bytes! out (utf8-encode (str t)))
      (swap! (state-of w) update :text str (str t)))
    nil))

(defn written-text [w]
  (let [st @(state-of w)]
    (if (:out st)
      (utf8-decode (output-bytes (:out st)))
      (:text st))))

(defn flush-writer! [w]
  (let [st @(state-of w)]
    (cond (:file st) (write-file-text! (:file st) (:text st))
          (:out st) (flush-output! (:out st)))
    nil))

(defn close-writer! [w]
  (when-not (:closed? @(state-of w))
    (flush-writer! w)
    (swap! (state-of w) assoc :closed? true)
    (when-let [out (:out @(state-of w))]
      (close-output! out)))
  nil)

;; ---------------------------------------------------------------------------
;; the boot's class loader: a standin whose lookups answer nil -- the
;; boot loads libraries through its own roots, not a classpath, so a
;; resource is simply not found (stock's answer for an absent name).

(def class-loader {:cohost/kind ::class-loader})

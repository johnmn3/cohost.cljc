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
      ::sink                 'java.io.PrintWriter
      ::file-writer          'java.io.FileWriter
      ::output-stream-writer 'java.io.OutputStreamWriter
      ::server-socket        'java.net.ServerSocket
      ::socket               'java.net.Socket
      ::socket-input         'java.io.InputStream
      ::socket-output        'java.io.OutputStream
      ::socket-reader        'java.io.Reader
   ;; the host's standard streams (adapter :io-handles): System.out's
   ;; writer and System.in's reader as the JVM gives *out* and *in* theirs
      ::host-writer          'java.io.OutputStreamWriter
      ::host-reader          'java.io.InputStreamReader})

;; One record type per dispatch class (see the ns docstring). Stream
;; values carry [kind state under]: kind the fine kind, state the
;; state atom, under a wrapped value (a pushback reader's reader;
;; nil elsewhere). File stands alone with [path parent]. The two
;; *Dispatch types are never constructed: they exist so a host's
;; type table can name a type for java.net.URL / java.net.URI in an
;; extend position (the values themselves are cohost.http's, whose
;; record type a host's protocol dispatch cannot tell from a map's).
;; jank has no defrecord: jank compiles this file itself, and the type
;; macros are its adapter's, interned into cohost.core for booted code
;; only (measured: compile-module of a one-defrecord module dies
;; "unresolved-symbol defrecord" on jank 0.1-noble). There a value of the
;; layer is the adapter's standin shape -- a map tagged with its record's
;; name, as cohost.adapter's Iter/SB and cohost.shim's date values -- and
;; dispatch reads :cohost/kind through the shim's class-of, as it does for
;; cohost.http's maps.
#?(:jank (defn- stream-standin [tag k s u] (host/standin tag {:kind k :state s :under u})))
#?(:jank (defn ->FileValue [path parent] (host/standin 'FileValue {:path path :parent parent}))
   :default (defrecord FileValue [path parent]))
#?(:jank (defn ->InputStreamValue [k s u] (stream-standin 'InputStreamValue k s u))
   :default (defrecord InputStreamValue [kind state under]))
#?(:jank (defn ->BufferedInputStreamValue [k s u] (stream-standin 'BufferedInputStreamValue k s u))
   :default (defrecord BufferedInputStreamValue [kind state under]))
#?(:jank (defn ->OutputStreamValue [k s u] (stream-standin 'OutputStreamValue k s u))
   :default (defrecord OutputStreamValue [kind state under]))
#?(:jank (defn ->BufferedOutputStreamValue [k s u] (stream-standin 'BufferedOutputStreamValue k s u))
   :default (defrecord BufferedOutputStreamValue [kind state under]))
#?(:jank (defn ->ReaderValue [k s u] (stream-standin 'ReaderValue k s u))
   :default (defrecord ReaderValue [kind state under]))
#?(:jank (defn ->BufferedReaderValue [k s u] (stream-standin 'BufferedReaderValue k s u))
   :default (defrecord BufferedReaderValue [kind state under]))
;; StringWriter.toString is its text: on the JVM seat the host's str of a
;; writer is its toString, and stock with-out-str is (str the-writer)
(declare writer-string)
#?(:jank (defn ->WriterValue [k s u] (stream-standin 'WriterValue k s u))
   :default (defrecord WriterValue [kind state under]
                       #?@(:jolt [] :cljw [] :clj [Object
                                                   (toString [this]
                                                             (let [u (.under ^WriterValue this)]
                                                                  (if (instance? StringBuilder u)
                                                                      (.toString ^StringBuilder u)
                                                                      (writer-string this))))])))
#?(:jank (defn ->BufferedWriterValue [k s u] (stream-standin 'BufferedWriterValue k s u))
   :default (defrecord BufferedWriterValue [kind state under]))
#?(:jank nil :default (defrecord UrlDispatch [kind state under]))
#?(:jank nil :default (defrecord UriDispatch [kind state under]))
#?(:jank (defn ->ServerSocketValue [k s u] (stream-standin 'ServerSocketValue k s u))
   :default (defrecord ServerSocketValue [kind state under]))
#?(:jank (defn ->SocketValue [k s u] (stream-standin 'SocketValue k s u))
   :default (defrecord SocketValue [kind state under]))

#?(:jank (def ^:private standin-tags
              '#{FileValue InputStreamValue BufferedInputStreamValue OutputStreamValue
                 BufferedOutputStreamValue ReaderValue BufferedReaderValue WriterValue
                 BufferedWriterValue ServerSocketValue SocketValue}))

(defn- file-value? [x]
       #?(:jank (host/standin? 'FileValue x) :default (instance? FileValue x)))

(defn value?
      "True when x is a value of this layer."
      [x]
      #?(:jank (and (map? x) (contains? standin-tags (get x :cohost/standin)))
         :default
         (or (instance? FileValue x)
             (instance? InputStreamValue x) (instance? BufferedInputStreamValue x)
             (instance? OutputStreamValue x) (instance? BufferedOutputStreamValue x)
             (instance? ReaderValue x) (instance? BufferedReaderValue x)
             (instance? WriterValue x) (instance? BufferedWriterValue x)
             (instance? ServerSocketValue x) (instance? SocketValue x))))

(defn kind-of [x]
      (cond (file-value? x) ::file
            (value? x) (or (:cohost/kind x) (:kind x))
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
      ::buffered-writer       (fn [k s u] (->BufferedWriterValue k s u))
      ::server-socket         (fn [k s u] (->ServerSocketValue k s u))
      ::socket                (fn [k s u] (->SocketValue k s u))
      ::socket-input          (fn [k s u] (->InputStreamValue k s u))
      ::socket-output         (fn [k s u] (->OutputStreamValue k s u))
      ::socket-reader         (fn [k s u] (->ReaderValue k s u))
      ::sink                  (fn [k s u] (->WriterValue k s u))
      ::host-writer           (fn [k s u] (->WriterValue k s u))
      ::host-reader           (fn [k s u] (->ReaderValue k s u))})

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
  ;; jank: no record classes; the standin's tag names the type
      #?(:jank (get '{java.io.File FileValue java.io.InputStream InputStreamValue
                      java.io.BufferedInputStream BufferedInputStreamValue
                      java.io.OutputStream OutputStreamValue
                      java.io.BufferedOutputStream BufferedOutputStreamValue
                      java.io.Reader ReaderValue java.io.BufferedReader BufferedReaderValue
                      java.io.Writer WriterValue java.io.BufferedWriter BufferedWriterValue
                      java.net.Socket SocketValue java.net.ServerSocket ServerSocketValue}
                    token)
         :default
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
               java.net.Socket SocketValue
               java.net.ServerSocket ServerSocketValue
               nil)))

;; ---------------------------------------------------------------------------
;; sockets: an in-process loopback. A pipe is an atom {:bytes [] :closed?}
;; in the output-stream state shape, so append-byte!/close-output! work
;; on a socket's output unchanged; a socket input carries its own read
;; position over the shared pipe and BLOCKS (doorbell-brief waits) while
;; the pipe is empty and open, as a socket read does. The registry maps
;; port -> server state; only loopback connects exist here -- a real
;; network route is a host adapter's, when one grows one.

(declare read-byte!)

(def ^:private listeners (atom {}))
(def ^:private ephemeral-port (atom 49151))

(defn- wait-brief!
       "A short wait: a fresh doorbell's timeout where the host has
   doorbells (an unsignalled wait runs its timeout); a bare yield of
   the loop where it has none."
       []
       (when-let [db (host/doorbell)]
                 (host/doorbell-wait db 25))
       nil)

(defn- pipe [] (atom {:bytes [] :closed? false}))

(defn socket-value? [x] #?(:jank (host/standin? 'SocketValue x) :default (instance? SocketValue x)))
(defn server-socket-value? [x] #?(:jank (host/standin? 'ServerSocketValue x) :default (instance? ServerSocketValue x)))

(defn listen!
      "A ServerSocket value bound at port (0: an ephemeral port)."
      [port]
      (let [port (if (zero? port)
                     (loop [p (swap! ephemeral-port inc)]
                           (if (contains? @listeners p) (recur (swap! ephemeral-port inc)) p))
                     port)]
           (when (contains? @listeners port)
                 (throw (host/ex* 'java.net.BindException (str "Address already in use: " port))))
           (let [v (value ::server-socket (atom {:port port :queue (atom []) :closed? false}))]
                (swap! listeners assoc port v)
                v)))

(defn server-port [srv] (:port @(state-of srv)))
(defn server-closed? [srv] (boolean (:closed? @(state-of srv))))

(defn server-close! [srv]
      (swap! (state-of srv) assoc :closed? true)
      (swap! listeners dissoc (:port @(state-of srv)))
      nil)

(defn accept!
      "ServerSocket.accept: the next queued connection, blocking; a
   SocketException when the server socket is closed, as stock's
   accept loop expects on shutdown."
      [srv]
      (loop []
            (when (server-closed? srv)
                  (throw (host/ex* 'java.net.SocketException "Socket is closed")))
            (let [q (:queue @(state-of srv))]
                 (if (seq @q)
                     (let [conn (first @q)]
                          (swap! q (fn [v] (subvec v 1)))
                          conn)
                     (do (wait-brief!) (recur))))))

(defn- socket-over [in-pipe out-pipe port]
       (value ::socket (atom {:in-pipe in-pipe :out-pipe out-pipe
                              :port port :local-port (swap! ephemeral-port inc)
                              :closed? false :in nil :out nil})))

(defn connect!
      "A client Socket value connected to a listening port on this
   process's loopback; ConnectException when nothing listens (or the
   host is not a loopback name)."
      [host-name port]
      (when-not (contains? #{nil "localhost" "127.0.0.1" "::1" "0.0.0.0"} host-name)
                (throw (host/ex* 'java.net.ConnectException (str "Connection refused: " host-name))))
      (let [srv (get @listeners port)]
           (when (or (nil? srv) (server-closed? srv))
                 (throw (host/ex* 'java.net.ConnectException "Connection refused")))
           (let [c->s (pipe) s->c (pipe)
                 client (socket-over s->c c->s port)
                 server-end (socket-over c->s s->c port)]
                (swap! (:queue @(state-of srv)) conj server-end)
                client)))

(defn socket-closed? [s] (boolean (:closed? @(state-of s))))
(defn socket-port [s] (:port @(state-of s)))
(defn socket-local-port [s] (:local-port @(state-of s)))

(defn socket-input [s]
      (or (:in @(state-of s))
          (let [v (value ::socket-input
                         (atom {:pipe (:in-pipe @(state-of s)) :pos 0 :closed? false}))]
               (swap! (state-of s) assoc :in v)
               v)))

(defn socket-output [s]
      (or (:out @(state-of s))
          (let [v (value ::socket-output (:out-pipe @(state-of s)))]
               (swap! (state-of s) assoc :out v)
               v)))

(defn socket-close! [s]
      (let [st (state-of s)]
           (swap! st assoc :closed? true)
           (when-let [in (:in @st)] (swap! (state-of in) assoc :closed? true))
           (swap! (:out-pipe @st) assoc :closed? true)
           nil))

(defn socket-read-byte!
      "A blocking InputStream.read over a socket input's pipe."
      [in]
      (loop []
            (let [st @(state-of in)]
                 (when (:closed? st)
                       (throw (host/ex* 'java.io.IOException "Stream closed")))
                 (let [p @(:pipe st) pos (:pos st)]
                      (cond (< pos (count (:bytes p)))
                            (do (swap! (state-of in) update :pos inc) (nth (:bytes p) pos))
                            (:closed? p) -1
                            :else (do (wait-brief!) (recur)))))))

(defn socket-reader
      "The incremental Reader over a socket input (the layer's other
   stream readers decode whole content at wrap time; a socket's
   content has not arrived yet)."
      [in]
      (value ::socket-reader (atom {:closed? false :pending nil}) in))

(defn socket-read-char!
      "Reader.read over a socket reader: bytes assembled into UTF-16
   units as they arrive; -1 only when the pipe is closed and drained."
      [r]
      (let [st (state-of r)]
           (when (:closed? @st)
                 (throw (host/ex* 'java.io.IOException "Stream closed")))
           (if-let [pend (:pending @st)]
                   (do (swap! st assoc :pending nil) pend)
                   (let [in (under-of r)
                         b0 (read-byte! in)]
                        (if (neg? b0)
                            -1
                            (let [need (cond (< b0 0x80) 0 (< b0 0xE0) 1 (< b0 0xF0) 2 :else 3)
                                  bs (loop [bs [b0] n need]
                                           (if (zero? n)
                                               bs
                                               (let [b (read-byte! in)]
                                                    (if (neg? b) bs (recur (conj bs b) (dec n))))))
                                  cp (case (count bs)
                                           1 b0
                                           2 (bit-or (bit-shift-left (bit-and b0 0x1F) 6)
                                                     (bit-and (nth bs 1) 0x3F))
                                           3 (bit-or (bit-shift-left (bit-and b0 0x0F) 12)
                                                     (bit-shift-left (bit-and (nth bs 1) 0x3F) 6)
                                                     (bit-and (nth bs 2) 0x3F))
                                           4 (bit-or (bit-shift-left (bit-and b0 0x07) 18)
                                                     (bit-shift-left (bit-and (nth bs 1) 0x3F) 12)
                                                     (bit-shift-left (bit-and (nth bs 2) 0x3F) 6)
                                                     (bit-and (nth bs 3) 0x3F))
                                           b0)]
                                 (if (> cp 0xFFFF)
                                     (let [v (- cp 0x10000)]
                                          (swap! st assoc :pending (char (+ 0xDC00 (bit-and v 0x3FF))))
                                          (char (+ 0xD800 (bit-shift-right v 10))))
                                     (char cp))))))))

;; StringBuilder (the :default arm) is the class hosts' buffer;
;; the squint pass has no such class — see the docstring.
#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn- line-buffer
       "The read-line loops' accumulator: a StringBuilder on the class
   hosts. Squint's analyzer resolves no StringBuilder class and JS
   has none at runtime, so there the buffer is a small object with
   the same append/length/toString surface over a JS array."
       []
       #?(:squint (let [parts (array)
                        o (js-obj)]
                       (set! (.-append o) (fn [c] (.push parts c) o))
                       (set! (.-toString o) (fn [] (.join parts "")))
                       (.defineProperty js/Object o "length"
                                        (js-obj "get" (fn [] (.-length parts))))
                       o)
     ;; jank: no StringBuilder class and no JVM members; the scope's
     ;; local atom of chars, read through the helpers below
          :jank (atom [])
          :default (StringBuilder.)))

(defn- lb-append! [sb c] #?(:jank (do (swap! sb conj c) sb) :default (.append sb c)))
(defn- lb-length [sb] #?(:jank (count @sb) :default (.length sb)))
(defn- lb-str [sb] #?(:jank (apply str @sb) :default (.toString sb)))

(defn socket-read-line!
      "BufferedReader.readLine over a socket reader."
      [r]
      (loop [sb (line-buffer)]
            (let [c (socket-read-char! r)]
                 (cond (number? c) (when (pos? (lb-length sb)) (lb-str sb))
                       (= c \newline) (lb-str sb)
                       (= c \return) (do (let [c2 (socket-read-char! r)]
                                              (when (and (not (number? c2)) (not= c2 \newline))
                                                    (swap! (state-of r) assoc :pending c2)))
                                         (lb-str sb))
                       :else (do (lb-append! sb c) (recur sb))))))

;; ---------------------------------------------------------------------------
;; the host's standard streams (adapter :io-handles). A host whose own
;; print/read machinery takes only its native handles (let-go) gives the
;; layer raw leaves instead: write a string to a handle, flush it, read
;; one line from it. The booted core's *out*/*err*/*in* roots are these
;; values, so cohost's print/read stack -- not the host's -- serves them.
;; The handle is looked up per operation (a thunk), so a host-side
;; rebinding of its own stream (a host with-out-str around a boot call)
;; is seen. State is the value's atom, as for every stream of the layer.

(defn host-writer
      "A Writer over the host stream the thunk h answers (:out or :err's leaf)."
      [h]
      (value ::host-writer (atom {:handle h :closed? false})))

(defn host-reader
      "An incremental Reader over the host stream the thunk h answers: a line
   is pulled through the adapter's :read-line leaf when the buffered one
   is used up; -1 once the leaf answers nil."
      [h]
      (value ::host-reader (atom {:handle h :text "" :pos 0 :eof? false :closed? false})))

(defn- host-fill!
       "Make sure a host reader has an unread char buffered, pulling lines
   from the host; false at the end of the stream."
       [r]
       (let [st (state-of r)]
            (loop []
                  (let [{:keys [text pos eof? handle]} @st]
                       (cond (< pos (count text)) true
                             eof? false
                             :else (let [line (host/io-read-line (handle))]
                                        (if (nil? line)
                                            (swap! st assoc :eof? true :text "" :pos 0)
                                            (swap! st assoc :text (str line "\n") :pos 0))
                                        (recur)))))))

(defn- host-read-char! [r]
       (when (:closed? @(state-of r))
             (throw (host/ex* 'java.io.IOException "Stream closed")))
       (if (host-fill! r)
           (let [{:keys [text pos]} @(state-of r)]
                (swap! (state-of r) assoc :pos (inc pos))
                (nth text pos))
           -1))

(defn- host-read-line! [r]
       (when (:closed? @(state-of r))
             (throw (host/ex* 'java.io.IOException "Stream closed")))
       (when (host-fill! r)
             (let [st (state-of r)
                   {:keys [text pos]} @st
          ;; the buffer holds exactly one host line and its \n
                   line (subs text pos (dec (count text)))]
                  (swap! st assoc :pos (count text))
                  line)))

(def reader-kinds
     #{::reader ::buffered-reader ::string-reader ::char-array-reader
       ::input-stream-reader ::file-reader ::pushback-reader
       ::socket-reader ::host-reader})
(def writer-kinds
     #{::writer ::buffered-writer ::string-writer ::file-writer
       ::output-stream-writer ::sink ::host-writer})
(def input-stream-kinds
     #{::input-stream ::buffered-input-stream ::file-input-stream
       ::byte-input-stream ::socket-input})
(def output-stream-kinds
     #{::output-stream ::buffered-output-stream ::file-output-stream
       ::byte-output-stream ::socket-output})

(defn reader-value? [x] (contains? reader-kinds (kind-of x)))
(defn writer-value? [x]
  ;; JVM: two instanceof checks. The kind-of path walks every io record
  ;; type, then reads :cohost/kind off the extmap (F1 write-dot / toString).
      #?(:clj (or (instance? WriterValue x) (instance? BufferedWriterValue x))
         :default (contains? writer-kinds (kind-of x))))
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
      ([parent child] (assoc (->FileValue (str parent "/" child) (str parent))
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

(declare read-byte-whole! read-bytes-whole! read-char-whole!
         read-chars-whole! read-line-whole!)

(defn read-byte!
      "InputStream.read: the next byte 0-255, or -1 at the end."
      [in]
      (if (is? ::socket-input in)
          (socket-read-byte! in)
          (read-byte-whole! in)))

(defn- read-byte-whole!
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
      (if (is? ::socket-input in)
          (let [b (read-byte! in)]
               (if (neg? b)
                   [-1 []]
                   (let [out [b]
                         st @(state-of in) p @(:pipe st) pos (:pos st)
                         avail (- (count (:bytes p)) pos)]
                        (if (and (pos? avail) (< (count out) len))
                            (let [take (min avail (- len (count out)))]
                                 (swap! (state-of in) update :pos + take)
                                 [(+ (count out) take)
                                  (into out (subvec (:bytes p) pos (+ pos take)))])
                            [(count out) out]))))
          (read-bytes-whole! in len)))

(defn- read-bytes-whole!
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
      (if (is? ::socket-input in)
          (socket-reader in)
          (reader-over-text ::input-stream-reader (stream-text in))))

(defn file-reader
      "A FileReader over a file's whole text."
      [f]
      (reader-over-text ::file-reader (file-text f)))

(defn close-input! [in]
      (swap! (state-of in) assoc :closed? true)
      nil)

(defn buffered-reader [r]
      (if (and (reader-value? r) (not (is? ::pushback-reader r)))
          (value ::buffered-reader (state-of r) r)
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

(defn- socket-backed?
       "True when r's content is a socket reader's, under any wrappers:
   a buffered reader is transparent, a pushback reader's governing
   content is its under's."
       [r]
       (loop [x r]
             (cond (nil? x) false
                   (is? ::socket-reader x) true
                   (is? ::host-reader x) true
                   (or (is? ::buffered-reader x) (is? ::pushback-reader x)) (recur (under-of x))
                   :else false)))

(defn read-char!
      "Reader.read: the next char (a Character), or -1 at the end, as
   cohost.http's readers answer."
      [r]
      (cond (is? ::socket-reader r) (socket-read-char! r)
            (is? ::host-reader r) (host-read-char! r)
            (is? ::buffered-reader r) (read-char! (under-of r))
            (and (is? ::pushback-reader r) (socket-backed? r))
            (let [buf (:buf @(state-of r))]
                 (if (seq buf)
                     (do (swap! (state-of r) assoc :buf (subs buf 1)) (nth buf 0))
                     (read-char! (under-of r))))
            :else (read-char-whole! r)))

(defn- read-char-whole!
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
      (cond (is? ::buffered-reader r) (read-chars! (under-of r) len)
            (and (is? ::pushback-reader r) (socket-backed? r))
            (let [c0 (read-char! r)]
                 (if (number? c0)
                     [-1 ""]
                     (loop [out (str c0)]
                           (if (>= (count out) len)
                               [(count out) out]
                               (let [c (read-char! r)]
                                    (if (number? c) [(count out) out] (recur (str out c))))))))
            (is? ::host-reader r)
        ;; up to len chars, never past the end of the buffered host line:
        ;; a read must not block for input the caller did not ask for
            (if (host-fill! r)
                (let [{:keys [text pos]} @(state-of r)
                      n (min len (- (count text) pos))]
                     (swap! (state-of r) assoc :pos (+ pos n))
                     [n (subs text pos (+ pos n))])
                [-1 ""])
            (is? ::socket-reader r)
            (let [b0 (socket-read-char! r)]
                 (if (number? b0)
                     [-1 ""]
                     (loop [out (str b0)]
                           (if (>= (count out) len)
                               [(count out) out]
                               (let [c (socket-read-char! r)]
                                    (if (number? c)
                                        [(count out) out]
                                        (recur (str out c))))))))
            :else (read-chars-whole! r len)))

(defn- read-chars-whole!
       [r len]
       (when (reader-closed? r)
             (throw (host/ex* 'java.io.IOException "Stream closed")))
       (let [[text pos] (reader-text+pos r)
             avail (- (count text) pos)
             n (min len avail)]
            (if (and (zero? n) (pos? len))
                [-1 ""]
                (do (reader-advance! r n) [n (subs text pos (+ pos n))]))))

(declare unread!)

(defn- read-line-chars!
       "readLine built on read-char!: for a pushback reader over a socket
   reader, whose buffer and under the char path already composes."
       [r]
       (loop [sb (line-buffer)]
             (let [c (read-char! r)]
                  (cond (number? c) (when (pos? (count (lb-str sb))) (lb-str sb))
                        (= c \newline) (lb-str sb)
                        (= c \return) (let [d (read-char! r)]
                                           (when (and (not (number? d)) (not= d \newline)) (unread! r d))
                                           (lb-str sb))
                        :else (do (lb-append! sb c) (recur sb))))))

(defn read-line!
      "BufferedReader.readLine: the next line without its terminator
   (\\n, \\r\\n or \\r), nil at the end."
      [r]
      (cond (is? ::socket-reader r) (socket-read-line! r)
            (is? ::host-reader r) (host-read-line! r)
            (is? ::buffered-reader r) (read-line! (under-of r))
            (socket-backed? r) (read-line-chars! r)
            :else (read-line-whole! r)))

(defn- read-line-whole!
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

;; LineNumberingPushbackReader's line number and string capture, read
;; off the reader's own state: the consumed text is the under reader's
;; text up to its position, less what was pushed back. The line is the
;; newlines consumed past the base setLineNumber recorded (1 at
;; construction, as LNPR starts); a capture records where it began.
;; A socket-backed reader keeps no text: its line stays at the base.
(defn- newlines-in [s]
       (loop [n 0 from 0]
             (let [i (host/index-of s "\n" from)]
                  (if (neg? i) n (recur (inc n) (inc i))))))

(defn- consumed-text
       "The text a pushback reader over a text reader has handed out: nil for
   a socket-backed one (its content is not retained)."
       [r]
       (when-not (socket-backed? r)
                 (let [ust @(state-of (under-of r))
                       buf (:buf @(state-of r))
                       upto (max 0 (- (:pos ust) (count buf)))]
                      (subs (:text ust) 0 upto))))

(defn line-number
      "LineNumberingPushbackReader.getLineNumber over a pushback reader."
      [r]
      (+ (get @(state-of r) :line-base 1)
         (if-let [t (consumed-text r)] (newlines-in t) 0)))

(defn set-line-number!
      "LineNumberingPushbackReader.setLineNumber: the line here becomes n."
      [r n]
      (swap! (state-of r) assoc :line-base
             (- n (if-let [t (consumed-text r)] (newlines-in t) 0)))
      nil)

(defn capture-string!
      "LineNumberingPushbackReader.captureString: begin capturing the text read."
      [r]
      (swap! (state-of r) assoc :capture-at (count (or (consumed-text r) "")))
      nil)

(defn captured-string
      "LineNumberingPushbackReader.getString: the text read since
   captureString (nil when none began), ending the capture."
      [r]
      (let [at (:capture-at @(state-of r))]
           (swap! (state-of r) dissoc :capture-at)
           (when at (subs (or (consumed-text r) "") at))))

(defn close-reader! [r]
      (if (is? ::pushback-reader r)
          (do (swap! (state-of r) assoc :closed? true)
              (close-reader! (under-of r)))
          (swap! (state-of r) assoc :closed? true))
      nil)

;; ---------------------------------------------------------------------------
;; writers (state {:text buf :closed? bool :out out-stream? :file f?})
;; :text is a growable buffer, not a concatenated string. A tight
;; with-out-str prn loop was ~4× (per-iter) to ~80× (one writer, many
;; prn) slower than native Clojure because append-text! did
;; (swap! … update :text str t), which copies the whole accumulation
;; on every write. JVM uses StringBuilder; other hosts accumulate
;; chunks and join once at read.

(defn- new-text-buf
       ([] (new-text-buf nil))
       ([s]
        #?(:clj (let [b (StringBuilder.)]
                     (when (and s (pos? (count (str s)))) (.append b (str s)))
                     b)
           :default (if (and s (pos? (count (str s)))) [(str s)] []))))

(defn- text-buf-append
       "Append t to buf, returning the (possibly same) buffer for swap!."
       [buf t]
       (let [s (str t)]
            #?(:clj (cond (instance? StringBuilder buf) (doto ^StringBuilder buf (.append s))
                          (string? buf) (doto (StringBuilder. ^String buf) (.append s))
                          :else (doto (StringBuilder.) (.append (str buf)) (.append s)))
               :default (if (string? buf) (str buf s) (conj (or buf []) s)))))

(defn- text-buf-str [buf]
       (cond (nil? buf) ""
             (string? buf) buf
             #?(:clj (instance? StringBuilder buf) :default false)
             #?(:clj (.toString ^StringBuilder buf) :default "")
             (sequential? buf) (apply str buf)
             :else (str buf)))

(defn writer-over
      [kind file out]
      (value kind (atom {:text (new-text-buf) :closed? false :file file :out out})))

(defn string-writer
      "A fresh StringWriter. On the JVM the StringBuilder lives in the
   record's `under` field so append and toString do not deref a state
   atom. No per-call atom: close/flush of this writer is a no-op (stock
   with-out-str never closes). The 7-arg record ctor carries
   :cohost/kind in the extmap without an assoc copy (F1 ARM1: RT.assoc
   was a measurable leaf under string-writer)."
      []
      #?(:clj (WriterValue. ::string-writer
                            nil
                            (StringBuilder.)
                            nil
                            {:cohost/kind ::string-writer}
                            -1 -1)
         :default (writer-over ::string-writer nil nil)))

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
                 (host/try-all (fn [] (swap! (state-of v) assoc :text (new-text-buf (file-text f))))))
           v))

(defn buffered-writer [w]
      (if (writer-value? w) (value ::buffered-writer (state-of w)) w))

(defn- writer-state-atom
       "The writer's state atom. On the JVM a type-hinted field read avoids
   Keyword valAt on the record (WriterValue.valAt was a measurable leaf
   in the F1 ARM6 sample)."
       [w]
       #?(:clj (cond (instance? WriterValue w) (.state ^WriterValue w)
                     (instance? BufferedWriterValue w) (.state ^BufferedWriterValue w)
                     :else (state-of w))
          :default (state-of w)))

#?(:clj (defn- string-builder-under
               "A StringWriter's buffer, when it lives on the record (JVM string-writer)."
               [w]
               (let [u (if (instance? WriterValue w) (.under ^WriterValue w) nil)]
                    (when (instance? StringBuilder u) u)))
   :default (defn- string-builder-under [_w] nil))

(defn- append-text-slow!
       [w t]
       (let [s (if (string? t) t (str t))
             a (writer-state-atom w)
             st @a]
            (when (:closed? st)
                  (throw (host/ex* 'java.io.IOException "Stream closed")))
            (cond
             (= ::sink (:cohost/kind w))
             #?(:clj (let [buf (:buf st)]
                          (if (instance? StringBuilder buf)
                              (.append ^StringBuilder buf s)
                              (swap! a update :buf text-buf-append s)))
                :default (swap! a update :buf text-buf-append s))
             (:handle st) (host/io-write! ((:handle st)) s)
             (:out st) (append-bytes! (:out st) (utf8-encode s))
             :else
             #?(:clj (let [buf (:text st)]
                          (if (instance? StringBuilder buf)
                              (.append ^StringBuilder buf s)
                              (swap! a update :text text-buf-append s)))
                :default (swap! a update :text text-buf-append s)))
            nil))

(defn append-text!
      "Append t to writer w. On the JVM a StringWriter appends to the
   StringBuilder in `under` with no helper call and no atom deref.
   The tight .under/.append path is :clj-only: jank's analyzer rejects
   an unconditioned StringBuilder member call at boot (kind WITH mini #58)."
      [w t]
      #?(:clj
         (if (instance? WriterValue w)
             (let [u (.under ^WriterValue w)]
                  (if (instance? StringBuilder u)
                      (do (.append ^StringBuilder u (if (instance? String t) t (str t))) nil)
                      (append-text-slow! w t)))
             (append-text-slow! w t))
         :default
         (append-text-slow! w t)))

(defn sink-writer
      "A Writer (class java.io.PrintWriter) -- clojure.core/PrintWriter-on's
   value: writes build up a string, and a flush calls f with the string
   built since the last flush (when there is one); a close flushes, then
   calls close-f (when given) of no arguments. As stock's proxy Writer
   under its BufferedWriter: one call of f per flush, never per write."
      ([f] (sink-writer f nil))
      ([f close-f]
       (value ::sink (atom {:sink f :close close-f :buf (new-text-buf) :closed? false}))))

(defn written-text
      "The writer's accumulated text. On the JVM a StringWriter's buffer is
   `.toString` of the StringBuilder on the record, no atom deref."
      [w]
      ;; string-builder-under is nil off the JVM seat by design (its
      ;; :default arm is nil): the else branch is the live one there.
      (if-let [sb #_{:clj-kondo/ignore [:constant-condition]} (string-builder-under w)]
              #?(:clj (.toString ^StringBuilder sb) :default (text-buf-str sb))
              (let [st @(writer-state-atom w)]
                   (if (:out st)
                       (utf8-decode (output-bytes (:out st)))
                       (let [buf (:text st)]
                            #?(:clj (if (instance? StringBuilder buf)
                                        (.toString ^StringBuilder buf)
                                        (text-buf-str buf))
                               :default (text-buf-str buf)))))))

(defn writer-string
      "Object.toString of a layer writer: a StringWriter's text, else the
   class's name and an identity hash, as the JVM's."
      [w]
      (if-let [sb #_{:clj-kondo/ignore [:constant-condition]} (string-builder-under w)]
              #?(:clj (.toString ^StringBuilder sb) :default (text-buf-str sb))
              (if (is? ::string-writer w)
                  (written-text w)
                  (str (class-token w) "@" #?(:jolt (hash w) :cljw (hash w) :clj (Integer/toHexString (System/identityHashCode w)) :default (hash w))))))

(defn write-printed-string!
      "print-method String without a per-char shim hop. The loop is a host
   tight loop (no rewritten .charAt / .append, no char-escape-string
   map lookup). Escapes are stock core_print's seven: \\n \\t \\r \\\" \\\\ \\f \\b.
   readably? is (or *print-dup* *print-readably*)."
      [w s readably?]
      #?(:clj
         (let [s (if (instance? String s) s (str s))]
              (if-not readably?
                      (append-text! w s)
                      (if-let [sb (string-builder-under w)]
                              (let [n (.length ^String s)]
                                   (.append ^StringBuilder sb \")
                                   (loop [i 0]
                                         (when (< i n)
                                               (let [c (.charAt ^String s i)]
                                                    (case (int c)
                                                          10 (.append ^StringBuilder sb "\\n")
                                                          9  (.append ^StringBuilder sb "\\t")
                                                          13 (.append ^StringBuilder sb "\\r")
                                                          34 (.append ^StringBuilder sb "\\\"")
                                                          92 (.append ^StringBuilder sb "\\\\")
                                                          12 (.append ^StringBuilder sb "\\f")
                                                          8  (.append ^StringBuilder sb "\\b")
                                                          (.append ^StringBuilder sb c)))
                                               (recur (inc i))))
                                   (.append ^StringBuilder sb \"))
                              (append-text!
                               w
                               (let [n (.length ^String s)
                                     b (StringBuilder. (+ n 2))]
                                    (.append b \")
                                    (loop [i 0]
                                          (when (< i n)
                                                (let [c (.charAt ^String s i)]
                                                     (case (int c)
                                                           10 (.append b "\\n")
                                                           9  (.append b "\\t")
                                                           13 (.append b "\\r")
                                                           34 (.append b "\\\"")
                                                           92 (.append b "\\\\")
                                                           12 (.append b "\\f")
                                                           8  (.append b "\\b")
                                                           (.append b c)))
                                                (recur (inc i))))
                                    (.append b \")
                                    (.toString b))))))
         :default (append-text! w (if readably? (str "\"" s "\"") (str s))))
      nil)

;; pr-on-into-sb! is :clj only: its arglist hint and body name the JVM class
;; StringBuilder, which no other body's analyzer can resolve; every other
;; body's fast-pr-on! takes its :default (fallback) arm and never calls it.
#?(:clj
   (defn- pr-on-into-sb!
          "fast-pr-on! when w's buffer is a StringBuilder on `under`: append
   directly, no per-chunk append-text! / string-builder-under hop."
          [x ^StringBuilder sb w print-level print-length print-namespace-maps print-meta print-readably fallback]
          (letfn [(pr* [x]
                       (cond
                        (instance? clojure.lang.Keyword x)
                        (.append sb (.toString ^clojure.lang.Keyword x))
                        (instance? Long x)
                        (.append sb ^long (.longValue ^Long x))
                        (nil? x) (.append sb "nil")
                        (instance? Boolean x) (.append sb (if x "true" "false"))
                        (instance? String x)
                        (write-printed-string! w x (boolean print-readably))
                        (and (instance? clojure.lang.IPersistentMap x)
                             (not (instance? clojure.lang.IRecord x))
                             (not print-level)
                             (not print-length)
                             (or (not print-namespace-maps)
                                 (let [e (first x)]
                                      (or (nil? e)
                                          (let [k (key e)]
                                               (not (and (or (keyword? k) (symbol? k))
                                                         (namespace k)))))))
                             (not (and (meta x) (and print-meta print-readably))))
                        (do (.append sb "{")
                            (when-let [xs (seq x)]
                                      (loop [e (first xs) n (next xs)]
                                            (pr* (key e))
                                            (.append sb " ")
                                            (pr* (val e))
                                            (when n
                                                  (.append sb ", ")
                                                  (recur (first n) (next n)))))
                            (.append sb "}"))
                        (and (instance? clojure.lang.IPersistentVector x)
                             (not print-level)
                             (not print-length)
                             (not (and (meta x) (and print-meta print-readably))))
                        (do (.append sb "[")
                            (let [n (count x)]
                                 (loop [i 0]
                                       (when (< i n)
                                             (when (pos? i) (.append sb " "))
                                             (pr* (nth x i))
                                             (recur (unchecked-inc i)))))
                            (.append sb "]"))
                        :else (fallback x w)))]
                 (pr* x)
                 nil)))

;; The flag params live in the :clj arm; the :default arm is nil
;; (non-JVM seats route pr-on to the fallback — see "takes its
;; :default (fallback) arm" above), so they read unused there.
#_{:clj-kondo/ignore [:unused-binding]}
(defn fast-pr-on!
      "Host-compiled pr-on for the common types. The earlier booted seat's
   (instance? clojure.lang.IPersistentMap x) went through the shim's
   instance_fn / token-isa? / class-of (43% of ARM2 samples) and often
   missed, so simple maps fell through to print-method / print-sequential
   (MultiFn on 64% of stacks). Here instance? is JVM instanceof, and
   writes go to append-text!, not shimmed .write.
   Called only when *print-dup* is false. fallback is (fn [x w] ...) for
   types this seat does not handle (typically print-method). Returns nil."
      [x w print-level print-length print-namespace-maps print-meta print-readably fallback]
      #?(:clj
     ;; Only the io-layer writers take append-text!. pprint's pretty-writer
     ;; and other targets go to fallback (print-method), which already
     ;; knows how to .write / .append them.
         (if-not (or (instance? WriterValue w) (instance? BufferedWriterValue w))
                 (do (fallback x w) nil)
                 (if-let [sb (string-builder-under w)]
                         (pr-on-into-sb! x sb w print-level print-length print-namespace-maps
                                         print-meta print-readably fallback)
                         (letfn [(pr* [x]
                                      (cond
                                       (instance? clojure.lang.Keyword x) (append-text! w (str x))
                                       (instance? Long x) (append-text! w (str x))
                                       (nil? x) (append-text! w "nil")
                                       (instance? Boolean x) (append-text! w (if x "true" "false"))
                                       (instance? String x)
                                       (write-printed-string! w x (boolean print-readably))
                                       (and (instance? clojure.lang.IPersistentMap x)
                                            (not (instance? clojure.lang.IRecord x))
                                            (not print-level)
                                            (not print-length)
                                            (or (not print-namespace-maps)
                                                (let [e (first x)]
                                                     (or (nil? e)
                                                         (let [k (key e)]
                                                              (not (and (or (keyword? k) (symbol? k))
                                                                        (namespace k)))))))
                                            (not (and (meta x) (and print-meta print-readably))))
                                       (do (append-text! w "{")
                                           (when-let [xs (seq x)]
                                                     (loop [e (first xs) n (next xs)]
                                                           (pr* (key e))
                                                           (append-text! w " ")
                                                           (pr* (val e))
                                                           (when n
                                                                 (append-text! w ", ")
                                                                 (recur (first n) (next n)))))
                                           (append-text! w "}"))
                                       (and (instance? clojure.lang.IPersistentVector x)
                                            (not print-level)
                                            (not print-length)
                                            (not (and (meta x) (and print-meta print-readably))))
                                       (do (append-text! w "[")
                                           (let [n (count x)]
                                                (loop [i 0]
                                                      (when (< i n)
                                                            (when (pos? i) (append-text! w " "))
                                                            (pr* (nth x i))
                                                            (recur (unchecked-inc i)))))
                                           (append-text! w "]"))
                                       :else (fallback x w)))]
                                (pr* x)
                                nil)))
         :default (do (fallback x w) nil)))

;; Used from the :clj arms of the print machinery below. Kondo
;; does not credit a use inside a reader conditional against a
;; def whose value is itself a reader conditional (minimal repro
;; in the syntax lane's STATUS.md), so the linter's unused-var
;; warning for this def is a proven false positive.
#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private system-newline
     #?(:clj (or (System/getProperty "line.separator") "\n")
        :default "\n"))

;; No hint on this def: jank's parser rejects string meta hints
;; (parse/invalid-meta-hint-value) ANYWHERE in a file — it parses
;; reader-conditional branches it will not select, so every array
;; hint in this file is spelled ^objects — and the `objects`
;; symbol hint is valid only in fn-arg/let positions — in def-var
;; position the JVM resolves the tag as a class and fails. Every
;; use site carries its own ^objects hint, so nothing loses typing.
(def ^:private print-vs (object-array 10))
;; 0 *out*, 1 *print-dup*, 2 *print-level*, 3 *print-length*,
;; 4 *print-namespace-maps*, 5 *print-meta*, 6 *print-readably*,
;; 7 *flush-on-newline*, 8 print-method, 9 print-dup

;; ns-resolve is a var of the booted/JVM core this machinery runs
;; against; cljs.core's static model has no such var.
#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn- resolve-core-var [nm]
       (ns-resolve 'cohost.core nm))

(defn- out-var
       "The booted *out* var, cached in print-vs after install-print-vars!."
       []
       (or (aget ^objects print-vs 0)
           (let [v (resolve-core-var '*out*)]
                (aset ^objects print-vs 0 v)
                v)))

(defn install-print-vars!
      "Cache cohost.core's print vars after the io layer and core_print are in.
   The hot path then .deref's these Vars; no ns-resolve, no clojure.core/deref."
      []
      (let [vs ^objects print-vs]
           (aset vs 0 (resolve-core-var '*out*))
           (aset vs 1 (resolve-core-var '*print-dup*))
           (aset vs 2 (resolve-core-var '*print-level*))
           (aset vs 3 (resolve-core-var '*print-length*))
           (aset vs 4 (resolve-core-var '*print-namespace-maps*))
           (aset vs 5 (resolve-core-var '*print-meta*))
           (aset vs 6 (resolve-core-var '*print-readably*))
           (aset vs 7 (resolve-core-var '*flush-on-newline*))
           (aset vs 8 (resolve-core-var 'print-method))
           (aset vs 9 (resolve-core-var 'print-dup))
           nil))

;; print-method: same booted-core var family as resolve-core-var.
;; The unused-private-var warning is the same proven false
;; positive as system-newline above: called from pr-on-any!'s
;; :clj arm only.
#_{:clj-kondo/ignore [:unresolved-symbol :unused-private-var]}
(defn- fallback-print-method [x w]
       (let [f (aget ^objects print-vs 8)]
            (if f (f x w) (print-method x w))))

#?(:clj
   (do
     ;; Direct accessors for package-private Var$Frame / Var$TBox, defined
     ;; as hidden classes in clojure.lang (so they can new TBox/Frame and
     ;; read Frame.bindings without Field.get or Constructor.newInstance
     ;; on the hot path). A Lookup is obtained by calling MethodHandles.lookup
     ;; through Reflector, which lives in clojure.lang and has the privilege
     ;; to define those classes.
    (defn- emit-fn-bytes
           [iname iface apply-desc emit-apply!]
           (let [cw (clojure.asm.ClassWriter.
                     (bit-or clojure.asm.ClassWriter/COMPUTE_FRAMES
                             clojure.asm.ClassWriter/COMPUTE_MAXS))]
                (.visit cw clojure.asm.Opcodes/V11
                        (bit-or clojure.asm.Opcodes/ACC_PUBLIC
                                clojure.asm.Opcodes/ACC_FINAL
                                clojure.asm.Opcodes/ACC_SUPER)
                        iname nil "java/lang/Object"
                        (into-array String [iface]))
                (let [mv (.visitMethod cw clojure.asm.Opcodes/ACC_PUBLIC "<init>" "()V" nil nil)]
                     (.visitCode mv)
                     (.visitVarInsn mv clojure.asm.Opcodes/ALOAD 0)
                     (.visitMethodInsn mv clojure.asm.Opcodes/INVOKESPECIAL
                                       "java/lang/Object" "<init>" "()V" false)
                     (.visitInsn mv clojure.asm.Opcodes/RETURN)
                     (.visitMaxs mv 0 0)
                     (.visitEnd mv))
                (let [mv (.visitMethod cw clojure.asm.Opcodes/ACC_PUBLIC "apply" apply-desc nil nil)]
                     (.visitCode mv)
                     (emit-apply! mv)
                     (.visitMaxs mv 0 0)
                     (.visitEnd mv))
                (.visitEnd cw)
                (.toByteArray cw)))

    (defn- clojure-lang-lookup
           ^java.lang.invoke.MethodHandles$Lookup []
           (clojure.lang.Reflector/invokeStaticMethod
            java.lang.invoke.MethodHandles "lookup" (object-array 0)))

    (defn- new-hidden [bytes]
           (let [lk (clojure-lang-lookup)
                 opts (into-array java.lang.invoke.MethodHandles$Lookup$ClassOption
                                  [java.lang.invoke.MethodHandles$Lookup$ClassOption/NESTMATE])
                 hidden (.defineHiddenClass lk bytes true opts)
                 cls (.lookupClass hidden)
                 ctor (.findConstructor hidden cls
                                        (java.lang.invoke.MethodType/methodType Void/TYPE))]
                (.invokeWithArguments ctor (object-array 0))))

    (def ^:private ^java.util.function.Function frame-bindings-of
         (new-hidden
          (emit-fn-bytes "clojure/lang/$$CohostFrameBindings"
                         "java/util/function/Function"
                         "(Ljava/lang/Object;)Ljava/lang/Object;"
                         (fn [^clojure.asm.MethodVisitor mv]
                             (.visitVarInsn mv clojure.asm.Opcodes/ALOAD 1)
                             (.visitTypeInsn mv clojure.asm.Opcodes/CHECKCAST "clojure/lang/Var$Frame")
                             (.visitFieldInsn mv clojure.asm.Opcodes/GETFIELD
                                              "clojure/lang/Var$Frame" "bindings"
                                              "Lclojure/lang/Associative;")
                             (.visitInsn mv clojure.asm.Opcodes/ARETURN)))))

    (def ^:private ^java.util.function.BiFunction tbox-maker
         (new-hidden
          (emit-fn-bytes "clojure/lang/$$CohostTBox"
                         "java/util/function/BiFunction"
                         "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                         (fn [^clojure.asm.MethodVisitor mv]
                             (.visitTypeInsn mv clojure.asm.Opcodes/NEW "clojure/lang/Var$TBox")
                             (.visitInsn mv clojure.asm.Opcodes/DUP)
                             (.visitVarInsn mv clojure.asm.Opcodes/ALOAD 1)
                             (.visitTypeInsn mv clojure.asm.Opcodes/CHECKCAST "java/lang/Thread")
                             (.visitVarInsn mv clojure.asm.Opcodes/ALOAD 2)
                             (.visitMethodInsn mv clojure.asm.Opcodes/INVOKESPECIAL
                                               "clojure/lang/Var$TBox" "<init>"
                                               "(Ljava/lang/Thread;Ljava/lang/Object;)V" false)
                             (.visitInsn mv clojure.asm.Opcodes/ARETURN)))))

    (def ^:private ^java.util.function.BiFunction frame-maker
         (new-hidden
          (emit-fn-bytes "clojure/lang/$$CohostFrame"
                         "java/util/function/BiFunction"
                         "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                         (fn [^clojure.asm.MethodVisitor mv]
                             (.visitTypeInsn mv clojure.asm.Opcodes/NEW "clojure/lang/Var$Frame")
                             (.visitInsn mv clojure.asm.Opcodes/DUP)
                             (.visitVarInsn mv clojure.asm.Opcodes/ALOAD 1)
                             (.visitTypeInsn mv clojure.asm.Opcodes/CHECKCAST "clojure/lang/Associative")
                             (.visitVarInsn mv clojure.asm.Opcodes/ALOAD 2)
                             (.visitTypeInsn mv clojure.asm.Opcodes/CHECKCAST "clojure/lang/Var$Frame")
                             (.visitMethodInsn mv clojure.asm.Opcodes/INVOKESPECIAL
                                               "clojure/lang/Var$Frame" "<init>"
                                               "(Lclojure/lang/Associative;Lclojure/lang/Var$Frame;)V" false)
                             (.visitInsn mv clojure.asm.Opcodes/ARETURN)))))

    (def ^:private ^java.util.function.Function thread-bound-of
         (new-hidden
          (emit-fn-bytes "clojure/lang/$$CohostThreadBound"
                         "java/util/function/Function"
                         "(Ljava/lang/Object;)Ljava/lang/Object;"
                         (fn [^clojure.asm.MethodVisitor mv]
                             (.visitVarInsn mv clojure.asm.Opcodes/ALOAD 1)
                             (.visitTypeInsn mv clojure.asm.Opcodes/CHECKCAST "clojure/lang/Var")
                             (.visitFieldInsn mv clojure.asm.Opcodes/GETFIELD
                                              "clojure/lang/Var" "threadBound"
                                              "Ljava/util/concurrent/atomic/AtomicBoolean;")
                             (.visitInsn mv clojure.asm.Opcodes/ARETURN)))))

    (defn- frame-bindings
           "The current thread's Var binding map (Var -> TBox), via the Frame."
           []
           (.apply frame-bindings-of (clojure.lang.Var/getThreadBindingFrame)))

    (def ^:private out-thread-bound (object-array 1))

    (defn- mark-thread-bound!
           [v]
           (let [^objects slot out-thread-bound
                 ab (or (aget slot 0)
                        (let [x (.apply thread-bound-of v)]
                             (aset slot 0 x)
                             x))]
                (.set ^java.util.concurrent.atomic.AtomicBoolean ab true)))

    (defn- make-tbox [val]
           (.apply tbox-maker (Thread/currentThread) val))

    (defn- make-frame [bindings prev]
           (.apply frame-maker bindings prev))

     ;; Overlay one Var->TBox on top of an existing binding map so a
     ;; with-out-str push does not PersistentHashMap.assoc. entryAt of
     ;; the overlaid var is an identity check against a cached MapEntry
     ;; (Var.getThreadBinding / deref of *out* must not allocate). Other
     ;; keys go to `under`.
    (deftype OutOverlay [under v tbox entry]
             clojure.lang.ILookup
             (valAt [this k] (.valAt this k nil))
             (valAt [_ k not-found]
                    (if (identical? k v)
                        tbox
                        (.valAt ^clojure.lang.ILookup under k not-found)))
             clojure.lang.Associative
             (containsKey [_ k]
                          (or (identical? k v)
                              (.containsKey ^clojure.lang.Associative under k)))
             (entryAt [_ k]
                      (if (identical? k v)
                          entry
                          (.entryAt ^clojure.lang.Associative under k)))
             (assoc [_ k val]
                    (if (identical? k v)
                        (OutOverlay. under v val (clojure.lang.MapEntry/create v val))
                        (OutOverlay. (.assoc ^clojure.lang.Associative under k val) v tbox entry)))
             clojure.lang.IPersistentCollection
             (cons [this o]
                   (if (instance? java.util.Map$Entry o)
                       (.assoc this (.getKey ^java.util.Map$Entry o)
                               (.getValue ^java.util.Map$Entry o))
                       this))
             (empty [_]
                    (OutOverlay. clojure.lang.PersistentHashMap/EMPTY v tbox entry))
             (equiv [_ o]
                    (and (instance? OutOverlay o)
                         (identical? v (.-v ^OutOverlay o))
                         (= tbox (.-tbox ^OutOverlay o))
                         (= under (.-under ^OutOverlay o))))
             (count [_]
                    (let [n (.count ^clojure.lang.Counted under)]
                         (if (.containsKey ^clojure.lang.Associative under v) n (inc n))))
             clojure.lang.Seqable
             (seq [_]
                  (cons entry (remove #(identical? (key %) v) (seq under))))
             clojure.lang.Counted)

    (defn- out-overlay [under v tbox]
           (OutOverlay. under v tbox (clojure.lang.MapEntry/create v tbox)))

     ;; Flag snapshot, keyed by the current Var Frame object. Binding
     ;; (including print-sequential's *print-level* bind inside
     ;; print-method) allocates a new Frame, so the next pr-on-any!
     ;; recomputes and sees the new values. When the frame holds none
     ;; of the print-flag vars, values are getRawRoot (no HashMap.find).
     ;; An OutOverlay with-out-str frame only rebinds *out*, so push/pop
     ;; retarget this cache at the new Frame identity and keep the values.
    (def ^:private flag-snap (ThreadLocal.))

    (def ^:private frame-class (Class/forName "clojure.lang.Var$Frame"))

    (defn- retarget-flag-snap!
           "If the snapshot was taken for from-frame, retarget it at to-frame.
        OutOverlay only rebinds *out*, so the flag values stay valid. Do
        not retarget a snapshot from some other frame (e.g. a *print-meta*
        binding that has not been snapshotted yet)."
           [from-frame to-frame]
           (when-let [c (.get ^ThreadLocal flag-snap)]
                     (when (identical? (aget ^objects c 0) from-frame)
                           (aset ^objects c 0 to-frame))))

    (defn- frame-holds-print-flags? [bmap]
           (let [^objects vs print-vs
                 ^clojure.lang.Associative m bmap]
                (or (.containsKey m (aget vs 1))
                    (.containsKey m (aget vs 2))
                    (.containsKey m (aget vs 3))
                    (.containsKey m (aget vs 4))
                    (.containsKey m (aget vs 5))
                    (.containsKey m (aget vs 6))
                    (.containsKey m (aget vs 7)))))

    (defn- snapshot-print-flags
           "Object array: [frame dup level length nsmaps meta readably flush?].
        Cached for this thread until the binding frame changes. An OutOverlay
        push retargets the cached frame pointer so ARM1 does not resnapshot."
           []
           (let [frame (clojure.lang.Var/getThreadBindingFrame)
                 ^objects c (.get ^ThreadLocal flag-snap)]
                (if (and c (identical? (aget c 0) frame))
                    c
                    (let [^objects vs print-vs
                          snap (object-array 8)
                          root? (not (frame-holds-print-flags? (frame-bindings)))]
                         (aset snap 0 frame)
                         (dotimes [i 7]
                                  (let [^clojure.lang.Var v (aget vs (inc i))]
                                       (aset snap (inc i)
                                             (if root? (.getRawRoot v) (.deref v)))))
                         (.set ^ThreadLocal flag-snap snap)
                         snap))))))

;; Used from the :clj arms of the print machinery below. Kondo
;; does not credit a use inside a reader conditional against a
;; def whose value is itself a reader conditional (minimal repro
;; in the syntax lane's STATUS.md), so the linter's unused-var
;; warning for this def is a proven false positive.
#_{:clj-kondo/ignore [:unused-private-var]}
(def ^:private out-save
     #?(:clj (ThreadLocal.)
        :default nil))

(defn push-out!
      "Bind cohost.core/*out* to s. If *out* is already thread-bound, set!
   the existing box and remember the previous value (no new binding
   frame). Otherwise push a Frame that overlays *out* on the current
   bindings without PersistentHashMap.assoc. set! and get-thread-bindings
   work during the body; popThreadBindings restores the previous frame
   (including on throw). Used by the installed with-out-str."
      [s]
      #?(:clj
         (let [^clojure.lang.Var v (out-var)
               tb (.getThreadBinding v)]
              (if (some? tb)
                  (do (.set ^ThreadLocal out-save (cons (.deref v) (.get ^ThreadLocal out-save)))
                      (.set v s)
                      nil)
                  (let [prev (clojure.lang.Var/getThreadBindingFrame)
                        overlay (out-overlay (frame-bindings) v (make-tbox s))
                        neu (make-frame overlay prev)]
                       (mark-thread-bound! v)
                       (when-let [vf (.getValidator v)]
                                 (when-not (boolean (vf s))
                                           (throw (IllegalStateException. "Invalid reference state"))))
                       (clojure.lang.Var/resetThreadBindingFrame neu)
                       (retarget-flag-snap! prev neu)
                       (.set ^ThreadLocal out-save (cons neu (.get ^ThreadLocal out-save)))
                       nil)))
         :default
         (do #_{:clj-kondo/ignore [:unresolved-symbol]} (push-thread-bindings {(out-var) s}) nil)))

(defn pop-out!
      []
      #?(:clj
         (let [st (.get ^ThreadLocal out-save)
               old (first st)]
              (.set ^ThreadLocal out-save (next st))
              (if (.isInstance ^Class frame-class old)
                  (do (clojure.lang.Var/popThreadBindings)
                      (retarget-flag-snap! old (clojure.lang.Var/getThreadBindingFrame)))
                  (.set ^clojure.lang.Var (out-var) old))
              nil)
         :default
         (do #_{:clj-kondo/ignore [:unresolved-symbol]} (pop-thread-bindings) nil)))

#?(:clj (defn append-newline!
              "Write the platform newline to w. Layer writers use append-text!;
   anything else (pretty-writer, a real java.io.Writer) uses .append."
              [w]
              (do (if (or (instance? WriterValue w) (instance? BufferedWriterValue w))
                      (append-text! w system-newline)
                      (.append ^java.lang.Appendable w (str system-newline)))
                  nil))
   :default (defn append-newline! [_w] nil))

(defn flush-writer! [w]
  ;; A JVM StringWriter with its buffer on `under` has no state atom.
      (if #_{:clj-kondo/ignore [:constant-condition]} (string-builder-under w)
          nil
          (let [st @(state-of w)]
               (cond (= ::sink (:cohost/kind w))
                     (let [b (text-buf-str (:buf st))]
                          (when (pos? (count b))
                                (swap! (state-of w) assoc :buf (new-text-buf))
                                ((:sink st) b)))
                     (:file st) (write-file-text! (:file st) (text-buf-str (:text st)))
                     (:out st) (flush-output! (:out st))
                     (:handle st) (host/io-flush! ((:handle st))))
               nil)))

#?(:clj (defn flush-any!
              "Flush w without re-reading *out*. Layer writers use flush-writer!;
   a java.io.Flushable gets .flush; anything else is a no-op."
              [w]
              (do (if (or (instance? WriterValue w) (instance? BufferedWriterValue w))
                      (flush-writer! w)
                      (when (instance? java.io.Flushable w)
                            (.flush ^java.io.Flushable w)))
                  nil))
   :default (defn flush-any! [_w] nil))

#?(:clj (defn pr-on-any!
              "Host pr-on of x onto an already-read writer w. Print flags are
   snapshotted once per binding frame (not per nested value). A
   binding inside print-method / print-sequential changes the frame,
   so the next call sees the new values. *out* is not reread."
              [x w]
              (do (let [^objects snap (snapshot-print-flags)]
                       (if (aget snap 1)
                           (let [^clojure.lang.Var pdf (aget ^objects print-vs 9)]
                                ((.deref pdf) x w))
                           (fast-pr-on! x w
                                        (aget snap 2)
                                        (aget snap 3)
                                        (aget snap 4)
                                        (aget snap 5)
                                        (aget snap 6)
                                        fallback-print-method)))
                  nil))
   :default (defn pr-on-any! [_x _w] nil))

(defn- current-out
       ^Object []
       #?(:clj
          (let [^objects vs print-vs
                ^clojure.lang.Var v (aget vs 0)]
               (.deref v))
          :default @(out-var)))

(defn- flush-on-newline?
       []
       #?(:clj (aget ^objects (snapshot-print-flags) 7)
          :default nil))

#?(:clj (defn- needs-flush?
               "prn's *flush-on-newline* flush. A StringWriter whose buffer is on
   `under` is a no-op in flush-writer!; skip the call."
               [w]
               (if (and (instance? WriterValue w)
                        (instance? StringBuilder (.under ^WriterValue w)))
                   false
                   (boolean (flush-on-newline?))))
   :default (defn- needs-flush? [_w] (boolean (flush-on-newline?))))

(defn prn0!
      "0-arg prn: newline + optional flush, *out* read once."
      []
      (let [w (current-out)]
           (append-newline! w)
           (when (needs-flush? w)
                 (flush-any! w))
           nil))

(defn prn1!
      "1-arg prn: deref *out* once, print, newline, optional flush."
      [x]
      (let [w (current-out)]
           (pr-on-any! x w)
           (append-newline! w)
           (when (needs-flush? w)
                 (flush-any! w))
           nil))

(defn prn*!
      "Variadic prn of x then more, *out* read once."
      [x more]
      (let [w (current-out)]
           (pr-on-any! x w)
           (doseq [y more]
                  (if (writer-value? w)
                      (append-text! w " ")
                      #?(:clj (.append ^java.lang.Appendable w " ") :default nil))
                  (pr-on-any! y w))
           (append-newline! w)
           (when (needs-flush? w)
                 (flush-any! w))
           nil))

(defn close-writer! [w]
      (if #_{:clj-kondo/ignore [:constant-condition]} (string-builder-under w)
          nil
          (when-not (:closed? @(state-of w))
                    (flush-writer! w)
                    (swap! (state-of w) assoc :closed? true)
                    (when-let [out (:out @(state-of w))]
                              (close-output! out))
                    (when-let [cf (:close @(state-of w))]
                              (cf))))
      nil)

;; ---------------------------------------------------------------------------
;; the boot's class loader: a standin whose lookups answer nil -- the
;; boot loads libraries through its own roots, not a classpath, so a
;; resource is simply not found (stock's answer for an absent name).

(def class-loader {:cohost/kind ::class-loader})

(defn inet-address? [x] (and (map? x) (= ::inet-address (:cohost/kind x))))
(defn inet-host [a] (:host a))

(defn unconnected-socket
      "A Socket value with no connection: its pipes are born closed, so
   reads see EOF and writes refuse as closed."
      []
      (let [p1 (pipe) p2 (pipe)]
           (swap! p1 assoc :closed? true)
           (swap! p2 assoc :closed? true)
           (socket-over p1 p2 nil)))

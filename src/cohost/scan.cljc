;; Source text by a scan of its characters, on every host: where a form ends,
;; and reader conditionals resolved by cohost, for every host alike (D-11:
;; each target's ranking of the platform keys picks the branch). The
;; ClojureWasm adapter reads no stream, so it finds each top-level form's
;; extent here; adapter/read-forms resolves #? here before a host's reader
;; sees the text. cs is a vector of the text's characters (nth of a string
;; walks it from the start on some hosts).
(ns cohost.scan)

(def ^:private delims #{\( \) \[ \] \{ \} \" \;})
(defn- ws? [c] (or (= c \space) (= c \,) (= c \newline) (= c \tab) (= c \return) (= c \formfeed)))

(defn skip-ws
  "The index of the next form at or after i, past whitespace, commas and
   ; comments."
  [cs i]
  (let [n (count cs)]
    (loop [i i]
      (if (>= i n)
        n
        (let [c (nth cs i)]
          (cond (ws? c) (recur (inc i))
                (= c \;) (recur (loop [j i] (if (or (>= j n) (= (nth cs j) \newline)) j (recur (inc j)))))
                :else i))))))

(defn- token-end [cs i]
  (let [n (count cs)]
    (loop [i i]
      (if (or (>= i n) (let [c (nth cs i)] (or (ws? c) (contains? delims c)))) i (recur (inc i))))))

(defn- string-end "i at the opening quote -> past the closing one" [cs i]
  (let [n (count cs)]
    (loop [i (inc i)]
      (if (>= i n)
        n
        (let [c (nth cs i)]
          (cond (= c \\) (recur (+ i 2)) (= c \") (inc i) :else (recur (inc i))))))))

(defn- at [cs i] (when (< i (count cs)) (nth cs i)))

(declare form-end)
(defn- coll-end [cs i close]
  (let [n (count cs)]
    (loop [i (skip-ws cs i)]
      (cond (>= i n) n
            (= (nth cs i) close) (inc i)
            :else (recur (skip-ws cs (form-end cs i)))))))

(defn form-end
  "The index past the form starting at i (a #_ discard runs on to the form
   after it)."
  [cs i]
  (if (>= i (count cs))
    i
    (let [c (nth cs i)
          after (fn [k] (form-end cs (skip-ws cs k)))]
      (case c
        \( (coll-end cs (inc i) \))
        \[ (coll-end cs (inc i) \])
        \{ (coll-end cs (inc i) \})
        \" (string-end cs i)
        \\ (token-end cs (+ i 2))
        (\' \` \@) (after (inc i))
        \~ (after (if (= \@ (at cs (inc i))) (+ i 2) (inc i)))
        \^ (after (form-end cs (skip-ws cs (inc i))))
        \# (let [d (at cs (inc i))]
             (case d
               (\( \{) (form-end cs (inc i))
               \" (string-end cs (inc i))
               (\' \=) (after (+ i 2))
               (\_ \^) (after (form-end cs (skip-ws cs (+ i 2))))
               \? (after (if (= \@ (at cs (+ i 2))) (+ i 3) (+ i 2)))
               \: (after (token-end cs (+ i 2)))
               \# (token-end cs (+ i 2))
               ;; a tagged literal: the tag, then its form
               (after (token-end cs (inc i)))))
        (token-end cs i)))))


;; ---------------------------------------------------------------------------
;; Reader conditionals in user and dependency code (D-11). cohost chooses each
;; #? branch itself, by its target's ranking of the platform keys: the branch
;; whose key ranks first wins, wherever it stands in the conditional (on the
;; JVM's reader the first matching branch in source order wins). A key the
;; ranking does not name is never taken; with no ranked branch the
;; conditional reads as nothing. cohost's own sources are not read here: each
;; host's reader reads their #? natively.

(def rankings
  "Every target's ranking of the reader-conditional keys, best first, by the
   adapter's :name. Every target but jank ranks :clj, then :default: the
   branch the JVM's Clojure takes. Jank ranks its own key first, so a
   #?(:jank ...) arm -- the C++ passthrough's home -- is the branch taken
   there, falling back to :clj."
  {:clj [:clj :default]
   :jolt [:clj :default]
   :lpy [:clj :default]
   :phel [:clj :default]
   :cljs [:clj :default]
   :cljw [:clj :default]
   :clje [:clj :default]
   :cljr [:clj :default]
   :cljd [:clj :default]
   :teavm [:clj :default]
   :lg [:clj :default]
   :jank [:jank :clj :default]
   :cljrs [:clj :default]})

(def default-ranking [:clj :default])

(def clj-arm-route
  "When set, a fn of the raw :clj arm source. It returns text the host
   reader keeps in place of that arm. Nil leaves the arm as source, which
   is every host's behavior except a jolt process whose :clj leg is
   champaca. The fn is installed from the config's clj-leg resolution."
  (atom nil))

(def clj-arm-eval
  "Host-side fn of the arm source. eval-top calls it instead of the
   booted core when the reader sees the route's marker form."
  (atom nil))

(defn ranking "target (the adapter's :name) -> its ranking." [target]
  (get rankings target default-ranking))

(def key-hosts
  "The host a key's code was written for: the provenance of a branch's forms."
  {:clj :java :bb :java :cljs :js :cljr :clr :cljd :dart :lpy :python :jank :cpp})

(def key-exts
  "The source file extension each key names: a namespace with several files
   (foo.clj, foo.cljc, ...) loads the one whose key ranks first."
  {:clj ".clj" :cljs ".cljs" :cljr ".cljr" :cljd ".cljd" :lpy ".lpy" :jank ".jank" :bb ".bb" :default ".cljc"})

(defn source-exts "ranking -> the extensions to look for, best first." [ranking]
  (vec (keep key-exts ranking)))

(defn ext-key "a path -> the key its extension names (:default for .cljc), or nil." [path]
  (some (fn [[k ext]] (let [n (count path) e (count ext)]
                        (when (and (> n e) (= ext (subs path (- n e)))) k)))
        key-exts))

(defn- blank
  "s with every character but a line break a space: dropped text keeps its
   room, so every form after it keeps its line and column."
  [s]
  (apply str (map (fn [c] (if (or (= c \newline) (= c \return)) c \space)) s)))

(defn- rank-of [ranking k]
  (loop [r (seq ranking) n 0]
    (cond (nil? r) nil (= k (first r)) n :else (recur (next r) (inc n)))))

(declare resolve*)

(defn- branch
  "The reader conditional at i -> [text end spans]: text is as long as the
   conditional, its best-ranked branch in place (a #?@ branch's collection by
   its elements) and the rest blank; spans are [start end key] of each branch
   taken, in src's indices."
  [src cs i ranking]
  (let [splice? (= \@ (at cs (+ i 2)))
        end (form-end cs i)
        open (skip-ws cs (+ i (if splice? 3 2)))
        best (loop [k (skip-ws cs (inc open)) best nil]
               (if (or (>= k end) (= \) (nth cs k)))
                 best
                 (let [fe (token-end cs k)
                       b (skip-ws cs fe)
                       be (form-end cs b)
                       r (rank-of ranking (subs src k fe))]
                   (recur (skip-ws cs be)
                          (if (and r (or (nil? best) (< r (first best)))) [r (subs src k fe) b be] best)))))]
    (if best
      (let [[_ k b be] best
            [s e] (if splice? [(inc b) (dec be)] [b be])
            raw (subs src s e)
            route (when (= k ":clj") @clj-arm-route)]
        (if route
          [(str (blank (subs src i s)) (route raw) (blank (subs src e end)))
           end
           [[s e :clj]]]
          (let [[text spans] (resolve* raw ranking)]
            [(str (blank (subs src i s)) text (blank (subs src e end)))
             end
             (conj (mapv (fn [[a z k']] [(+ a s) (+ z s) k']) spans) [s e (keyword (subs k 1))])])))
      [(blank (subs src i end)) end []])))

(defn- resolve*
  "-> [text spans]; ranking holds the keys as text (\":clj\")."
  [src ranking]
  (let [cs (vec src) n (count cs)]
    (loop [i 0 start 0 out [] spans []]
      (if (>= i n)
        [(if (empty? out) src (apply str (conj out (subs src start n)))) spans]
        (let [c (nth cs i)]
          (cond
            (or (ws? c) (contains? #{\( \) \[ \] \{ \} \' \` \~ \@ \^} c)) (recur (inc i) start out spans)
            (= c \;) (recur (loop [j i] (if (or (>= j n) (= (nth cs j) \newline)) j (recur (inc j)))) start out spans)
            (= c \") (recur (string-end cs i) start out spans)
            (= c \\) (recur (token-end cs (+ i 2)) start out spans)
            (= c \#) (let [d (at cs (inc i))]
                       (cond (= d \") (recur (string-end cs (inc i)) start out spans)
                             (= d \?) (let [[text end sp] (branch src cs i ranking)]
                                        (recur end end (conj out (subs src start i) text) (into spans sp)))
                             :else (recur (inc i) start out spans)))
            :else (recur (token-end cs i) start out spans)))))))

(defn resolve-conditionals+spans
  "src with each reader conditional, at any depth, replaced by its branch
   that ranking (keywords, best first) picks -> [text spans]. text is as long
   as src and every kept character stays where it was; spans are [start end
   key], one per branch taken, innermost first."
  [src ranking]
  (resolve* src (mapv str ranking)))

(defn resolve-conditionals
  "src with each reader conditional replaced by the branch ranking picks."
  [src ranking]
  (first (resolve-conditionals+spans src ranking)))

;; Provenance: which key's code a form was read from, for the codemod. It is
;; kept beside the read, never on the forms: a form read from a branch
;; carries the meta it has on the JVM and nothing else.

(def ^:private reading (atom ()))

(defn- top-extents [text]
  (let [cs (vec text) n (count cs)]
    (loop [i (skip-ws cs 0) out []]
      (if (>= i n) out (let [j (form-end cs i)] (recur (skip-ws cs j) (conj out [i j])))))))

(defn- line-starts [text]
  (let [n (count text)]
    (loop [i 0 out [0]]
      (if (>= i n) out (recur (inc i) (if (= \newline (nth text i)) (conj out (inc i)) out))))))

(defn- line-of
  "The 1-based line of offset i, given line-starts: the line whose start
   is the greatest start <= i."
  [starts i]
  (loop [lo 1 hi (count starts)]
    (if (>= lo hi)
      lo
      (let [mid (quot (+ lo hi) 2)]
        (if (<= (nth starts mid) i)
          (recur (inc mid) hi)
          (recur lo mid))))))

(defn form-start
  "text, n (0-based) -> the [line column] (both 1-based) at which text's
   nth top-level form starts, or nil when text has fewer forms: the
   position a reader failing at that form reports."
  [text n]
  (when (and (some? n) (<= 0 n))
    (let [ext (top-extents text)]
      (when (< n (count ext))
        (let [a (nth (nth ext n) 0)
              starts (line-starts text)
              line (line-of starts a)]
          [line (inc (- a (nth starts (dec line))))])))))

(defn with-source
  "Run thunk as the read of text (resolved) with its spans; path (or nil) is
   where it came from. thunk gets a fn of no args that read-forms calls as
   each top-level form is read (before its cb), so provenance-key knows which
   one is being read. Nests: a load inside a form is read inside it."
  [text spans path thunk]
  (let [info (atom {:text text :spans spans :path path :file-key (when path (ext-key path)) :count 0})]
    (swap! reading conj info)
    (try (thunk (fn [] (swap! info update :count inc) nil))
         (finally (swap! reading rest)))))

(defn source-path
  "The path the innermost active read is reading (what with-source was
   given), or nil outside a read and for text with no file. A host that
   reads a file's forms one by one names their source with it."
  []
  (when-let [info (first @reading)] (:path @info)))

(defn- index-in [info line col]
  (let [starts (or (:starts @info) (let [s (line-starts (:text @info))] (swap! info assoc :starts s) s))]
    (when (and (number? line) (number? col) (<= 1 line (count starts)))
      (+ (nth starts (dec line)) (dec col)))))

(defn provenance-key
  "The key whose code form was read from, while it is being read (the
   codemod's question): the innermost branch holding the form's position
   (its :line/:column, when the host's reader gives them), else the branch
   holding the whole top-level form being read, else the file's key (:clj for
   a .clj, :default for a .cljc); nil for text with no file outside any
   branch, and outside a read."
  [form]
  (when-let [info (first @reading)]
    (let [{spans :spans file-key :file-key n-read :count} @info
          ext (or (:extents @info) (let [e (top-extents (:text @info))] (swap! info assoc :extents e) e))
          [a z] (when (pos? n-read) (get ext (dec n-read)))
          m (meta form)
          idx (when (and a m) (index-in info (:line m) (:column m)))
          ;; a host that reads each top-level form's text alone gives lines
          ;; relative to it
          idx (if (and idx (<= a idx) (< idx z)) idx
                  (when (and a m (:line m) (:column m))
                    (let [rel (vec (line-starts (subs (:text @info) a z)))
                          l (:line m)]
                      (when (<= 1 l (count rel)) (+ a (nth rel (dec l)) (dec (:column m)))))))
          holding (fn [lo hi] (some (fn [[s e k]] (when (and (<= s lo) (<= hi e)) k)) spans))]
      (or (when idx (holding idx (inc idx)))
          (when a (holding a z))
          file-key))))

(defn provenance
  "The host form's code was written for (:clj -> :java, :cljs -> :js, ...),
   from provenance-key; nil for portable code (:default) and outside a read."
  [form]
  (get key-hosts (provenance-key form)))

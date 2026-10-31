;; Source text by a scan of its characters, on every host: where a form ends,
;; and reader conditionals resolved as the JVM reads a .cljc. The ClojureWasm
;; adapter reads no stream, so it finds each top-level form's extent here;
;; adapter/read-forms resolves #? here before a host's reader sees the text.
;; cs is a vector of the text's characters (nth of a string walks it from the
;; start on some hosts).
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

(declare resolve-conditionals)

(defn- branch
  "The text a reader conditional at i reads as: its first branch whose feature
   is one of features (strings, \":clj\") or :default — a #?@ branch's
   collection by its elements — or nothing. -> [text end]"
  [src cs i features]
  (let [splice? (= \@ (at cs (+ i 2)))
        end (form-end cs i)
        open (skip-ws cs (+ i (if splice? 3 2)))]
    (loop [k (skip-ws cs (inc open))]
      (if (or (>= k end) (= \) (nth cs k)))
        ["" end]
        (let [fe (token-end cs k)
              b (skip-ws cs fe)
              be (form-end cs b)]
          (if (or (contains? features (subs src k fe)) (= ":default" (subs src k fe)))
            [(resolve-conditionals (if splice? (subs src (inc b) (dec be)) (subs src b be)) features) end]
            (recur (skip-ws cs be))))))))

(defn resolve-conditionals
  "src with each reader conditional replaced by the text of the branch the
   JVM's reader takes for features (#{\":clj\"} there), at any depth."
  [src features]
  (let [cs (vec src) n (count cs)]
    (loop [i 0 start 0 out []]
      (if (>= i n)
        (if (zero? start) src (apply str (conj out (subs src start n))))
        (let [c (nth cs i)]
          (cond
            (or (ws? c) (contains? #{\( \) \[ \] \{ \} \' \` \~ \@ \^} c)) (recur (inc i) start out)
            (= c \;) (recur (loop [j i] (if (or (>= j n) (= (nth cs j) \newline)) j (recur (inc j)))) start out)
            (= c \") (recur (string-end cs i) start out)
            (= c \\) (recur (token-end cs (+ i 2)) start out)
            (= c \#) (let [d (at cs (inc i))]
                       (cond (= d \") (recur (string-end cs (inc i)) start out)
                             (= d \?) (let [[text end] (branch src cs i features)]
                                        (recur end end (conj out (subs src start i) text)))
                             :else (recur (inc i) start out)))
            :else (recur (token-end cs i) start out)))))))

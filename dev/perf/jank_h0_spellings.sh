#!/usr/bin/env bash
# jank-FAST Step 5 H0. Which hint spellings jank 0.1 accepts.
# Raw jank, no cohost. One process per form: a bad hint aborts the file.
#
#   bash dev/perf/jank_h0_spellings.sh
set -u
root=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

run() {
  local name="$1"
  local src="$2"
  printf '%s\n' "$src" > "$work/$name.jank"
  jank run -O3 --eager "$work/$name.jank" >"$work/$name.out" 2>"$work/$name.err"
  local ec=$?
  local headline
  headline=$(awk '
    /^error: / { sub(/^error: /, ""); print; exit }
    /^[A-Za-z0-9].*/ && !seen && NR>1 { }
  ' "$work/$name.err")
  if [[ -z "$headline" ]]; then
    headline=$(tr '\n' ' ' < "$work/$name.out" | sed 's/  */ /g')
  fi
  printf 'SPELL %-22s exit=%s %s\n' "$name" "$ec" "$headline"
}

run bare-double '(defn f [^double a] a) (println (f 1.25) (type (f 1.25)))'
run bare-double-int '(defn f [^double a] a) (println (f 1))'
run bare-long '(defn f [^long a] a) (println (f 7) (type (f 7)))'
run bare-long-real '(defn f [^long a] a) (println (f 1.5))'
run bare-long-big '(defn f [^long a] a) (println (f 3000000000) (type (f 3000000000)))'
run bare-int '(defn f [^int a] a) (println (f 7))'
run bare-int-real '(defn f [^int a] a) (println (f 1.5))'
run bare-int-big '(defn f [^int a] a) (println (f 3000000000))'
run bare-bool '(defn f [^bool a] a) (println (f true) (f false) (type (f true)))'
run bare-bool-int '(defn f [^bool a] a) (println (f 1))'
run bare-float '(defn f [^float a] a) (println (f 1.25) (type (f 1.25)))'
run cpp-double '(defn f [^cpp/double a] a) (println (f 1.25) (type (f 1.25)))'
run cpp-long '(defn f [^cpp/long a] a) (println (f 3000000000))'
run cpp-int '(defn f [^cpp/int a] a) (println (f 3000000000))'
run cpp-bool '(defn f [^cpp/bool a] a) (println (f true))'
run cpp-bool-int '(defn f [^cpp/bool a] a) (println (f 1))'
run string-double '(defn f [^"double" a] a) (println (f 1.25))'
run string-long '(defn f [^"long" a] a) (println (f 1))'
run string-bool '(defn f [^"bool" a] a) (println (f true))'
run string-int '(defn f [^"int" a] a) (println (f 1))'
run jank-colon-i64 '(defn f [^jank::i64 a] a) (println (f 1))'
run jank-dot-i64 '(defn f [^jank.i64 a] a) (println (f 3000000000))'
run jtl-i64 '(defn f [^jtl.i64 a] a) (println (f 3000000000))'
run jtl-f64 '(defn f [^jtl.f64 a] a) (println (f 1.25))'
run jtl-i32 '(defn f [^jtl.i32 a] a) (println (f 3000000000))'
run keyword-double '(defn f [^:double a] a) (println (f 1) (f 1.25))'
run map-symbol '(defn f [^{:tag double} a] a) (println (f 1.25))'
run map-string '(defn f [^{:tag "double"} a] a) (println (f 1.25))'
run map-quoted '(defn f [^{:tag (quote double)} a] a) (println (f 1.25) (type (f 1.25)))'
run list-cpp '(defn f [^(cpp/double) a] a) (println (f 1.25))'
run ret-double '(defn ^double f [] 3000000000) (println (f))'
run let-int-real '(println (let [^int a 1.5] a))'
run let-int-big '(println (let [^int a 3000000000] a))'
run let-plain-big '(println (let [a 3000000000] a))'
run let-double-int '(println (let [^double a 1] a))'
run loop-long '(println (loop [^long i 0] (if (< i 3) (recur (inc i)) i)))'
run unresolved-boolean '(defn f [^boolean a] a) (println (f true))'
run unresolved-integer '(defn f [^integer a] a) (println (f 1))'
run unresolved-i64 '(defn f [^i64 a] a) (println (f 1))'

# How the reader stores the tag. One file, all of these read.
cat > "$work/read.jank" << 'EOF'
(defn show [label src]
  (let [form (read-string src)
        params (nth form 2)
        sym (nth params 0)]
    (println label (pr-str (meta sym)))))
(show "bare-double" "(defn f [^double a] a)")
(show "cpp-double" "(defn f [^cpp/double a] a)")
(show "bare-long" "(defn f [^long a] a)")
(show "bare-bool" "(defn f [^bool a] a)")
(show "bare-int" "(defn f [^int a] a)")
(show "jank-colon" "(defn f [^jank::i64 a] a)")
(show "jank-dot" "(defn f [^jank.i64 a] a)")
(show "jtl-i64" "(defn f [^jtl.i64 a] a)")
(show "keyword" "(defn f [^:double a] a)")
(show "map-symbol" "(defn f [^{:tag double} a] a)")
(show "map-string" "(defn f [^{:tag \"double\"} a] a)")
(show "map-quoted" "(defn f [^{:tag (quote double)} a] a)")
(show "list-cpp" "(defn f [^(cpp/double) a] a)")
(let [form (read-string "(defn ^double f [a] a)")]
  (println "ret-double" (pr-str (:tag (meta (nth form 1))))))
(let [form (read-string "(let [^double a 1.0] a)")]
  (println "let-double" (pr-str (:tag (meta (nth (nth form 1) 0))))))
(let [form (read-string "(loop [^long i 0] i)")]
  (println "loop-long" (pr-str (:tag (meta (nth (nth form 1) 0))))))
EOF
jank run -O3 --eager "$work/read.jank"

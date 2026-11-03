#!/usr/bin/env bash
# A5-8 (from U6-10): print cohost's rewritten form of each defn in a file (default: ../a5-8/loops.clj), on jank.
#   bash dev/research/u6-10/dump.sh [file] [name ...]   (JANK_BIN picks the binary)
set -u
f="${1:-dev/research/a5-8/loops.clj}"; shift || true
names="${*:-dbl kw ok iloop dloop mixed}"
export LC_ALL=C.UTF-8
aot=$(bb -e '(print (load-file "dev/perf/jank_aot_ensure.clj"))' 2>/dev/null | tail -1)
sel=$(printf "'%s " $names)
src="(let [ctx (cohost.boot/ctx) rdr (clojure.core/read-string (str \"[\" (slurp \"$f\") \"]\"))] (doseq [form rdr] (when (and (seq? form) (= 'defn (first form)) (contains? #{$sel} (second form))) (println \";; ----\" (second form)) (prn (cohost.rewrite/rewrite ctx form)))))"
"${JANK_BIN:-jank}" --module-path "$aot:src" run-main cohost.run -- --no-libs --eval "$src" 2>&1 | grep -v '^   =>'

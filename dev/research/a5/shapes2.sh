#!/usr/bin/env bash
# A5-2: each line of shapes2.txt evaluated on its own (one process; a native exception in one
# shape does not hide the others). Under cohost on jank (default), or JVM Clojure with `jvm`.
#   bash dev/research/a5/shapes2.sh [jvm]      (JANK_BIN picks the jank binary)
set -u
here=$(cd "$(dirname "$0")" && pwd)
mapfile -t exprs < "$here/shapes2.txt"
export LC_ALL=C.UTF-8
if [ "${1:-}" = jvm ]; then
  code="(doseq [s $(printf '%s\n' "${exprs[@]}" | bb -e '(prn (vec (line-seq (java.io.BufferedReader. *in*))))')] (println s \"=>\" (try (pr-str (eval (read-string s))) (catch Throwable t (str \"ERR \" (.getMessage t))))))"
  clojure -M -e "$code" 2>/dev/null
else
  aot=$(bb -e '(print (load-file "dev/perf/jank_aot_ensure.clj"))' 2>/dev/null | tail -1)
  stdbuf -oL -eL "${JANK_BIN:-jank}" -O3 --module-path "$aot:src" run-main cohost.run -- --no-libs --eval "${exprs[@]}" 2>&1
fi

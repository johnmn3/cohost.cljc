#!/usr/bin/env bash
# Eve-in-cohost unit 6, step 2: dev/u6/visit_probe.jank over a list of jank's clojure.core fns, one process each, in
# both modes. One line per fn and mode: "VISIT <mode> <fn> exit=<rc> <PROBE answer | the panic line | ->".
# Env: EVE_JANK_PATH (`bb classpath jank` in the Eve checkout under test), JANK_BIN, COHOST_AOT_DIR (optional:
# cohost's AOT objects, as bb boot:jank builds them, ahead of src on the module path -- a faster load).
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; repo="$(cd "$here/../.." && pwd)"
export LC_ALL="${LC_ALL:-en_US.UTF-8}"
fns=("$@"); [ ${#fns[@]} -eq 0 ] && fns=(coll? seq? seqable? sequential? map? vector? set? string? fn? ifn? counted? associative? empty? count seq str pr-str hash = identical? meta type get contains? conj nth)
for mode in converged plain; do
  for f in "${fns[@]}"; do
    out="$("${JANK_BIN:-jank}" --module-path "${COHOST_AOT_DIR:+$COHOST_AOT_DIR:}$repo/src:${EVE_JANK_PATH:-}" run "$here/visit_probe.jank" -- "$mode" "$f" 2>&1)"; rc=$?
    line="$(grep -m1 '^PROBE ' <<<"$out")"; [ -z "$line" ] && line="$(grep -m1 -E 'Panic|error:' <<<"$out")"
    echo "VISIT $mode $f exit=$rc ${line:--}"
  done
done

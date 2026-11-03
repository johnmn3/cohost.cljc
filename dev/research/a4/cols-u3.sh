#!/usr/bin/env bash
# A4 (c)(d)(e) (u3): the column run. Interleaves, per round: UNPATCHED base tree (BASE dir, apt),
# UNPATCHED + extension (this tree, apt), PATCHED + extension (this tree, PAT_BIN) when PAT_BIN is
# set; one JVM run first. Rounds 2.. of the base column skip the quadratic sort walks (SLOW rows,
# minutes each on apt) unless FULL_BASE=1.
#   BASE=<base tree> PAT_BIN=<series jank> ROUNDS=3 bash dev/research/a4/cols-u3.sh
set -u
here="$(pwd)"; probe=dev/research/a4/leaves.clj
fast="$(mktemp --suffix=.clj)"
grep -v -E '"sort-(reduce|nth-50k-of|rest-count)' "$probe" > "$fast"
export LC_ALL=C.UTF-8
echo "== JVM"; (cd "$here" && env -u LANG -u LC_ALL clojure -M dev/research/run-jvm.clj "$probe" 2>&1 | grep -v JAVA_TOOL)
for r in $(seq 1 "${ROUNDS:-3}"); do
  echo "== round $r UNPATCHED base ($BASE) load $(cut -d' ' -f1-3 /proc/loadavg)"
  p="$probe"; [ "$r" -gt 1 ] && [ "${FULL_BASE:-0}" != 1 ] && p="$fast"
  cp "$p" "$BASE/dev/research/a4/leaves-run.clj"
  (cd "$BASE" && bash dev/research/run-cohost-jank.sh dev/research/a4/leaves-run.clj 2>&1 | grep -E '^(check|sort|string|loop|apply|str|vec)')
  echo "== round $r UNPATCHED + extension load $(cut -d' ' -f1-3 /proc/loadavg)"
  (cd "$here" && bash dev/research/run-cohost-jank.sh "$probe" 2>&1 | grep -E '^(check|sort|string|loop|apply|str|vec)')
  if [ -n "${PAT_BIN:-}" ]; then
    echo "== round $r PATCHED + extension load $(cut -d' ' -f1-3 /proc/loadavg)"
    (cd "$here" && JANK_BIN="$PAT_BIN" bash dev/research/run-cohost-jank.sh "$probe" 2>&1 | grep -E '^(check|sort|string|loop|apply|str|vec)')
  fi
done
rm -f "$fast"

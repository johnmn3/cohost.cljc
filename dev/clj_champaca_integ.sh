#!/usr/bin/env bash
# Real in-process champaca check. Not part of `bb clj-body:test`.
#
# Needs jolt on PATH and a built cohost-clj bundle:
#   CHAMPACA_SRC=/path/to/patched/champaca bash dev/build-clj-bundle.sh
#   bash dev/clj_champaca_integ.sh
#
# The bundle default is $HOME/.cache/cohost/cohost-clj. COHOST_CLJ_BUNDLE
# overrides it. A machine with no `clojure` is the case this is for.
set -eu
root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
bundle="${COHOST_CLJ_BUNDLE:-$HOME/.cache/cohost/cohost-clj}"
command -v jolt >/dev/null 2>&1 || { echo "clj_champaca_integ: jolt is not on PATH" >&2; exit 1; }
[ -x "$bundle/lib/champaca" ] || { echo "clj_champaca_integ: no $bundle/lib/champaca (bash dev/build-clj-bundle.sh)" >&2; exit 1; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp "$root/dev/clj_champaca_integ.cljc" "$work/arm.cljc"
printf '%s\n' '{:paths ["."]}' > "$work/deps.edn"
# PATH without clojure: hide it if present, keep jolt and bb.
jolt_bin="$(command -v jolt)"
bb_bin="$(command -v bb || true)"
path="$(dirname "$jolt_bin")"
[ -n "$bb_bin" ] && path="$path:$(dirname "$bb_bin")"
out="$(PATH="$path" "$root/bin/cohost" :jolt "$work/arm.cljc" 2>"$work/err" || true)"
printf '%s\n' "$out"
if printf '%s\n' "$out" | grep -q 'CLJ-ARM'; then
  echo "clj_champaca_integ: :clj arm ran"
  exit 0
fi
echo "clj_champaca_integ: CLJ-ARM was not printed. stderr:" >&2
cat "$work/err" >&2
exit 1

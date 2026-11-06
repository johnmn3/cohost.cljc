#!/usr/bin/env bash
# Eve-in-cohost unit 6, step 1: the region leaves' check
# (doc/eve-cohost/UNIT6-DESIGN.md sections 3b, 6 step 1, 7.5). The jank seat's
# :region-open / :region-heap / :region-sync leaves, filled with Eve's region
# adapter (cohost.region-eve), are exercised through cohost.adapter only:
# a heap region round-trips a number; a file region written and synced by one
# process is read back by another that started after the writer exited; and
# (step 2) each region Eve makes is cohost's object carrying Eve's tag.
# Prints one verdict line:
#
#   REGION-CHECK PASS|FAIL|GATED  writer-pid=.. reader-pid=.. n=..  (reason)
#
# Usage:  dev/u6/region.sh
# Env:
#   EVE_JANK_PATH  the jank module path holding Eve's jank realization (`bb classpath
#                  jank` in an Eve checkout whose :jank row is native); unset = GATED
#   JANK_BIN       the jank binary (default: jank on PATH)
#   U6_DIR         where the region file and logs go (default: target/u6-region)
# Exit: 0 on PASS or GATED, 1 on FAIL.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
jank_bin="${JANK_BIN:-jank}"
out="${U6_DIR:-$repo/target/u6-region}"
# jank's lexer reads source through the locale; C.UTF-8 lexes but breaks #inst
# (eve/doc/hosts/jank.md, "The locale")
export LC_ALL="${LC_ALL:-en_US.UTF-8}"
rm -rf "$out"; mkdir -p "$out"
# Eve's completion header (eve/eve_obj.hpp, unit 6 option 3) on jank's include path: its src root on
# EVE_JANK_PATH, include for src; jank's clang reads CPLUS_INCLUDE_PATH
eve_inc="$(tr ':' '\n' <<<"${EVE_JANK_PATH:-}" | sed -n 's|adapters/completion/jank/src$|adapters/completion/jank/include|p' | paste -sd:)"
[ -n "$eve_inc" ] && export CPLUS_INCLUDE_PATH="$eve_inc${CPLUS_INCLUDE_PATH:+:$CPLUS_INCLUDE_PATH}"
path="$out/region.bin"
n=$(( ($$ * 7919 + $(date +%s)) % 2000000000 ))

run_role() {
  "$jank_bin" --module-path "$repo/dev:$repo/src${EVE_JANK_PATH:+:$EVE_JANK_PATH}" \
    run-main u6.region-check -- "$@"
}

field() { sed -n "s/.* $1=\([^ ]*\).*/\1/p" <<<"$2" | head -n 1; }

run_role write "$path" "$n" >"$out/write.log" 2>&1; we=$?
# the writer has exited here: what the reader sees is the file
run_role read "$path" >"$out/read.log" 2>&1; re=$?
wl="$(grep -m1 '^REGION ' "$out/write.log")"; rl="$(grep -m1 '^REGION ' "$out/read.log")"
if [ $we -eq 3 ] || [ $re -eq 3 ]; then
  echo "REGION-CHECK GATED  ($(grep -h -m1 '^GATED' "$out/write.log" "$out/read.log" | head -n 1))"
  exit 0
fi
why=""
[ $we -ne 0 ] && why="$why writer exit $we;"
[ $re -ne 0 ] && why="$why reader exit $re;"
[ -z "$wl" ] && why="$why no writer REGION line;"
[ -z "$rl" ] && why="$why no reader REGION line;"
wp="$(field pid "$wl")"; rp="$(field pid "$rl")"
if [ -n "$wl" ] && [ -n "$rl" ]; then
  b=$(( -n - 1 ))
  [ "$wp" = "$rp" ] && why="$why same pid;"
  for l in "$wl" "$rl"; do [ "$(field regions "$l")" = "true" ] || why="$why regions not true;"; done
  for l in "$wl" "$rl"; do [ "$(field conv "$l")" = "true" ] || why="$why region not cohost's object (conv);"; done
  [ "$(field heap0 "$wl")" = "0" ] || why="$why heap region not zeroed;"
  [ "$(field heap "$wl")" = "$n" ] || why="$why heap round trip;"
  [ "$(field a "$wl")" = "$n" ] || why="$why writer a;"
  [ "$(field b "$wl")" = "$b" ] || why="$why writer b;"
  [ "$(field a "$rl")" = "$n" ] || why="$why reader a;"
  [ "$(field b "$rl")" = "$b" ] || why="$why reader b;"
fi
if [ -z "$why" ]; then
  echo "REGION-CHECK PASS  writer-pid=$wp reader-pid=$rp n=$n"
else
  echo "REGION-CHECK FAIL  writer-pid=$wp reader-pid=$rp n=$n ($why logs: $out)"
  exit 1
fi

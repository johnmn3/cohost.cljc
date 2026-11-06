#!/usr/bin/env bash
# Eve-in-cohost unit 6 PREP: the disk-resident-root probe runner
# (doc/eve-cohost/UNIT6-DESIGN.md section 4). A root written by one process is
# answered, after that process has exited, by another process that never saw it
# in memory. Each leg prints one verdict line:
#
#   LEG <name> PASS|FAIL|GATED  writer-pid=.. reader-pid=.. digest=..  (reason)
#
# Legs (writer seat -> reader seat):
#   jvm         JVM -> JVM     Eve main on the JVM: the reference leg; its digest
#                              is the number the jank legs must reproduce.
#   jank        jank -> jank   the unit-6 gate item. GATED until Eve loads on jank
#                              (the Eve-on-jank flagship gate).
#   jvm-jank    JVM -> jank    cross-seat: one on-disk format, two seats.
#   jank-jvm    jank -> JVM    the converse.
#
# Usage:  dev/u6/residence.sh [leg ...]          (default: all four)
# Env:
#   EVE_SRC        an Eve checkout (github.com/SeniorCareMarket/eve); required
#   EVE_JVM_CP     the JVM classpath holding Clojure 1.12 + Eve's JVM paths; when
#                  unset it is built from CLOJURE_JARS (a dir of clojure, spec.alpha
#                  and core.specs.alpha jars) + Eve's deps.edn :paths
#   EVE_JANK_PATH  the jank module path holding Eve's jank realization (the
#                  flagship's; e.g. its src + jank adapter dirs); unset = jank legs
#                  run with no Eve on the path and report GATED
#   JANK_BIN       the jank binary (default: jank on PATH)
#   U6_DIR         where domains and logs go (default: target/u6-residence)
# Exit: 0 when no leg FAILed (GATED legs do not fail the run), 1 otherwise.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
probe="$here/residence_probe.cljc"
jank_bin="${JANK_BIN:-jank}"
out="${U6_DIR:-$repo/target/u6-residence}"
export LANG="${LANG:-C.UTF-8}"   # jank's lexer wants a UTF-8 locale (dev/install/jank-src.sh)
mkdir -p "$out"
# Eve's completion header (eve/eve_obj.hpp, unit 6 option 3) on jank's include path: its src root on
# EVE_JANK_PATH, include for src; jank's clang reads CPLUS_INCLUDE_PATH
eve_inc="$(tr ':' '\n' <<<"${EVE_JANK_PATH:-}" | sed -n 's|adapters/completion/jank/src$|adapters/completion/jank/include|p' | paste -sd:)"
[ -n "$eve_inc" ] && export CPLUS_INCLUDE_PATH="$eve_inc${CPLUS_INCLUDE_PATH:+:$CPLUS_INCLUDE_PATH}"

jvm_cp() {
  if [ -n "${EVE_JVM_CP:-}" ]; then echo "$EVE_JVM_CP"; return; fi
  local cp="" j="${CLOJURE_JARS:-}" p
  [ -n "$j" ] && cp="$(ls "$j"/*.jar | paste -sd:)"
  if [ -n "${EVE_SRC:-}" ]; then
    # Eve's :paths for the JVM (hosts.edn :jvm), read from its deps.edn
    for p in $(sed -n '/^ *{:paths \[/,/\]/p' "$EVE_SRC/deps.edn" | head -n 4 | grep -o '"[^"]*"' | tr -d '"'); do
      cp="$cp:$EVE_SRC/$p"
    done
  fi
  echo "${cp#:}"
}

# run_role <seat> <role> <base> [tag] -> stdout+stderr to the log, exit code returned
run_role() {
  local seat="$1" role="$2" base="$3" tag="${4:-}"
  case "$seat" in
    jvm)  java -cp "$(jvm_cp)" clojure.main "$probe" "$role" "$base" $tag ;;
    jank) "$jank_bin" --module-path "$repo/dev${EVE_JANK_PATH:+:$EVE_JANK_PATH}" \
            run-main u6.residence-probe -- "$role" "$base" $tag ;;
  esac
}

field() { sed -n "s/.* $1=\([^ ]*\).*/\1/p" <<<"$2" | head -n 1; }
value() { sed -n 's/.* value=//p' <<<"$1" | head -n 1; }

leg() {
  local name="$1" wseat="$2" rseat="$3"
  local d="$out/$name" tag="u6-$name-$$-$(date +%s)"
  rm -rf "$d"; mkdir -p "$d"
  local base="$d/domain"
  run_role "$wseat" write "$base" "$tag" >"$d/write.log" 2>&1; local we=$?
  # the writer process has exited here: everything the reader sees is on disk
  run_role "$rseat" read "$base" >"$d/read.log" 2>&1; local re=$?
  local wl rl
  wl="$(grep -m1 '^ROOT ' "$d/write.log")"; rl="$(grep -m1 '^ROOT ' "$d/read.log")"
  if [ $we -eq 3 ] || [ $re -eq 3 ]; then
    echo "LEG $name GATED  ($(grep -h -m1 '^GATED' "$d/write.log" "$d/read.log" | head -n 1))"
    return 0
  fi
  local wp rp wd rd
  wp="$(field pid "$wl")"; rp="$(field pid "$rl")"; wd="$(field digest "$wl")"; rd="$(field digest "$rl")"
  local why=""
  [ $we -ne 0 ] && why="$why writer exit $we;"
  [ $re -ne 0 ] && why="$why reader exit $re;"
  [ -z "$wl" ] && why="$why no writer ROOT line;"
  [ -z "$rl" ] && why="$why no reader ROOT line;"
  [ -n "$wl" ] && [ -n "$rl" ] && {
    [ "$wp" = "$rp" ] && why="$why same pid;"
    [ "$wd" != "$rd" ] && why="$why digest differs;"
    [ "$wd" = "nil" ] && why="$why no digest;"
    # printed values are compared within one seat only: two seats' printers may
    # order a hash map's entries differently; across seats the digest decides
    [ "$wseat" = "$rseat" ] && [ "$(value "$wl")" != "$(value "$rl")" ] && why="$why value differs;"
    grep -q -- "$tag" <<<"$rl" || why="$why reader lacks this run's tag;"
  }
  if [ -z "$why" ]; then
    echo "LEG $name PASS  writer-pid=$wp reader-pid=$rp digest=$wd"
  else
    echo "LEG $name FAIL  writer-pid=$wp reader-pid=$rp ($why logs: $d)"
    return 1
  fi
}

legs=("$@"); [ ${#legs[@]} -eq 0 ] && legs=(jvm jank jvm-jank jank-jvm)
rc=0
for l in "${legs[@]}"; do
  case "$l" in
    jvm)      leg jvm jvm jvm || rc=1 ;;
    jank)     leg jank jank jank || rc=1 ;;
    jvm-jank) leg jvm-jank jvm jank || rc=1 ;;
    jank-jvm) leg jank-jvm jank jvm || rc=1 ;;
    *) echo "unknown leg: $l"; rc=1 ;;
  esac
done
exit $rc

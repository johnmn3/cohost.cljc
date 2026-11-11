#!/bin/bash
# hatch-phase0.sh [workdir] — Phase-0 hosting probe + two-body gate (JVM + basilisp) at the 0932 pin.
# Probes: atom.broker lane-m/cohost-on-eve-0931 (multi-bodied/probe); its eve/ subtree is REPLACED by
# SeniorCareMarket/eve @ 73a64110 (the probes read $REPO/eve); cohost = release/cohost-on-eve-0932
# (carries the list* one-liner, so no scratch patching).
set -u
WORK="${1:-$HOME/cohost-0932-phase0}"; EVE_SHA=73a641108e52fc9b42b8a4d591a2776f6611a99e
mkdir -p "$WORK"; exec > >(tee "$WORK/phase0.log") 2>&1
echo "== phase0 start $(date -Is) box=$(hostname) load1_at_start=$(cut -d' ' -f1 /proc/loadavg)"
cd "$WORK"
[ -d ab/.git ] || git clone -q --depth 1 -b lane-m/cohost-on-eve-0931 https://github.com/johnmn3/atom.broker ab || { echo CLONE_FAILED ab; exit 3; }
[ -d ab/eve/.eve0932 ] || { rm -rf ab/eve; git clone -q https://github.com/SeniorCareMarket/eve ab/eve && (cd ab/eve && git checkout -q $EVE_SHA && touch .eve0932) || { echo CLONE_FAILED eve; exit 3; }; }
[ -d cohost/.git ] || git clone -q -b release/cohost-on-eve-0932 https://github.com/johnmn3/cohost.cljc cohost || { echo CLONE_FAILED cohost; exit 3; }
mkdir -p jvm-run && cp -r cohost jvm-run/cohost 2>/dev/null
echo "-- ab: $(cd ab && git rev-parse HEAD)  eve: $(cd ab/eve && git rev-parse HEAD)  cohost: $(cd cohost && git rev-parse HEAD)"
echo "-- tools: $(java -version 2>&1|head -1) | $(clojure --version 2>&1|head -1) | $(basilisp --version 2>&1|head -1)"
BAS="$(command -v basilisp)"; [ -n "$BAS" ] || echo "GAP: no basilisp on PATH"
echo "== 1/2 hosting probe $(date -Is)"
timeout 1200 bash ab/multi-bodied/probe/run-jvm-hosting.sh "$WORK/jvm-run"; echo "-- hosting rc=$?"
cat "$WORK/jvm-run/probe-out.txt" 2>/dev/null | tail -30
echo "== 2/2 two-body gate $(date -Is)"
[ -n "$BAS" ] && { timeout 1800 bash ab/multi-bodied/probe/run-two-body.sh "$WORK/two-body" "$WORK/cohost" "$BAS"; echo "-- two-body rc=$?"; }
echo "== journals: $(find "$WORK/jvm-run" "$WORK/two-body" -name '*.journal*' 2>/dev/null | head -6)"
echo "== phase0 end $(date -Is) load1=$(cut -d' ' -f1 /proc/loadavg)"

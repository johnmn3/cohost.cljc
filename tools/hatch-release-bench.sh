#!/bin/bash
# hatch-release-bench.sh <battery|jvm|jolt|jank|cljs|lpy|browser> [workdir]
# cohost-on-eve-0932 release measurement, one host per call, on the box it runs on.
# Trees: cohost @ release/cohost-on-eve-0932, Eve sibling (../eve) @ release/eve-0.9.3.2.
# Same command as the banked 2026-11-08 refresh: `bb bench:<host>` -> target/bench-*.edn.
# No installs: a missing runtime is a NAMED GAP line, not a failure. Banks to branch
# release/cohost-on-eve-0932-bench under doc/perf/release-0932/ (PUSH_FAILED -> files stay in workdir).
set -u
HOST="${1:?usage: hatch-release-bench.sh <battery|jvm|jolt|jank|cljs|lpy|browser> [workdir]}"
WORK="${2:-$HOME/cohost-0932-$HOST}"
EVE_SHA=a11a1820ed61a7a69f73d6630e2d6ee111e4e7ad
mkdir -p "$WORK"; exec > >(tee "$WORK/run-$HOST.log") 2>&1
echo "== release-bench $HOST start $(date -Is) box=$(hostname) cores=$(nproc) load1_at_start=$(cut -d' ' -f1 /proc/loadavg) (full: $(cat /proc/loadavg))"
cd "$WORK"
[ -d cohost/.git ] || git clone -q -b release/cohost-on-eve-0932 https://github.com/johnmn3/cohost.cljc cohost || { echo "CLONE_FAILED cohost"; exit 3; }
if [ ! -d eve/.git ]; then git clone -q https://github.com/SeniorCareMarket/eve eve && (cd eve && git checkout -q $EVE_SHA) || { echo "CLONE_FAILED eve"; exit 3; }; fi
echo "-- cohost sha: $(cd cohost && git rev-parse HEAD)  eve sha: $(cd eve && git rev-parse HEAD)  (want $EVE_SHA)"
echo "-- tools: $(java -version 2>&1|head -1) | $(clojure --version 2>&1|head -1) | bb $(bb --version 2>&1) | node $(node --version 2>&1) | $(basilisp --version 2>&1|head -1) | jolt $(jolt --version 2>&1|head -1) | jank $(jank --version 2>&1|head -1)"
cd cohost
need() { command -v "$1" >/dev/null || { echo "GAP $HOST: no '$1' on PATH on this box (no install attempted)"; exit 5; }; }
case "$HOST" in
  battery) need clojure; echo "-- bb boot:jvm start $(date -Is)"; bb boot:jvm; echo "-- battery end rc=$?"; exit 0;;
  jvm) need clojure; need bb;; jolt) need jolt; need bb;; jank) need jank; need bb;;
  cljs) need node; need bb; need clojure;; lpy) need basilisp; need bb;; browser) need bb;;
  *) echo "BAD HOST"; exit 2;;
esac
clojure -P -M 2>&1 | tail -1
[ "$HOST" = cljs ] && { clojure -P -M:cljs 2>&1 | tail -1; echo "-- cljs Eve checkout (eve_paths): pinned git dep"; }
echo "-- bench start $(date -Is) load1=$(cut -d' ' -f1 /proc/loadavg)"
t0=$(date +%s); bb "bench:$HOST"; rc=$?; t1=$(date +%s)
echo "-- bench end rc=$rc wall=$((t1-t0))s load1=$(cut -d' ' -f1 /proc/loadavg)"
edn=$(ls target/bench-*.edn 2>/dev/null | head -1)
[ -n "$edn" ] || { echo "NO_DELIVERABLE: no target/bench-*.edn (rc=$rc); last log lines above are the evidence"; exit 4; }
D=doc/perf/release-0932; mkdir -p $D; cp "$edn" "$D/bench-$HOST-hatch.edn"; cp "$WORK/run-$HOST.log" "$D/bench-$HOST-hatch.log"
git checkout -q -B release/cohost-on-eve-0932-bench && git add $D \
 && git -c user.name=hatch-runner -c user.email=hatch@fleet commit -q -m "release perf 0932: bench-$HOST on hatch (cohost $(git rev-parse --short HEAD), Eve $EVE_SHA)" \
 && { git push -q origin release/cohost-on-eve-0932-bench || { git pull -q --rebase origin release/cohost-on-eve-0932-bench && git push -q origin release/cohost-on-eve-0932-bench; }; } \
 && echo "BANKED $D/bench-$HOST-hatch.edn on release/cohost-on-eve-0932-bench" || echo "PUSH_FAILED: files in $WORK/cohost/$D"
exit $rc

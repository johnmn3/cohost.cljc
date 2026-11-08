#!/bin/bash
# grok-bench-jvm.sh — SELF-CONTAINED JVM bench for the cohost perf refresh
# (lane-m/cohost-perf-refresh). Stages everything it needs on the runner box:
# JDK 21 + Clojure CLI + Babashka 1.13.226 (only if missing), the cohost tree
# (branch lane-m/cohost-perf-refresh), and the Eve tree as its sibling
# (atom.broker @ lane-m/eve-release-0931, tip e727a05837 — the NEW Eve).
#
# *** DOUBLE-RUN GUARD ***
# This package is the TWIN of J gatekeeper job
#   20261108T182834-cohostperf-bench-jvm  (lane-m/cohost-perf-refresh)
# Exactly ONE of the twins may run. Run this script ONLY on a coordinator
# dispatch that confirms the J twin was withdrawn from ~/j-queue/inbox
# BEFORE this dispatch fired. If the J twin has started or finished, DO NOT
# RUN. Two bench-clj.edn for one cell is a defect, not redundancy.
#
# Deliverable (banked on branch lane-m/cohost-perf-refresh, repo
# johnmn3/cohost.cljc):
#   doc/perf/refresh-2026-11-08/bench-clj-grok.edn   (the bench records)
#   doc/perf/refresh-2026-11-08/bench-clj-grok.log   (this run's log)
# If the push is refused (credential scope), the script leaves both files in
# the clone, prints PUSH_FAILED + the clone path, and still exits 0 — the
# coordinator retrieves them from the printed path.
set -u
WORK="${1:-$HOME/cohost-perf-grok}"
TOOLS="$HOME/fleet-tools"
mkdir -p "$WORK" "$TOOLS"
exec > >(tee "$WORK/run.log") 2>&1
echo "== grok-bench-jvm start $(date -Is) host=$(hostname) cores=$(nproc) load=$(cat /proc/loadavg)"

# ---- toolchain (install what's missing; a failed install is a banked finding)
export PATH="$TOOLS/bin:$TOOLS/clojure/bin:$PATH"
if ! command -v java >/dev/null; then
  echo "-- installing Temurin JDK 21"
  curl -fsSL -o "$TOOLS/jdk.tgz" "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" \
    && mkdir -p "$TOOLS/jdk" && tar xzf "$TOOLS/jdk.tgz" -C "$TOOLS/jdk" --strip-components=1 \
    && mkdir -p "$TOOLS/bin" && ln -sf "$TOOLS/jdk/bin/java" "$TOOLS/bin/java" && ln -sf "$TOOLS/jdk/bin/javac" "$TOOLS/bin/javac" \
    || { echo "INSTALL_FAILED jdk"; exit 3; }
fi
if ! command -v clojure >/dev/null; then
  echo "-- installing Clojure CLI"
  curl -fsSL -o "$TOOLS/linux-install.sh" "https://download.clojure.org/install/linux-install.sh" \
    && bash "$TOOLS/linux-install.sh" --prefix "$TOOLS/clojure" \
    || { echo "INSTALL_FAILED clojure-cli"; exit 3; }
fi
BB=""
if command -v bb >/dev/null; then BB="$(command -v bb)"; else
  echo "-- installing Babashka 1.13.226"
  curl -fsSL -o "$TOOLS/bb.tgz" "https://github.com/babashka/babashka/releases/download/v1.13.226/babashka-1.13.226-linux-amd64.tar.gz" \
    && tar xzf "$TOOLS/bb.tgz" -C "$TOOLS" && ln -sf "$TOOLS/bb" "$TOOLS/bin/bb" && BB="$TOOLS/bin/bb" \
    || { echo "INSTALL_FAILED babashka"; exit 3; }
fi
echo "-- toolchain: $(java -version 2>&1 | head -1) | $(clojure --version 2>&1 | head -1) | $($BB --version)"

# ---- trees (sibling layout: cohost/../eve/src must resolve to the NEW Eve)
cd "$WORK"
[ -d cohost/.git ] || git clone -q --depth 1 -b lane-m/cohost-perf-refresh https://github.com/johnmn3/cohost.cljc cohost || { echo "CLONE_FAILED cohost"; exit 3; }
[ -d ab/.git ] || git clone -q --depth 1 -b lane-m/eve-release-0931 https://github.com/johnmn3/atom.broker ab || { echo "CLONE_FAILED atom.broker"; exit 3; }
ln -sfn ab/eve eve
echo "-- cohost HEAD: $(cd cohost && git log --oneline -1)"
echo "-- eve tree tip: $(cd ab && git log --oneline -1) (want e727a05837 = 0931)"
[ -f eve/src/eve/atom.cljc ] || ls eve/src | head

# ---- deps prep (Maven reachability is part of the measurement's honesty)
cd "$WORK/cohost"
clojure -P -M 2>&1 | tail -2 || { echo "DEPS_FAILED (maven unreachable?)"; exit 3; }

# ---- the bench (PERF.md lineage: bb bench:jvm -> target/bench-clj.edn)
echo "-- bench start $(date -Is) load=$(cat /proc/loadavg)"
t0=$(date +%s)
"$BB" bench:jvm
rc=$?
t1=$(date +%s)
echo "-- bench end rc=$rc wall=$((t1-t0))s load=$(cat /proc/loadavg)"
[ -f target/bench-clj.edn ] || { echo "NO_DELIVERABLE target/bench-clj.edn missing"; exit 4; }

# ---- bank the deliverable
mkdir -p doc/perf/refresh-2026-11-08
cp target/bench-clj.edn doc/perf/refresh-2026-11-08/bench-clj-grok.edn
cp "$WORK/run.log" doc/perf/refresh-2026-11-08/bench-clj-grok.log
git add doc/perf/refresh-2026-11-08/
git -c user.name="grok-runner" -c user.email="grok@fleet" commit -q -m "perf refresh 2026-11-08: bench-clj (JVM) measured on grok runner; Eve sibling @ 0931 e727a05837" \
  && git push -q origin lane-m/cohost-perf-refresh \
  && echo "BANKED doc/perf/refresh-2026-11-08/bench-clj-grok.edn on lane-m/cohost-perf-refresh" \
  || echo "PUSH_FAILED — deliverable left in $WORK/cohost/doc/perf/refresh-2026-11-08/ for coordinator retrieval"
exit $rc

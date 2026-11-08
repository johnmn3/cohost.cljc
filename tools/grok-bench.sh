#!/bin/bash
# grok-bench.sh <jolt|jank|cljs|lpy> — SELF-CONTAINED bench package for the
# cohost perf refresh (lane-m/cohost-perf-refresh), sibling of
# tools/grok-bench-jvm.sh (the JVM instance). Stages on the runner box:
# JDK 21 + Clojure CLI + Babashka 1.13.226 (if missing), the per-host runtime
# (below), the cohost tree (@ lane-m/cohost-perf-refresh) and the Eve tree as
# its sibling (atom.broker @ lane-m/eve-release-0931, tip e727a05837).
#
# Per-host runtime + deliverable:
#   jolt — needs a `jolt` binary on PATH (v0.8.23 is the fleet's). No public
#          installer is staged by this package: absent jolt = INSTALL_FAILED
#          banked finding, coordinator reroutes. -> bench-jolt.edn
#   jank — jank from github.com/jank-lang/jank (latest release linux asset).
#          -> bench-jank.edn
#   cljs — Node 24 (tarball if missing). NOTE: this seat's Eve is the PINNED
#          git dep (v0.9.3, deps.edn :cljs) — dev/eve-cljs/eve_paths.clj
#          resolves the pinned checkout, not the sibling tree. The run log
#          says so; tables must label this seat Eve 0.9.3-pinned.
#          -> bench-cljs.edn
#   lpy  — basilisp==0.5.1 via uv tool (uv installed if missing). The J twin
#          of this cell FAILED on J for exactly one reason: no basilisp on
#          that box ("Cannot run program basilisp"). -> bench-lpy.edn
#
# *** DOUBLE-RUN GUARD ***
# Each host cell has exactly ONE live run. These packages fire only on a
# coordinator/lane dispatch recorded in the mailbox, for cells whose J twin
# is finished (done/failed) or withdrawn — never while a J twin is queued
# or running. The dispatch text names the J twin's state.
#
# Deliverables (banked on lane-m/cohost-perf-refresh, johnmn3/cohost.cljc):
#   doc/perf/refresh-2026-11-08/bench-<host>-grok.edn + bench-<host>-grok.log
# PUSH_FAILED fallback as in grok-bench-jvm.sh.
set -u
HOST="${1:?usage: grok-bench.sh <jolt|jank|cljs|lpy>}"
case "$HOST" in jolt|jank|cljs|lpy) ;; *) echo "BAD HOST $HOST"; exit 2;; esac
WORK="${2:-$HOME/cohost-perf-grok-$HOST}"
TOOLS="$HOME/fleet-tools"
mkdir -p "$WORK" "$TOOLS/bin"
exec > >(tee "$WORK/run.log") 2>&1
echo "== grok-bench-$HOST start $(date -Is) host=$(hostname) cores=$(nproc) load=$(cat /proc/loadavg)"
export PATH="$TOOLS/bin:$TOOLS/clojure/bin:$HOME/.local/bin:$PATH"

# ---- base toolchain (same as grok-bench-jvm.sh)
if ! command -v java >/dev/null; then
  curl -fsSL -o "$TOOLS/jdk.tgz" "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" \
    && mkdir -p "$TOOLS/jdk" && tar xzf "$TOOLS/jdk.tgz" -C "$TOOLS/jdk" --strip-components=1 \
    && ln -sf "$TOOLS/jdk/bin/java" "$TOOLS/bin/java" && ln -sf "$TOOLS/jdk/bin/javac" "$TOOLS/bin/javac" \
    || { echo "INSTALL_FAILED jdk"; exit 3; }
fi
if ! command -v clojure >/dev/null; then
  curl -fsSL -o "$TOOLS/linux-install.sh" "https://download.clojure.org/install/linux-install.sh" \
    && bash "$TOOLS/linux-install.sh" --prefix "$TOOLS/clojure" \
    || { echo "INSTALL_FAILED clojure-cli"; exit 3; }
fi
if command -v bb >/dev/null; then BB="$(command -v bb)"; else
  curl -fsSL -o "$TOOLS/bb.tgz" "https://github.com/babashka/babashka/releases/download/v1.13.226/babashka-1.13.226-linux-amd64.tar.gz" \
    && tar xzf "$TOOLS/bb.tgz" -C "$TOOLS" && ln -sf "$TOOLS/bb" "$TOOLS/bin/bb" && BB="$TOOLS/bin/bb" \
    || { echo "INSTALL_FAILED babashka"; exit 3; }
fi

# ---- per-host runtime
case "$HOST" in
  jolt)
    command -v jolt >/dev/null || { echo "INSTALL_FAILED jolt (no staged installer; binary must be provided)"; exit 3; }
    echo "-- jolt: $(jolt --version 2>&1 | head -1)";;
  jank)
    if ! command -v jank >/dev/null; then
      url=$(curl -fsSL "https://api.github.com/repos/jank-lang/jank/releases/latest" | grep -oE '"browser_download_url": "[^"]*linux[^"]*"' | head -1 | cut -d'"' -f4)
      [ -n "$url" ] && curl -fsSL -o "$TOOLS/jank.tgz" "$url" && mkdir -p "$TOOLS/jank" && tar xzf "$TOOLS/jank.tgz" -C "$TOOLS/jank" \
        && jbin=$(find "$TOOLS/jank" -name jank -type f | head -1) && [ -n "$jbin" ] && ln -sf "$jbin" "$TOOLS/bin/jank" \
        || { echo "INSTALL_FAILED jank (asset: ${url:-none})"; exit 3; }
    fi
    echo "-- jank present: $(command -v jank)";;
  cljs)
    if ! command -v node >/dev/null; then
      curl -fsSL -o "$TOOLS/node.tgz" "https://nodejs.org/dist/v24.4.1/node-v24.4.1-linux-x64.tar.gz" \
        && mkdir -p "$TOOLS/node" && tar xzf "$TOOLS/node.tgz" -C "$TOOLS/node" --strip-components=1 \
        && ln -sf "$TOOLS/node/bin/node" "$TOOLS/bin/node" && ln -sf "$TOOLS/node/bin/npm" "$TOOLS/bin/npm" \
        || { echo "INSTALL_FAILED node"; exit 3; }
    fi
    echo "-- node: $(node --version); EVE LABEL: pinned v0.9.3 git dep (eve_paths.clj)";;
  lpy)
    if ! command -v basilisp >/dev/null; then
      command -v uv >/dev/null || curl -fsSL "https://astral.sh/uv/install.sh" | sh || { echo "INSTALL_FAILED uv"; exit 3; }
      export PATH="$HOME/.local/bin:$PATH"
      uv tool install "basilisp==0.5.1" || { echo "INSTALL_FAILED basilisp"; exit 3; }
    fi
    echo "-- basilisp: $(basilisp --version 2>&1 | head -1)";;
esac
echo "-- toolchain: $(java -version 2>&1 | head -1) | $(clojure --version 2>&1 | head -1) | $($BB --version)"

# ---- trees (sibling layout, as grok-bench-jvm.sh)
cd "$WORK"
[ -d cohost/.git ] || git clone -q --depth 1 -b lane-m/cohost-perf-refresh https://github.com/johnmn3/cohost.cljc cohost || { echo "CLONE_FAILED cohost"; exit 3; }
[ -d ab/.git ] || git clone -q --depth 1 -b lane-m/eve-release-0931 https://github.com/johnmn3/atom.broker ab || { echo "CLONE_FAILED atom.broker"; exit 3; }
ln -sfn ab/eve eve
echo "-- cohost HEAD: $(cd cohost && git log --oneline -1)"
echo "-- eve tree tip: $(cd ab && git log --oneline -1) (want e727a05837 = 0931)"
cd "$WORK/cohost"
clojure -P -M 2>&1 | tail -1 || { echo "DEPS_FAILED"; exit 3; }

# ---- the bench
echo "-- bench start $(date -Is) load=$(cat /proc/loadavg)"
t0=$(date +%s)
"$BB" "bench:$HOST"
rc=$?
t1=$(date +%s)
echo "-- bench end rc=$rc wall=$((t1-t0))s load=$(cat /proc/loadavg)"
edn=$(ls target/bench-*.edn 2>/dev/null | head -1)
[ -n "$edn" ] || { echo "NO_DELIVERABLE target/bench-*.edn missing"; exit 4; }

# ---- bank the deliverable
mkdir -p doc/perf/refresh-2026-11-08
cp "$edn" "doc/perf/refresh-2026-11-08/bench-$HOST-grok.edn"
cp "$WORK/run.log" "doc/perf/refresh-2026-11-08/bench-$HOST-grok.log"
git add doc/perf/refresh-2026-11-08/
git -c user.name="grok-runner" -c user.email="grok@fleet" commit -q -m "perf refresh 2026-11-08: bench-$HOST measured on grok runner; Eve sibling @ 0931 e727a05837 (cljs seat: Eve pin v0.9.3)" \
  && git push -q origin lane-m/cohost-perf-refresh \
  && echo "BANKED doc/perf/refresh-2026-11-08/bench-$HOST-grok.edn on lane-m/cohost-perf-refresh" \
  || echo "PUSH_FAILED — deliverable left in $WORK/cohost/doc/perf/refresh-2026-11-08/ for coordinator retrieval"
exit $rc

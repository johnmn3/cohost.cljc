#!/bin/bash
# Unit 3 probe: integer divide-by-zero in DIRECT cpp/ arithmetic.
#
# Measures the boundary the disposition in n13-lane-report.md states:
# the seam's own division throws ArithmeticException (exit 1, report),
# while the same division written as C++ in a cpp/raw block traps
# SIGFPE (exit 136, no output, no report) -- in plain jank and through
# a cohost session alike. Volatile operands keep the division a real
# runtime idiv (literal 5/0 would be a compile-time constant).
#
# Run from the repo root:  bash doc/debug-seam-probes/probe-div0-cpp.sh
# The cohost leg needs a jank snapshot for this tree (bb
# boot:jank-snapshot) or pays the source-boot fallback.
set -u
export PATH=$HOME/.local/bin:/usr/local/bin:$PATH
export JAVA_HOME=$HOME/opt/jdk-21.0.7+6-jre
export CLJ_CONFIG=$HOME/workspace/eve-env/cljconfig
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
WORK=$(mktemp -d)

echo "== plain jank: namespace-scope cpp/raw helper, called via cpp/ =="
cat > "$WORK/p.jank" <<'EOF'
(ns p)
(cpp/raw "namespace probe3 { static long dv(long b) { volatile long a = 5; return a / b; } }")
(println (cpp/probe3.dv 1))
(println (cpp/probe3.dv 0))
EOF
(cd "$WORK" && jank run p.jank > p.out 2> p.err; echo "PLAIN_JANK_EXIT=$?")
echo "-- stdout:"; cat "$WORK/p.out"; echo "-- stderr:"; cat "$WORK/p.err"

echo
echo "== cohost session: the same helper in a fixture's cpp/raw block =="
FIX="$ROOT/dev/cli-demo/src/seam/div0.cljc"
CREATED=0
if [ ! -f "$FIX" ]; then
  cat > "$FIX" <<'EOF'
(ns seam.div0)
(cpp/raw "namespace probe3 { static long div0() { volatile long a = 5; volatile long b = 0; return a / b; } }")
(defn -main [& args] (println (cpp/probe3.div0)))
EOF
  CREATED=1
fi
(cd "$ROOT/dev/cli-demo" && "$ROOT/bin/cohost" :jank -m seam.div0 > "$WORK/c.out" 2> "$WORK/c.err"; echo "COHOST_RAW_EXIT=$?")
echo "-- stdout:"; cat "$WORK/c.out"; echo "-- stderr:"; head -3 "$WORK/c.err"
[ "$CREATED" = 1 ] && rm -f "$FIX"

echo
echo "== cohost control: the seam's own division =="
(cd "$ROOT/dev/cli-demo" && "$ROOT/bin/cohost" :jank -e "(/ 5 0)" > "$WORK/g.out" 2> "$WORK/g.err"; echo "COHOST_GUARDED_EXIT=$?")
head -2 "$WORK/g.err"

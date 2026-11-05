#!/bin/bash
# Gate probe #9: an uncaught throw in an AHEAD-OF-TIME jank binary.
#
# Builds seam.throwmain (a -main that throws an ex-info, uncaught) into a
# native executable through the repo's own AOT route (bb build:jank ->
# cohost.build-jank/build!), runs the binary FROM ANOTHER DIRECTORY (the
# static executable reads nothing from the checkout), then runs the same
# app through the launcher for comparison. Prints both runs verbatim and
# a verdict block: exit codes, whether the seam's report appeared
# (headline + "Full report at:"), and whether the report EDN exists and
# names the throw.
#
# Run from the repo root:  bash doc/debug-seam-probes/probe9-aot-throw.sh
set -u
export PATH=$HOME/.local/bin:/usr/local/bin:$PATH
export JAVA_HOME=$HOME/opt/jdk-21.0.7+6-jre
export CLJ_CONFIG=$HOME/workspace/eve-env/cljconfig
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
BIN="$ROOT/dev/cli-demo/target/jank/throwmain"
WORK=$(mktemp -d)

echo "== build (bb build:jank dev/cli-demo seam.throwmain throwmain) =="
bb build:jank dev/cli-demo seam.throwmain throwmain || { echo "BUILD FAILED"; exit 1; }
ls -la "$BIN"

echo
echo "== AOT binary, run from $WORK =="
cd "$WORK"
"$BIN" > aot.out 2> aot.err
echo "AOT_EXIT=$?"
echo "-- stdout:"; cat aot.out
echo "-- stderr:"; cat aot.err
REPORT=$(grep -o "Full report at:.*" aot.err | sed 's/Full report at: *//')
if [ -n "$REPORT" ] && [ -f "$REPORT" ]; then
  echo "-- report file exists: $REPORT"
  grep -o ":message \"[^\"]*\"" "$REPORT" | head -2
  grep -c ":trace" "$REPORT" | sed 's/^/-- :trace occurrences: /'
else
  echo "-- NO report file named on stderr"
fi

echo
echo "== same app through the launcher (bin/cohost :jank -m seam.throwmain) =="
cd "$ROOT/dev/cli-demo"
"$ROOT/bin/cohost" :jank -m seam.throwmain > "$WORK/jit.out" 2> "$WORK/jit.err"
echo "JIT_EXIT=$?"
echo "-- stdout:"; cat "$WORK/jit.out"
echo "-- stderr:"; cat "$WORK/jit.err"

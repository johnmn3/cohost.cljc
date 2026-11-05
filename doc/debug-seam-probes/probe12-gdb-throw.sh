#!/bin/bash
# Gate probe #12: gdb break-on-throw against a jank binary.
#
# Runs the probe-#9 AOT binary (seam.throwmain, an uncaught ex-info throw)
# under gdb in batch mode twice: once with gdb's `catch throw` catchpoint,
# once with a breakpoint on the C++ ABI throw entry (__cxa_throw). Prints
# the gdb output verbatim: whether gdb stopped AT the throw (before the
# process died of it) and whether the backtrace frames are named.
#
# Run from the repo root:  bash doc/debug-seam-probes/probe12-gdb-throw.sh
set -u
export PATH=$HOME/.local/bin:/usr/local/bin:$PATH
export JAVA_HOME=$HOME/opt/jdk-21.0.7+6-jre
export CLJ_CONFIG=$HOME/workspace/eve-env/cljconfig
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
BIN="$ROOT/dev/cli-demo/target/jank/throwmain"

if [ ! -x "$BIN" ]; then
  echo "== building the probe binary first (bb build:jank) =="
  bb build:jank dev/cli-demo seam.throwmain throwmain || { echo "BUILD FAILED"; exit 1; }
fi

echo "== gdb: catch throw =="
gdb -batch -nx \
  -ex "set pagination off" \
  -ex "catch throw" \
  -ex run \
  -ex "bt 15" \
  -ex "info program" \
  --args "$BIN" 2>&1

echo
echo "== gdb: break __cxa_throw =="
gdb -batch -nx \
  -ex "set pagination off" \
  -ex "break __cxa_throw" \
  -ex run \
  -ex "bt 15" \
  -ex "info program" \
  --args "$BIN" 2>&1

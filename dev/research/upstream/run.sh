#!/usr/bin/env bash
# bash dev/research/upstream/run.sh <NN-name> [jank options...]   (default -O3)
# Prepends timing.jank, writes target/research/upstream/<NN-name>.jank, runs jank run on it.
set -uo pipefail
# JANK_BIN=<path> picks the jank binary (default: jank on PATH)
JANK="${JANK_BIN:-jank}"
name="$1"; shift; opts="${*:--O3}"
mkdir -p target/research/upstream
cat dev/research/upstream/timing.jank "dev/research/upstream/$name.jank" > "target/research/upstream/$name.jank"
echo "== jank $opts run target/research/upstream/$name.jank"
"$JANK" $opts run "target/research/upstream/$name.jank"; echo "EXIT $?"

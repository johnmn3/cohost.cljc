#!/bin/bash
# datascript.cljc harness — Babashka runner (plane A: directly on Eve).
# Usage: run-bb.sh <contract|perf|one> <args...>
# Entry is ds-harness.bb-main, which loads Eve's bb completion before
# the port's namespaces (the shipped bb seat pattern). The classpath
# is composed the way Eve's own bb tasks do: Eve's src + hosts.edn :bb
# adapter roots (absolute), then this repo's lib pieces, the shipped
# drivers and the harness. BB env names the bb binary (fleet
# 1.13.226 — an older bb on the PATH changes the result class).
set -u
EVE_LOCAL="${EVE_LOCAL:-$HOME/workspace/ds-cohost/eve}"
BB="${BB:-$HOME/workspace/tools/bin/bb}"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
ROOTS="src adapters/slab-io/protocol/src adapters/http/java/src adapters/http-server/httpkit/src adapters/crypto/java/src adapters/core/clojure/src adapters/completion/bb/src adapters/completion/clojure/src adapters/coll/protocols/src adapters/slab/heap/src adapters/region/jvm-nio-lock/src adapters/encoding/java/src adapters/int32/jvm/src adapters/codec/byte-array/src adapters/process/java/src adapters/thread/jvm/src adapters/types/bb/src adapters/journal-file/java/src"
CP=""
for r in $ROOTS; do CP="$CP:$EVE_LOCAL/$r"; done
CP="$CP:$REPO/lib-ds:$REPO/lib:$REPO/lib-ds-deps:$REPO/test/drivers:$REPO/harness"
exec "$BB" -cp "${CP#:}" -m ds-harness.bb-main "$@"

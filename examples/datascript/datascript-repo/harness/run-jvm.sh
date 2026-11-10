#!/bin/bash
# datascript.cljc harness — JVM runner (plane A: directly on Eve).
# Usage: run-jvm.sh <namespace> <args...>
# Env: EVE_LOCAL (default: the seat's verified Eve 0.9.3.1 checkout)
# overrides the lib's Eve git pin with a local tree while git transport
# is unavailable; the repo's deps.edn keeps the git pin.
set -u
NS="$1"; shift
EVE_LOCAL="${EVE_LOCAL:-$HOME/workspace/ds-cohost/eve}"
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/../test" || exit 9
export CLJ_CONFIG="${CLJ_CONFIG:-$HOME/workspace/eve-env/cljconfig}"
exec clojure \
  -J--add-opens=java.base/java.lang=ALL-UNNAMED \
  -J--enable-native-access=ALL-UNNAMED \
  -Sdeps "{:deps {eve/eve {:local/root \"$EVE_LOCAL\"}}}" \
  -M -m "$NS" "$@"

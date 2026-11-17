#!/usr/bin/env bash
# CLJS parity stage-1 proof runner (hatch recipe; every line below is
# the shape that actually ran green on 2026-11-17).
#
#   dev/cljs_parity/run.sh proof.clj        # 22-check boot proof
#   dev/cljs_parity/run.sh hiccup_smoke.clj # hiccups stretch proof
#
# The runner deps live in /tmp (ephemeral by design); the scripts are
# the durable part and live beside this file. Maven resolves offline
# from /home/hatch/m2repo via the release-smokes CLJ_CONFIG.
set -euo pipefail
SCRIPT="${1:?usage: run.sh <proof.clj|hiccup_smoke.clj>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WT="$(cd "$HERE/../.." && pwd)"
RUN=/tmp/cljs-run
mkdir -p "$RUN"
cat > "$RUN/deps.edn" <<EOF
{:paths ["$WT/src" "$HOME/workspace/cohost-media/cljs-parity/census/hiccups-src"]
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}
        org.clojure/clojurescript {:mvn/version "1.12.134"}
        org.graalvm.polyglot/polyglot {:mvn/version "25.0.1"}
        org.graalvm.js/js-language {:mvn/version "25.0.1"}}}
EOF
cp "$HERE/$SCRIPT" "$RUN/$SCRIPT"
cd "$RUN"
CLJ_CONFIG=/home/hatch/workspace/release-smokes/cljconfig \
JAVA_HOME=/home/hatch/opt/jdk-25.0.5+8 \
PATH=/home/hatch/opt/jdk-25.0.5+8/bin:/home/hatch/.local/bin:$PATH \
timeout 400 clojure -M "$SCRIPT"

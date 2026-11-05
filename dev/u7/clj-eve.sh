#!/usr/bin/env bash
# Unit 7: run a Clojure (JVM seat) script with cohost's src plus Eve's :jvm roots
# from ONE Eve tree (EVE_ROOT, default the vendored eve-jank line the jank seat
# also runs: atom.broker/eve @ claude/eve-jank).
# Usage: dev/u7/clj-eve.sh file.clj [args]
set -euo pipefail
here="$(cd "$(dirname "$0")/../.." && pwd)"
EVE_ROOT="${EVE_ROOT:-/home/user/atom.broker/eve}"
paths=$(bb "$here/dev/u7/eve_paths.clj" "$here" "$EVE_ROOT" jvm)
cd "$here"
clojure -Sdeps "{:paths [$paths]}" -M "$@"

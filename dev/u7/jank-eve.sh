#!/usr/bin/env bash
# Unit 7: run a jank main with Eve's :jank roots from ONE Eve tree (EVE_ROOT,
# default atom.broker/eve @ claude/eve-jank) plus dev/u7/jank.
# Usage: dev/u7/jank-eve.sh <main-ns> [args...]
set -euo pipefail
here="$(cd "$(dirname "$0")/../.." && pwd)"
EVE_ROOT="${EVE_ROOT:-/home/user/atom.broker/eve}"
mp=$(bb "$here/dev/u7/eve_paths.clj" "$here" "$EVE_ROOT" jank)
export LC_ALL=en_US.UTF-8 GC_INITIAL_HEAP_SIZE=512M GC_FREE_SPACE_DIVISOR=3
main=$1; shift
cd "$EVE_ROOT"
jank --module-path "$mp:$here/dev/u7/jank" run-main "$main" -- "$@"

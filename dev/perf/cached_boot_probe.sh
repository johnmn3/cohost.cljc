#!/usr/bin/env bash
# D-6 step 1: what each host's own cache gives a generated module. One
# namespace of 1000 small fns (written per dialect by the caller, see
# doc/PERF.md "Cached boots"), three runs each, wall s:
#   eval   -- the forms read and evaluated one by one (load-file; the host's
#             compile of each form, as cohost's boot evaluates its forms)
#   module -- the namespace required as a module, its cache emptied first
#   cached -- the same again, its cache warm (the host's own bytecode,
#             compiled PHP, .lgb: what a cached boot would load)
#   bash dev/perf/cached_boot_probe.sh <probe-dir>
P=${1:?probe dir}; cd "$P"
w() { local t0=$(date +%s.%N); "$@" >/dev/null 2>&1; local rc=$?; local t1=$(date +%s.%N); printf "%.3f%s " "$(echo "$t1 - $t0" | bc)" "$([ $rc = 0 ] || echo '!')"; }
three() { local n=$1; shift; echo "PROBE $n $(w "$@") $(w "$@") $(w "$@")"; }
# ClojureWasm: no bytecode cache (cljw build re-evaluates at start: probed)
three cljw-eval cljw -e '(load-file "clj/probe/big.clj")'
three cljw-module cljw -cp clj -e "(require 'probe.big)"
# basilisp: require caches .lpyc bytecode under __pycache__
three lpy-eval basilisp run -c '(load-file "clj/probe/big.clj")'
three lpy-module bash -c "find clj -name __pycache__ -exec rm -rf {} +; basilisp run -p clj -c \"(require 'probe.big)\""
three lpy-cached basilisp run -p clj -c "(require 'probe.big)"
# let-go: -c compiles to .lgb bytecode
three lg-eval lg lg/probe/big.lg
three lg-compile lg -c /tmp/claude-big.lgb lg/probe/big.lg
three lg-cached lg /tmp/claude-big.lgb
# Phel: phel build compiles the namespace to PHP; running the build skips the compile
three phel-eval bash -c "cd phel && phel run src/big.phel"
three phel-build bash -c "cd phel && rm -rf out && phel build --no-cache"
three phel-cached bash -c "cd phel && php out/big.php"

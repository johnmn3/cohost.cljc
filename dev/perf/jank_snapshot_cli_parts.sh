#!/usr/bin/env bash
# D-8: where `cohost :jank -M -m demo.main jank-user` spends its wall time.
# Each part is its own command, three runs (s); the jank-side parts run the
# launch's own jank command with a config that stops at that point. Run in
# the repo with a fresh snapshot (bb boot:jank-snapshot).
#   bash dev/perf/jank_snapshot_cli_parts.sh [project-dir] [main-ns] [arg]
set -uo pipefail
R=$(cd "$(dirname "$0")/../.." && pwd); P=$(cd "${1:-$R/dev/cli-demo}" && pwd); M=${2:-demo.main}; A=${3:-jank-user}
cd "$P"
w() { local t0=$(date +%s.%N); "$@" >/dev/null 2>&1; local t1=$(date +%s.%N); printf "%.3f" "$(echo "$t1 - $t0" | bc)"; }
three() { local name=$1; shift; echo "PART $name $(w "$@") $(w "$@") $(w "$@")"; }
L=$(cd "$R" && bb -e "(load-file \"dev/perf/jank_snapshot_cli.clj\") (prn (jank-cli-launch \"$R\"))")
MP=$(bb -e '(println (:module-path (read-string (slurp *in*))))' <<<"$L")
SNAP=$(bb -e '(println (:snapshot? (read-string (slurp *in*))))' <<<"$L")
[ "$SNAP" = true ] || { echo "the snapshot is not fresh: $L"; exit 1; }
ROOTS=$("$R/bin/cohost" :jank -Spath 2>/dev/null)
cfg() { # main-edn inits-edn -> a config file
  local f=$(mktemp --suffix=.edn)
  bb -e "(spit \"$f\" (pr-str {:stock-root \"$R/resources/cohost/clojure-1.12.0\" :roots (vec (clojure.string/split \"$ROOTS\" (re-pattern \":\"))) :cwd \"$P\" :inits $2 :main $1 :snapshot? true :verbose false}))"
  echo "$f"
}
printf '(println "x")\n' > /tmp/claude-empty.jank
printf "(require 'cohost.run)\n" > /tmp/claude-cohost-run.jank
C0=$(cfg nil '[]'); C1=$(cfg nil "[[:e \"(require '$M)\"]]"); C2=$(cfg "{:type :main :ns \"$M\" :args [\"$A\"]}" '[]')
J=(env GC_INITIAL_HEAP_SIZE=512M jank -O3 --module-path "$MP")
three bb-start bb -e nil
three cohost-Spath "$R/bin/cohost" :jank -Spath
three clojure-Spath-cached clojure -Sverbose -Spath
three clojure-Spath-uncached bash -c "rm -rf .cpcache; clojure -Sverbose -Spath"
three launch-helper bash -c "cd '$R' && bb -e '(load-file \"dev/perf/jank_snapshot_cli.clj\") (jank-cli-launch \"$R\")'"
three jank-start "${J[@]}" run /tmp/claude-empty.jank
three jank-cohost-objects "${J[@]}" run /tmp/claude-cohost-run.jank
three jank-cli-boot-exit "${J[@]}" run-main cohost.cli -- "$C0"
three jank-cli-load-app "${J[@]}" run-main cohost.cli -- "$C1"
three jank-cli-main "${J[@]}" run-main cohost.cli -- "$C2"
# the project's snapshot (D-8), when it is built and fresh: the same parts with
# its module path and :project-snapshot in the config
PJ=$(cd "$R" && bb -e "(load-file \"dev/perf/jank_snapshot_cli.clj\") (prn (jank-project-launch \"$R\" \"$P\" (clojure.string/split \"$ROOTS\" (re-pattern \":\")) \"$M\" \"$MP\" {}))" 2>/dev/null)
PMP=$(bb -e '(println (or (:module-path (read-string (slurp *in*))) ""))' <<<"$PJ")
if [ -n "$PMP" ]; then
  pcfg() { local f=$(cfg "$1" "$2"); bb -e "(spit \"$f\" (pr-str (assoc (read-string (slurp \"$f\")) :project-snapshot \"cohost.project\")))"; echo "$f"; }
  P0=$(pcfg nil '[]'); P1=$(pcfg nil "[[:e \"(require '$M)\"]]"); P2=$(pcfg "{:type :main :ns \"$M\" :args [\"$A\"]}" '[]')
  PJR=(env GC_INITIAL_HEAP_SIZE=512M jank -O3 --module-path "$PMP")
  three proj-cli-boot-exit "${PJR[@]}" run-main cohost.cli -- "$P0"
  three proj-cli-load-app "${PJR[@]}" run-main cohost.cli -- "$P1"
  three proj-cli-main "${PJR[@]}" run-main cohost.cli -- "$P2"
  rm -f "$P0" "$P1" "$P2"
fi
three whole "$R/bin/cohost" :jank -M -m "$M" "$A"
three jvm clojure -M -m "$M" "$A"
rm -f "$C0" "$C1" "$C2"

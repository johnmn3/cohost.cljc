#!/usr/bin/env bash
# jank-FAST Step 6 stage 2. boot-ms and libs-ms, three runs each, alternating:
# bb bench:jank --quick (the AOT boot of core.clj, no snapshot) and
# bb boot:jank-snapshot --bench --quick (a fresh snapshot). After each run the
# two numbers are read from target/bench-jank.edn. Prints one RUN line per run
# and one MEDIAN line per side. Run it alone on the box.
#   bash dev/perf/jank_snapshot_time.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
bb -e '(println (load-file "dev/perf/jank_snapshot_ensure.clj"))' >/dev/null
read_ms() {
  bb -e '(let [r (clojure.edn/read-string (slurp "target/bench-jank.edn"))] (println (:boot-ms r) (:libs-ms r) (+ (:boot-ms r) (:libs-ms r))))'
}
aot=(); snap=()
for i in 1 2 3; do
  bb bench:jank --quick >/dev/null 2>&1
  a=$(read_ms); echo "RUN aot $i boot-ms libs-ms sum: $a"; aot+=("$a")
  bb boot:jank-snapshot --bench --quick >/dev/null 2>&1
  s=$(read_ms); echo "RUN snapshot $i boot-ms libs-ms sum: $s"; snap+=("$s")
done
median() { # column n of three lines
  printf '%s\n' "$@" | awk -v c="$col" '{print $c}' | sort -g | sed -n 2p
}
for side in aot snap; do
  if [ $side = aot ]; then rows=("${aot[@]}"); else rows=("${snap[@]}"); fi
  col=1; b=$(median "${rows[@]}"); col=2; l=$(median "${rows[@]}"); col=3; t=$(median "${rows[@]}")
  echo "MEDIAN $side boot-ms $b libs-ms $l sum $t"
done

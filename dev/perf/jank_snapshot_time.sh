#!/usr/bin/env bash
# jank-FAST Step 6 stage 2. boot-ms and libs-ms, three runs each, alternating:
# the AOT boot of core.clj without the snapshot (what bb bench:jank ran
# before the snapshot became its default: cohost's AOT modules, run-main
# cohost.run --bench) and bb bench:jank, which boots from the fresh snapshot.
# After each run the two numbers are read from target/bench-jank.edn. Prints
# one RUN line per run and one MEDIAN line per side. Run it alone on the box.
#   bash dev/perf/jank_snapshot_time.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
bb -e '(println (load-file "dev/perf/jank_snapshot_ensure.clj"))' >/dev/null
aotdir=$(bb -e '(println (load-file "dev/perf/jank_aot_ensure.clj"))' | tail -1)
read_ms() {
  bb -e '(let [r (clojure.edn/read-string (slurp "target/bench-jank.edn"))] (println (:boot-ms r) (:libs-ms r) (+ (:boot-ms r) (:libs-ms r))))'
}
aot=(); snap=()
for i in 1 2 3; do
  jank --module-path "$aotdir:src" run-main cohost.run -- --bench --quick >/dev/null 2>&1
  a=$(read_ms); echo "RUN aot $i boot-ms libs-ms sum: $a"; aot+=("$a")
  out=$(bb bench:jank --quick 2>&1)
  grep -q '^SNAPSHOT-FRESH' <<<"$out" || { echo "bench:jank did not boot from a fresh snapshot"; exit 1; }
  s=$(read_ms); echo "RUN snapshot $i boot-ms libs-ms sum: $s"; snap+=("$s")
done
median() { # column $col of three lines
  printf '%s\n' "$@" | awk -v c="$col" '{print $c}' | sort -g | sed -n 2p
}
for side in aot snap; do
  if [ $side = aot ]; then rows=("${aot[@]}"); else rows=("${snap[@]}"); fi
  col=1; b=$(median "${rows[@]}"); col=2; l=$(median "${rows[@]}"); col=3; t=$(median "${rows[@]}")
  echo "MEDIAN $side boot-ms $b libs-ms $l sum $t"
done

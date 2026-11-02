#!/usr/bin/env bash
# jank-FAST research item 1 (S3-1, D-3): bb bench:jank --quick with and
# without -O3 on jank's JIT, from the fresh snapshot and from the AOT boot of
# core.clj (no snapshot), three runs each, the four sides interleaved. Each
# run's target/bench-jank.edn is kept under target/jank-o3/<side>-<i>.edn;
# dev/perf/jank_snapshot_o3_report.clj prints the medians.
#   bash dev/perf/jank_snapshot_o3.sh
# FULL=1: the full bench (bench:jank without --quick: warm-up, a time budget,
# at least 3 timed calls per side) from the snapshot only, kept as
# target/jank-o3/full-snap-<none|O3>-<i>.edn.
set -euo pipefail
cd "$(dirname "$0")/../.."
bb -e '(println (load-file "dev/perf/jank_snapshot_ensure.clj"))' >/dev/null
aotdir=$(bb -e '(println (load-file "dev/perf/jank_aot_ensure.clj"))' | tail -1)
mkdir -p target/jank-o3
if [ "${FULL:-}" = 1 ]; then
  for i in 1 2 3; do
    for o in none -O3; do
      tag=${o#-}
      out=$(COHOST_JANK_O=$o bb bench:jank 2>&1)
      grep -q '^SNAPSHOT-FRESH' <<<"$out" || { echo "bench:jank did not boot from a fresh snapshot"; exit 1; }
      cp target/bench-jank.edn "target/jank-o3/full-snap-$tag-$i.edn"
      echo "RUN full-snap-$tag $i"
    done
  done
  bb dev/perf/jank_snapshot_o3_report.clj full
  exit 0
fi
for i in 1 2 3; do
  for o in none -O3; do
    tag=${o#-}
    # from the snapshot: bench:jank (its default when fresh)
    out=$(COHOST_JANK_O=$o bb bench:jank --quick 2>&1)
    grep -q '^SNAPSHOT-FRESH' <<<"$out" || { echo "bench:jank did not boot from a fresh snapshot"; exit 1; }
    cp target/bench-jank.edn "target/jank-o3/snap-$tag-$i.edn"
    echo "RUN snap-$tag $i"
    # without the snapshot: the AOT boot, as bench:jank's fallback runs it
    oflag=(); [ "$o" = none ] || oflag=("$o")
    jank "${oflag[@]}" --module-path "$aotdir:src" run-main cohost.run -- --bench --quick >/dev/null 2>&1
    cp target/bench-jank.edn "target/jank-o3/aot-$tag-$i.edn"
    echo "RUN aot-$tag $i"
  done
done
bb dev/perf/jank_snapshot_o3_report.clj

#!/bin/bash
# Phel's snapshot (doc/AOT-PLAN.md item 3), in the cohost checkout:
#   dev/perf/phel_snapshot.sh [record|emit|run|all]   (default: all)
# record: phel run dev/phel_snapshot_record.phel -> target/phel-snapshot/events.edn
# emit:   bb dev/perf/jank_snapshot_emit.clj --host phel -> phel-snap/ (a Phel scan skips target/)
# run:    the boot from the modules and both batteries (dev/phel_snapshot_run.phel), from
#         phel-snap-run/, whose phel-config.php lists src and phel-snap.
# PHP's stack: 64 MB, as bench:phel runs. Under Linux's default 8 MB, deep lazy-seq walks
# (the battery's "chunked laziness" row) segfault PHP from source and from the modules alike.
set -euo pipefail
cd "$(dirname "$0")/../.."
step=${1:-all}
if [ "$step" = record ] || [ "$step" = all ]; then
  mkdir -p target/phel-snapshot
  bash -c "ulimit -s 65536 2>/dev/null || ulimit -s hard; exec phel run dev/phel_snapshot_record.phel --out target/phel-snapshot/events.edn"
fi
if [ "$step" = emit ] || [ "$step" = all ]; then
  rm -rf phel-snap
  bb dev/perf/jank_snapshot_emit.clj --host phel --events target/phel-snapshot/events.edn --out phel-snap
fi
if [ "$step" = run ] || [ "$step" = all ]; then
  mkdir -p phel-snap-run
  cat > phel-snap-run/phel-config.php <<'PHP'
<?php
// written by dev/perf/phel_snapshot.sh: cohost's sources and the emitted modules
ini_set('precision', '-1');
use Phel\Config\PhelConfig;
use Phel\Config\ProjectLayout;
return PhelConfig::forProject(ProjectLayout::Flat)
    ->withSrcDirs(['../src', '../phel-snap'])
    ->withTestDirs([])
    ->withBuildDestDir('out');
PHP
  (cd phel-snap-run && bash -c "ulimit -s 65536 2>/dev/null || ulimit -s hard; exec phel run ../dev/phel_snapshot_run.phel")
fi

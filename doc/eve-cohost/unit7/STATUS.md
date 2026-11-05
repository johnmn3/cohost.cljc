# Unit 7 — tiered atoms (mem -> disk -> net) — STATUS

- Lane: agent1/eve-unit7-tiered-atoms, branched from muse/eve-cohost-impl @ 0fa021f (verified tip, 12:59 PM ET 2026-11-05)
- 12:59 PM ET — lane opened; reading MEMORY-PLAN.md unit-7 spec.
- 1:00 PM ET — **unit 5 is not in the mainline** (0fa021f carries units 2+3 only). Unit 5 is complete on
  `muse/eve-cohost-impl-u5` @ b142ffe (REPORT: probe 66/66, check 31/31, JVM 221/221 + 62/62, jank AOT
  219/221). Merged into THIS LANE only (bb68557; conflicts were doc-strings, the shim require, and
  boot's `:host-lacks` line = unit 2's `core-lacks` ∪ unit 5's atom-surface names). No muse/* branch touched.
- 1:05 PM ET — installs: jank `0.1-noble` (dev/install/jank.sh, apt), Clojure CLI 1.12.6.1673 (official
  linux-install.sh, sha256-pinned tarball), babashka v1.13.229 (official installer). All exit 0.
- 1:08 PM ET — Eve sources: SeniorCareMarket/eve main @ d241de3 (eve 0.9.1, adapter architecture; **no
  :jank host**). Eve-on-jank lives in the vendored line `johnmn3/atom.broker` @ `claude/eve-jank`
  7ae05d49 (attached read-only): `:jank` host row, region/jank (MAP_SHARED + __atomic + futex),
  native slab-io/typed-view flipped in, and `bench/bench/atom_bench.jank` driving
  `eve.atom/persistent-atom-domain` (file-backed mmap atoms) on jank. Flagship gate NOT declared
  (perf rows moved to VS Agent 2 at 13:02 ET; realm-signedness defect open on jank — affects TAG-REF
  references, not plain data). Decision: the two-process gate uses ONE Eve tree for both seats
  (atom.broker/eve @ 7ae05d49, its `:jvm` and `:jank` roots), so the file format is one format.

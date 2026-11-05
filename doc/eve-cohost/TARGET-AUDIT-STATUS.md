# TARGET-AUDIT — STATUS

- Opened 2026-11-05 (~10:20 ET order from John), base
  `muse/eve-cohost-impl` @ `d5ba16c`.
- Deliverable: `doc/eve-cohost/TARGET-AUDIT.md` on this branch.
  Audit/research only; Eve's tree (`~/src/eve`) read-only.
- Step 0 enumeration: DONE (sources: Eve platform-support.md,
  CLJC reader-conditional census, IMemRegion backends,
  vendored-Eve hosts.edn + hosts README on
  atom.broker `claude/eve-jank`, cohost seat list).
- Per-target code audit: DONE (19 targets; read paths +
  deref established from code on both Eve lines).
- Measurement: one bench run (bb, banked at
  `dev/target_audit_bb_bench.clj`). It and Eve's own
  `bb atom-swap` example fail identically at load on
  standalone Eve main `c7dc390`
  (`ser/register-header-tag-reader!` unresolved,
  alloc.cljc:607) — the standalone :bb target is broken
  at load; recorded as its verdict instead of numbers.
  All other costs cite standing runs (spike P3, redo
  §5.2, vendored perf suite 2026-10-21).
- COMPLETE 2026-11-05: TARGET-AUDIT.md banked on this
  branch. Headline: collection reads in place everywhere
  they exist (JVM disk-priced — the one clean VIOLATION);
  plain deref materializes on EVERY target, both lines,
  contradicting platform-support.md's lazy-deref claim;
  5 targets NO EVE PORT; count reconciled (John's 11–12
  = vendored battery/perf set; audit rows = 19).

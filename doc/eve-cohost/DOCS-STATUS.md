# Docs lane — STATUS: user-facing docs for cohost-with-Eve

Branch `muse/eve-cohost-docs`, off `muse/eve-cohost-impl` @ c2323ec
(unit 8's merge b744ad2 is in that base; its landing gates were still
running at the base, so unit 8 is written as GATE-GREEN, per the
landing STATUS at doc/eve-cohost/LANDING-u8-STATUS.md). Started
2026-11-05 ET. Destination: `doc/guide/` chapters 12–15, indexed from
README.md's guide list (the repo's doc entry point), in the guide's
numbered-chapter structure and voice.

## Sources used, per guide

- **12-threading-with-eve.md** — MEMORY-PLAN.md §4 (unit scopes) and
  §7–§10 rulings; UNIT1-REPORT.md (the fill's name list, executors,
  conveyance, named distances, probe 29/29 + booted check 21/21);
  UNIT2-REPORT.md (jank leaves, snapshot-replay fix, probe row);
  CLJS-DEP-REPORT.md + its addenda (cljs leaf, macro-fill fix closing
  at probe ok=22, mesh stand-up conformance, REPL/CLI boot parity);
  unit8/REPORT.md (worker realms, form-carrying futures, agents stay
  in the core realm); `src/cohost/policy.cljc` `thread-names`
  (verified by grep — the exact 29 filled names); the example program
  is `dev/eve_probe_program.cljc` (has actually run on all seats).
- **13-memory-with-eve-atoms.md** — UNIT5-REPORT.md (atom layer,
  registry atom, seam-equality CAS, named remainders, the printed
  contention and uncontended numbers); MEMORY-PLAN.md §8 (heap
  ruling) and §7 Q5 (runtime atom domain); unit8/REPORT.md (cljs
  shared heap, per-realm watches/meta, the integer-key corruption
  finding); `eve_cohost_check_u5.clj` case list (verified by grep).
- **14-tiered-atoms.md** — unit7/REPORT.md end to end (metadata keys
  and examples verbatim, rules table with error keys, GATE-U7 9/9,
  the net two-process run, named remainders); MEMORY-PLAN.md §4
  unit 7 + §7 Q5; LANDING-u7-u9-DECLARATION.md (landed state, merged
  tree gates).
- **15-eve-coverage-and-status.md** — every cell cites the report or
  gate line it rests on, in the table's Sources column.

## Ambiguities found in the record (named, not guessed)

1. **Net atom's disk LRU.** The plan's model (§4 unit 7, and the
   standing atom-kind ruling) says a network atom contains its disk
   LRU, tier below tier. Unit 7's REPORT, which exercised the net
   kind, states the Eve implementation consumed as-is keeps the
   client's block cache in memory; the disk tier a network atom
   bottoms out in is the *gateway member's* disk domain. Chapter 14
   documents the exercised state and says which part is the model
   rather than the measurement.
2. **Conveyance keys.** `:eve/conveys` (atom) / `:eve/convey` (var)
   are the unit-7 lane's design choice under the Q5 metadata ruling;
   the report marks the expression "the operator's to confirm."
   The mechanism is landed; the key names are documented as they
   stand in the code, with the confirmation state not claimed.
3. **JVM seat's user surface.** Every JVM-seat Eve gate ran through
   the boot API (`:eve-atoms`, `:host-lacks`) or a layer probe; the
   `cohost` command's `:jvm` target is the real Clojure CLI. The
   guides say exactly that rather than implying a CLI switch the
   record doesn't show.
4. **test-stacktrace on cljs.** Unit 8's gate: the probe's cljs row
   matches the JVM's values on 22 of 23 cases; the stack-trace case
   prints a JVM frame count (26) against the cljs seat's empty trace
   (0), and the report leaves the case to the operator's ruling.
   The guides state the 22/23 fact and the pending ruling.
5. **Heap-grade numbers not cited.** Heap-grade step 2's guarded-
   deref numbers live in the Eve repo's lane record, not in a unit
   report on this branch; per this lane's citation rule the guides
   cite only UNIT5-REPORT's printed atom numbers for cost.

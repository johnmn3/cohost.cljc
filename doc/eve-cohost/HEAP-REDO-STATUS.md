# Heap spike REDO — STATUS

Lane: re-do the contaminated parts of HEAP-SPIKE.md under
John's corrected premises (2026-11-05 ~10:05 ET). Branch
`muse/eve-cohost-impl`, worktree
`~/workspace/wt/eve-cohost-heapredo`, base = remote tip
`7733004` at lane start (the ruling-bank lane's tip,
including its §4 postscript + per-problem audit table).

## Corrected premises (non-negotiable framing)

1. NO COUNTERPARTS ANYWHERE — every seat's runtime atom
   domain is real Eve, or the seat waits on named Eve-side
   work. The category is dead.
2. cljs/node seats HAVE the shared heap: workers +
   SharedArrayBuffer + Eve's shared atoms between them
   (`eve.shared-atom` / `eve.atom` SAB domains;
   cljs.thread lineage). The load probe's "no shared heap"
   line was false.
3. `eve.atom` the durable disk/net kind is NOT the runtime
   heap's mechanism (original P3 measurement stands).

## Scope

- REDO: P4 (full; probed from Eve's platform code);
  P3's cljs/SAB half (measured, not docs); P2's one
  counterpart clause; §2's supported shape (redrawn).
- STANDS, not redone: P1, P5, P6, P3's JVM measurements.

## Progress

- [x] Base verified; original spike read in full.
- [x] Eve platform structure probed (~/src/eve @ main
      c7dc390): reader features are exactly :cljs / :clj /
      :bb; IMemRegion backends enumerated per feature.
- [ ] P3 cljs bench run (eve.atom SAB atom + eve.map on
      Node) — numbers as printed or NOT MEASURED + blocker.
- [ ] §5 redo sections written; P4 replaced with pointer;
      P2 clause rewritten; P3/§2 addenda marked.
- [ ] Final declaration (chat + doc).

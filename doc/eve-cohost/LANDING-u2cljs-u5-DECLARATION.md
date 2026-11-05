# DECLARATION — unit 2 cljs half + unit 5 landed on muse/eve-cohost-impl

Landing completed 2026-11-05 (~2:15 PM ET), Lane M collation.
Collation branch muse/collate-u2cljs-u5, fast-forwarded into
muse/eve-cohost-impl the same way the units 2+3 landing was.
Landing STATUS: doc/eve-cohost/LANDING-u2cljs-u5-STATUS.md.
Gate logs: ~/workspace/wt/collate-u2cljs-u5-logs/ (boot-jvm.log,
cli-check-cljs.log, boot-cljs.log).

## Merges (in order, onto base muse/eve-cohost-impl @ 0fa021f)

1. **96cd795** — merge unit 2 cljs half (muse/eve-cohost-cljs-dep @
   e1de808): Eve as a dependency of cohost's cljs build, the cljs
   thread leaves, and the future/pvalues macro fix (mark-macro! sets
   .-cljs$lang$macro). NO conflicts.
2. **d177972** — merge unit 5 (muse/eve-cohost-impl-u5 @ b142ffe):
   Atom onto Eve atoms + var registry. Conflicts in 4 files, resolved:
   - src/cohost/boot.cljc — the :host-lacks line as the UNION: u5's
     :eve-atoms state key kept; :host-lacks = caller's ∪
     (host/core-lacks) ∪ (atom-surface-names when :eve-atoms). This is
     the shape unit 7's lane recorded from its own u5 merge.
   - src/cohost/shim.cljc — u5's added [cohost.atom :as atom-layer]
     require taken.
   - src/cohost/memory.cljc, probe_memory.clj — doc/header-only
     differences (code identical on both sides); the mainline
     (unit 3, heap-ruling) wording kept.
   - Paren checks after resolution: shim 881 forms OK, boot 138 OK,
     memory 8 OK, probe_memory 58 OK.

## Verification on the merged tree, as printed

- JVM gate (bb boot:jvm): "booted as stock: 465   from host: 202
  natives: 66   skipped: 58   errors: 0"; **battery: 221/221** stock
  behaviors pass; **libraries: 62/62** cases pass across 12
  clojure.* libraries.
- cljs cli:check (bb cli:check cljs): **7/7 SAME**.
- cljs boot (bb boot:cljs): **rc=0**; "booted as stock: 548
  from host: 87   natives: 46   skipped: 140   errors: 0"; the FAIL
  pair is exactly the pristine-base pair — "FAIL numeric tower",
  "FAIL defmacro &form &env" — identical to the cljs-dep branch's
  own banked gate log. Nothing new.
- Conflict-marker sweep on the committed merged tree (d177972):
  line-anchored <<<<<<< / >>>>>>> / ======= = **0 / 0 / 0** over the
  whole tree, no extension filter. (One unanchored hit: the word
  "(<<<<<<< / >>>>>>>)" in prose in the previous landing's
  COLLATE-STATUS.md, present on base 0fa021f — not a marker.)

No gate regressed against either branch's own numbers, so nothing
was fixed forward.

The pre-landing tip of this declaration's branch is b86e774; this
declaration commit is the mainline tip — read it back on the
fetched remote ref of muse/eve-cohost-impl.

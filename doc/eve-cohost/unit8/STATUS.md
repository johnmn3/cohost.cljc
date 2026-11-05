STATUS LOCATION FALLBACK: github.com/johnmn3/atom.broker could not be attached (add_repo push access was denied in this session), so per the order STATUS lives here on the code branch, doc/eve-cohost/unit8/STATUS.md.

# Unit 8: the shared-heap illusion on the cljs seat — STATUS

Branch `agent1/eve-unit8-cljs-shared-heap` (cohost.cljc). All times US Eastern.

## Log
- 2026-11-05 — branch created from agent1/eve-unit7-tiered-atoms @ 2c991ea; mainline
  muse/eve-cohost-impl @ 21e1d9f merged (it already carries unit 2 cljs half e1de808 + unit 5).
  One conflict (boot.cljc :host-lacks, both sides equivalent; unit 7 form kept). Pushed @ 4d8771f.
- Toolchain installed: Clojure CLI 1.12.6.1673 (official linux-install.sh, sha256-pinned tarball),
  babashka v1.13.229 (release binary, sha256 verified), node v22.23.4 (preinstalled).
- Spec read: MEMORY-PLAN.md §4 Unit 8 + rulings §§7–10. Gate = probe cljs row flipped (23/23
  JVM-equal, program byte-identical) + a worker-crossing capture case through the shared atom;
  cljs cli:check rows SAME; JVM/jank batteries untouched.
- Starting point (cljs-dep FINAL): cljs row ok=22 fail-other=1 (spec-conform), cooperative
  same-realm leaf; unit 8 owns real workers + Eve SAB atom as the realm bridge.

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
- De-risk probe PASS (dev/u8/u8_worker_probe.cljs, log unit8/logs/u8-worker-probe.log): a worker realm
  started by eve.thread-host/start-threads! (fat-kernel mesh) joins the main realm's in-process Eve
  domain from handed-over SABs (process-slab/init-worker-slabs! + domain root/reader-map regions);
  both realms swap one atom; final {:n 4000, :worker-wrote {...}} exact. SAB handover channel:
  Node's worker_threads.setEnvironmentData (public; no Eve source touched). Named Eve-side gap: Eve
  has no public "join an in-process domain from a worker" — the domain is rebuilt from Eve's own
  (private) mmap-claim-slot!/make-domain-state; class-6 overflow (>1 KB blocks) allocation from a
  worker is not wired by init-worker-slabs! (registers the SAB views only).
- Design (implementing now): cohost.hosts.cljs-eve (Node seat Eve hosting: atom factory over SAB
  domains + domain directory; realm mesh over eve.thread-host; inbox/outbox Eve atoms = mailboxes;
  crossing codec: atoms by [domain slot] reference, var-root fns by qualified name, data as Eve
  values); cohost.thread future/pmap carry forms on a seat with :realm-submit; worker realm boots the
  same cohost image and evaluates (self-hosted cljs can eval — the refusal in eve.thread-host's
  eval-form is for compiled cljs without a compiler).

# CLJS-DEP REPORT — Eve as a dependency of cohost's ClojureScript build + the cljs :thread-spawn cap

Branch `muse/eve-cohost-cljs-dep` (from `muse/eve-cohost-impl` @ 40c75a1). 2026-11-05.
Status detail: doc/eve-cohost/CLJS-DEP-STATUS.md. All numbers below were run on this branch, on this box.

## Step 1 — the dependency: DONE

**Mechanism chosen** (lightest the build supports; no vendored copy):

1. **A tools.deps git dep in the `:cljs` alias of `deps.edn`**:
   `eve/eve {:git/url "https://github.com/SeniorCareMarket/eve" :git/sha "d241de3fbc27e5dd80d5f1e17661103b0680b9c0"}`.
   SeniorCareMarket/eve's HEAD is exactly d241de3 (verified by ls-remote; the "d241de3 not on GitHub"
   blocker elsewhere concerns the other repo, johnmn3/eve). The pin moves to the Eve 0.9.3 release commit
   when that release lands (release-first dependency); numbers here are pre-release numbers against d241de3.
2. **Eve's hosts.edn `:cljs` adapter row on the compile classpath**, composed at build time by
   `dev/eve-cljs/eve_paths.clj` (reads the pin from deps.edn, locates the gitlibs checkout, reads ITS
   hosts.edn, emits the `-Sdeps` `:paths`). This is Eve's own consumption contract (hosts.edn header;
   doc/adopter-tiers.md §0: a host is Eve's `src` plus one hosts.edn row of adapter roots; Eve's bb.edn
   composes classpaths the same way). It is *necessary*, not decorative: a dep contributes only its root
   `:paths`, and Eve's root paths are its JVM row — with the git dep alone the compile dies at
   `Invalid :refer, var eve.host/aget does not exist` (the cljs runtime side of `eve.host` lives only in
   the cljs row's `adapters/core/cljs-compiler`).
3. **Four one-line foreign-lib bridges** (`dev/eve-cljs/bridges/node_{fs,os,path,worker_threads}.js`,
   provided as `:foreign-libs {:provides ["node:<m>"] :global-exports {…}}`): stock cljs.main 1.12.42
   auto-resolves plain Node builtin requires (`"fs"`, `"crypto"`, …) but not the `node:`-prefixed
   spellings; exactly one Eve file uses them (`adapters/thread/node/src/eve/thread_host.cljs`).
   No Eve source was touched anywhere in this step.

**Proof** (banked: `doc/eve-cohost/logs/eve-cljs-probe-out.log`; probe source `dev/eve-cljs/eve_cljs_probe.cljs`, recipe =
Eve's own batch4 test surface: `eve.process-slab/init!`, pool resets, `(a/atom-domain {})`, `eve.sab/get-env`):

```
EVE-PROBE eve.atom shared-atom? true
EVE-PROBE eve.atom initial {:n 0, :msg "hello"}
EVE-PROBE eve.atom after-swap {:n 1, :msg "round-trip"}
EVE-PROBE eve.atom after-reset {:n 42}
EVE-PROBE eve.atom RESULT PASS
```

A SharedArrayBuffer-backed Eve atom, constructed under Node in a build whose classpath is cohost's own
plus the Eve dep, with swap!/reset!/deref round-tripping a value. Node exit 0.

Two Eve-side findings, named (not worked around):
- **Eve's current SharedAtom root is `eve.atom`** (atom.cljc: atom :1398, atom-domain :1446); the legacy
  `eve.shared-atom` namespace's own allocator path fails on a virgin domain even after process-slab/init!:
  `atom constructor: Failed to alloc state block: :out-of-memory` (its telescope dump in the same log shows
  65,536 descriptors, one 265 MB FREE block, SAB=MIRROR OK). Eve-side defect or legacy-path rot; Eve's own
  current tests use eve.atom.
- Eve's runtime needs `eve.process-slab/init!` before any atom exists (without it:
  `No protocol method IMemRegion.-load-i32 defined for type null`). Consumer-side recipe, now in the probe.

**Regressions: none.** `bb boot:cljs` — exit 0, FAILs exactly the pristine-base pair (numeric tower;
defmacro &form &env — verified identical on an unmodified 40c75a1 worktree). `bb cli:check cljs` — see
STATUS for the row (run after this report's step-2 work; the standing baseline is 7/7 SAME).

## Step 2 — the spawn cap: WRITTEN; probe row ok=15 → ok=18

**What the machinery actually is** (read, cited):
- cljs-thread (github.com/johnmn3/cljs-thread @ 8eaad5d): `cljs-thread.core/init!` stands up worker pools
  (:core/:future/:injest); `spawn`/`future` are macros (src/cljs_thread/spawn.cljs, future.cljs) — the
  FORM crosses to a worker booted from the same image; bindings and shared atoms ride along.
- Eve's later lineage of the same design: `eve.thread-host` (adapters/thread/node) — fat-kernel workers
  (`new Worker(kernel-source, {eval:true})`, kernel = process.argv[1]), EDN messages; and `eve.thread`
  (src/eve/thread.cljc:14) states the cross-heap rule verbatim: only a registered task's name (deftask) or
  a form (tfn) travels — **"a plain closure is refused."**

**The wall, exactly**: `cohost.thread`'s work is closures over realm-local plain atoms — `spawn`
(thread.cljc) hands the cap `(fn [] … (f) …)`; executor queues, the registry and future state cells are
ordinary `(atom …)`s in the caller's realm. A worker realm cannot receive that closure, and the layer
gives the cap no form to convey. No cap confined to `src/cohost/hosts/cljs.cljs` can change what the
layer hands it; the form-carrying fix lives in the layer/shim (a tfn-style future-call), in files owned
by sibling lanes. This — not any absence of spawn machinery — is what unit 2's cljs half was actually
looking at.

**The leaf written** (src/cohost/hosts/cljs.cljs: `:thread-spawn`, `:thread-self`, `:doorbell`,
`:doorbell-wait`, `:doorbell-signal` — the same cap shape as the other seats): cooperative tasks on the
one realm. A spawned thunk queues as a task; the layer's blocking waits pump the queue until their own
bell rings (only the outermost wait pumps — flat stack; a task that reaches an unrung bell yields and is
re-queued). Tasks interleave at waits; they do not run in parallel. Named distances, in the code comment:
a thunk that acts and then waits on a not-yet-rung bell may re-run its early actions on re-entry; the
layer's executor `:running` count drifts when a worker task yields inside its finally. The steered target
shape (John, 2026-11-05 ~13:06 ET) is the mesh: a core thread stood up first, controlling the others,
everything available to it synchronously — that is cljs-thread's/eve.thread's architecture and it is
reachable the day the layer speaks forms (tfn/deftask); it is not reachable by any adapter leaf under
the closure-based layer, and this report does not pretend the cooperative leaf is it. Boot parity
evidence for the leaf as it stands: the probe row below ran through the CLI (`bin/cohost :cljs`), and
`bb boot:cljs` (the REPL-side run bundle) boots and self-tests with the same caps (baseline-identical).

**Probe row** (`bb dev/eve_probe.clj cljs`, banked: `doc/eve-cohost/logs/probe-cljs-row2.log`):

```
SUMMARY|cljs|ok=18 unsupported-shim=0 fail-other=5 wall-ms=20822
```
Baseline (unit 2, same runner): ok=15, fail-other=8. **Flipped: promise (OK 7), agent (OK 1),
pmap (OK (2 3 4))** — the executor family the caps exist for; ok=18 now equals the jank seat's row.
Also verified directly: `(deref (future-call (fn [] 42)))` → 42 through the layer.

**Still failing, each with its mechanism**:
- future-basic / future-capture-local / future-capture-atom / future-binding — FAIL
  `java.lang.NullPointerException :: Cannot read properties of undefined (reading 'call')`, at cljs.js
  ANALYSIS time, identical to baseline. Cause: the `future` macro itself never reaches user code on this
  seat. `cohost.thread/core-macros` (thread.cljc:665-668) holds exactly two fills — `future`, `pvalues`;
  post-boot, `(ns-interns 'cohost.core)` shows both ABSENT (nil) while every fn-side name resolves
  (future-call, agent, send, pmap). The interning site is `cohost.boot/install-thread-layer!`
  (boot.cljc:909-927, called at boot.cljc:995) — boot-side machinery, not the adapter. The macro's
  expansion target, `cohost.core/future-call`, is proven working above, so when the macro interning
  lands the four cases flip with no further adapter change.
- spec-conform — FAIL ExceptionInfo "ERROR", unchanged from baseline; unrelated to this lane
  (clojure.spec.alpha eval path).

## Correction of unit 2's NOT-YET framing (explicit, as ordered)

Unit 2 banked the cljs half as NOT-YET on three claims. All three are wrong:
1. *"Nothing in Eve's cljs sources spawns a worker."* False twice over: cljs-thread spawns workers by
   design (its repo above; Eve's unified runner boots it), and Eve's own `eve.thread-host` node adapter
   spawns fat-kernel workers today.
2. *"The layer hands an opaque closure that cannot cross realms; no such contract exists — design work,
   not a leaf."* Misframed: the conveyance contract exists and is old (forms/tasks, above). The true
   blocker is narrower and lives in cohost.thread, not in any missing contract: its work items are
   closures, and cross-heap machinery refuses plain closures. An adapter leaf (this lane's) still moves
   the seat from no thread layer at all to a working one for the executor family.
3. *"Eve is not in the cljs build."* Was true; removed by step 1 (git dep + hosts.edn row + bridges).

## What unit 8 now still owns

- The **shared-heap illusion proper**: cross-worker shared atoms as the realm bridge (Eve atoms over SAB
  between real workers), bindings conveyed transparently under library code — the cljs-thread mesh with
  a core thread stood up first (John's stand-up pattern), which requires the layer to speak forms:
  a tfn/deftask-carrying future-call in cohost.thread/shim (the named remainder above), then a
  :thread-spawn leaf over cljs-thread/eve.thread-host workers in place of the cooperative leaf.
- Nothing in step 1 blocks it: Eve is in the build, the SAB atom round-trips, and eve.thread-host's
  worker machinery compiles in this build today (it is in shared-atom's transitive set).
- The `future`/`pvalues` macro interning (boot.cljc) is a boot-lane item, listed here because the probe
  row shows it; it is independent of unit 8's design work.

## Addendum 2026-11-05 ~13:18 ET — mesh stand-up pattern conformance

Per the operator's directive (~13:06 ET: core thread first, controlled from the core, synchronous
access, REPL/CLI boot parity as gate evidence), CLJS-DEP-STATUS.md's addendum records the assessment
in full. Summary: the leaf conforms in control topology (one controlling flow owns all units; spawn
only enqueues; no peer autonomy) and in synchronous access (join/deref/await complete synchronously
from the controlling flow); it diverges in realm topology (cooperative same-realm tasks, not a core
worker + worker mesh) for the closure reason named above, with the form-carrying layer as the
correction path. Boot parity is now measured, not inferred: the identical probe program through the
REPL-side bundle (cohost.run --eval) and the CLI bundle gives a case-for-case identical result vector
(logs/probe-program-run-bundle.log vs logs/probe-cljs-row2.log). The coordinator's independent cljs
verification also passed today via cljs.core protocols over the same dep + hosts.edn row (shadow-cljs
route; slab atom round trip {:n 2}) — reconciled in the STATUS addendum.

---

## Addendum (follow-on lane, 2026-11-05): the future/pvalues macro-fill gap — CLOSED

**Mechanism.** The lane FINAL named this gap as "the fills never intern on this seat." Firsthand
diagnosis narrowed it: the fill fns do intern — as properties on the plain and $macros namespace
objects, with :use-macros resolving them in the user env identically to a stock macro (the
ns-interns ABSENT reading was :defs-blindness: ns-interns builds handles from :defs entries only,
and neither fills nor stock macros carry a $macros :defs entry on this seat). The analyzer's
macro path (cljs 1.12.42, self-hosted) is: get-expander* -> findInternedVar on the $macros object
-> cljs.core Var.isMacro, which reads the property (.-cljs$lang$macro fn) off the function object
itself. cljs's own defmacro emits (set! (. name -cljs$lang$macro) true) to set that marker; the
cohost.thread fill fns, interned as plain fns and moved by mark-macro!, never acquired it. With
the marker absent the analyzer treated (future ...) as a function call of an undeclared var, and
the compiled call dereferenced undefined at runtime: the row's
"Cannot read properties of undefined (reading 'call')".

**Fix.** One site, the owning layer: src/cohost/hosts/cljs.cljs mark-macro! now sets
(set! (.-cljs$lang$macro f) true) when moving the fn to the macros side (commit 3e7f109). The
shim's clojure.lang.Var/setMacro routes through the same cap, so stock setMacro callers get the
marker too. No changes to boot.cljc, thread.cljc, or the analyzer state shape.

**Row as printed after the fix** (bb dev/eve_probe.clj cljs; logs/probe-cljs-row3.log):
SUMMARY|cljs|ok=22 unsupported-shim=0 fail-other=1 — future-basic OK 3, future-capture-local
OK 42, future-capture-atom OK 1, future-binding OK 3; promise OK 7, delay OK 2, agent OK 1,
pmap OK (2 3 4), test-stacktrace OK 0 unchanged; spec-conform remains the pre-existing unrelated
FAIL. Diff against the previously banked row: exactly the four future lines. pvalues verified
directly (=> (2 4)). Boot parity re-run: REPL entry vs CLI entry case-for-case identical
(logs/probe-program-run-bundle-after-fix.log). Gates: bb boot:cljs rc=0 with exactly the
pristine-base FAIL pair; bb cli:check cljs 7/7 SAME (logs/gates-cljs-after-fix.log).

With this, unit 2 (seat leaves) is complete on all three seats: JVM+jank landed on
muse/eve-cohost-impl @ 0fa021f; cljs closed here.

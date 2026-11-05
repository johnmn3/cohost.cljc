# CLJS-DEP lane STATUS — Eve as a dependency of cohost's cljs build + the cljs :thread-spawn cap

**FINAL — both steps complete 2026-11-05. Full account: doc/eve-cohost/CLJS-DEP-REPORT.md.**
Step 1: Eve git dep (pin d241de3) + hosts.edn :cljs row composition + node: bridges; SAB shared-atom
round trip PASS under Node (`eve.atom` RESULT PASS, exit 0; log in doc/eve-cohost/logs/).
Regressions: boot:cljs exit 0 with exactly the pristine-base FAIL pair; **cli:check cljs 7/7 SAME**.
Step 2: cooperative-task thread leaves written in src/cohost/hosts/cljs.cljs (spawn/self/doorbell trio);
probe row **ok=18, fail-other=5** (baseline ok=15/fail-other=8) — promise, agent, pmap flipped.
Remaining fails, mechanisms named in the report: the four future cases (the `future`/`pvalues` macro
fills never intern on this seat — boot.cljc install-thread-layer!, not the adapter) and spec-conform
(pre-existing, unrelated). The real-worker wall is cohost.thread's closure work items vs the form-only
conveyance of cljs-thread/eve.thread-host — named with file citations in the report, which also corrects
unit 2's NOT-YET framing claim by claim and scopes what unit 8 still owns.

Branch: `muse/eve-cohost-cljs-dep` (from remote `muse/eve-cohost-impl` tip `40c75a1`, verified by ls-remote).

## Addendum 2026-11-05 ~13:18 ET — the mesh stand-up pattern (operator directive ~13:06 ET) vs this lane's leaves

The directive: a CORE THREAD stands up first and the other threads are controlled FROM it (no flat
peer spawn); once it is up, the core has SYNCHRONOUS access over the rest of the mesh; REPL-boot and
CLI-boot of the same program must come up identically (boot parity is gate evidence).

Against the leaves as banked (cooperative tasks in src/cohost/hosts/cljs.cljs):
- **Core ownership — conforms in control topology.** There is exactly one controlling flow: the
  program's main flow. `:thread-spawn` never starts anything autonomous — it enqueues a task, and
  tasks execute only when the controlling flow pumps (inside its blocking waits, or the scheduled
  macrotask pump). No spawned unit fends for itself; every unit's progress is dispatched from the
  one flow, as the pattern's "controlled FROM the core thread" requires.
- **Synchronous access — conforms.** Once running, every unit is synchronously reachable from the
  controlling flow: join, future deref and agent await complete synchronously (the wait pumps the
  queue until the bell rings). There is no async-only access path anywhere in the leaf.
- **Realm topology — diverges, named.** In cljs-thread's mesh the core is a distinct worker and the
  controlled threads are real workers. Here the "core" is the main flow itself and the units are
  cooperative tasks on the same realm — no real workers — for the reason banked in the REPORT:
  cohost.thread's work items are closures over realm-local atoms, and worker machinery
  (cljs-thread.spawn, eve.thread-host) conveys forms/tasks only ("a plain closure is refused",
  eve.thread). The correction path is the layer learning to speak forms (tfn/deftask), after which
  the leaf is re-pointed at a true core worker + worker mesh; that is unit 8's scope as banked.
  The leaf's control shape was deliberately built to the pattern above so that re-pointing changes
  the substrate, not the topology.
- **Boot parity — now measured, identical.** The same probe program run through the REPL-side entry
  (`cohost.run`, target/cljs-node, `--eval`) and the CLI entry (`bin/cohost :cljs`, target/cljs-cli)
  gives a case-for-case identical result vector (banked: logs/probe-program-run-bundle.log vs
  logs/probe-cljs-row2.log): future ×4 FAIL (the macro-fill gap), promise OK 7, delay OK 2,
  agent OK 1, pmap OK (2 3 4), spec-conform FAIL (pre-existing), test-stacktrace OK 0, done OK.

**Coordinator verification reconciled**: the coordinator's independent cljs verification PASSED
today through cljs.core protocols (git dep + hosts.edn :cljs row + shadow-cljs; slab atom
swap!/deref round trip {:n 2}). This lane's banked protocol claim was right; the coordinator's
earlier failure was a wrong call shape on his side, now reconciled. (This lane's own proof used
cljs.main rather than shadow-cljs and the eve.atom recipe; both routes land on the same dep + row.)
Started 2026-11-05 ~11:53 ET. Scope (corrected ~11:54 ET): TWO steps in order —
(1) Eve loadable in cohost's cljs build + SAB shared-atom round trip under Node + cljs checks unregressed;
(2) the cljs adapter `:thread-spawn` cap over the existing cljs.thread / Eve cljs machinery + the probe's cljs row.
Owns ONLY cljs build/dependency wiring + cljs adapter caps. Sibling lanes: units 4/5 (impl branches) — no file collisions expected; will flag here if any appear.

## Stage 0 — orientation (DONE ~12:00 ET)

### How the cljs seat builds today
- `deps.edn` (repo root): `{:paths ["src" "resources"] :deps {org.clojure/clojure 1.12.0} :aliases {:cljs {:extra-deps {org.clojure/clojurescript 1.12.42}}}}`.
- `bb boot:cljs`: `clojure -M:cljs -m cljs.main -co cljs-node.edn -c` then `node target/cljs-node/main.js`.
  `cljs-node.edn` = `{:main cohost.run :target :nodejs :output-to "target/cljs-node/main.js" :optimizations :none}`.
- CLI path (`bin/cohost :cljs`, what the probe and `cli:check` drive): same `-M:cljs` machinery with `cljs-cli.edn` (`:main cohost.cli`, nodejs, :none).
- So "dependency" in this build = a tools.deps coordinate in `deps.edn` (or its `:cljs` alias). One edit point covers boot, CLI, and check.

### The Eve piece the build must reach
- Eve repo: `~/src/eve`, origin `https://github.com/SeniorCareMarket/eve`, main `d241de3fbc27e5dd80d5f1e17661103b0680b9c0`.
  **Verified by ls-remote 2026-11-05 ~12:05 ET: SeniorCareMarket/eve HEAD IS d241de3** — the exact line whose
  `shared_atom.cljs` the heap spike measured. (The heap-grade coordinator's "d241de3 not on GitHub" blocker concerns
  the *other* repo, johnmn3/eve, whose HEAD is a claude branch — different repo.)
- Eve is itself a tools.deps library: its `deps.edn` declares `:paths ["src"]`. Namespaces `eve.*` live under `src/eve/`.
- Target namespace: `eve.shared-atom` (`src/eve/shared_atom.cljs`). Constructor `(eve.shared-atom/atom v)`
  (shared_atom.cljs:3447); `(atom-domain …)` at :2507. On the main thread the namespace auto-creates the global
  domain at load: `(when u/is-main-thread? … (set! *global-atom-instance* (atom-domain {})))` — a 100 MB
  SharedArrayBuffer-backed domain. `swap!`/`reset!`/`deref` work through cljs.core protocols on the `SharedAtom`
  deftype; cross-thread watches ride `Atomics.notify` + `waitAsync` loops (shared_atom.cljs:2391–2458).
  API precedent in Eve's own tests: `test/eve/batch4_validation_test.cljs` (`(a/atom {:x 42})`, swap!/reset!/deref).
- Transitive cljs deps of eve.shared-atom: eve.atom, eve.data, eve.util, eve.wasm-mem,
  eve.deftype-proto.{alloc,serialize,data} (+ their requires). WASM init at load is best-effort with a JS fallback
  (load-time `.catch` warns only) — no hard landmine expected; npm/string requires still to be swept before compile.

### Mechanism decision (step 1)
**Git dep in the `:cljs` alias of cohost's `deps.edn`:**
`eve/eve {:git/url "https://github.com/SeniorCareMarket/eve" :git/sha "d241de3fbc27e5dd80d5f1e17661103b0680b9c0"}`.
- Lightest mechanism the build already supports: no vendored copy (no sync liability), no path arithmetic
  (a `:local/root "../eve"` resolves differently from worktrees vs ~/src/cohost — rejected for that reason).
- Scoped to the `:cljs` alias so the dep lands only on the cljs seat's classpath, not every seat's.
- Matches the plan's direction: Eve-in-cohost consumes Eve as a dependency; the pin moves to the Eve 0.9.3
  release commit when that release lands (release-first dependency, John ~11:48 ET), so cljs numbers from this
  lane are pre-release numbers against d241de3.

### cljs.thread recon (step 2, in progress)
- John's correction stands: spawn on cljs is cljs.thread's existing design, not an open contract question.
- Eve's unified integration runner (`~/src/eve/test/eve/runner/eve_integration_unified.cljs`) requires
  `[cljs-thread.core :as thread]` + `[cljs-thread.env :as e]`; the same file serves as main-thread entry AND
  worker source; `thread/init!` dispatches the main fn to a `:core` worker when pools are ready; workers boot
  the same compilation unit. Exported test `cljs_thread.eve_batch5_validation_test.spawn_conveys` =
  `spawn-conveys-shared-atom` — spawn conveying a shared atom is a tested cljs.thread behavior.
- The original cljs-thread test files are vendored in atom.broker at `vs/test/cljs-thread-originals/`
  (ns `cljs-thread.*-test`, using `(:require-macros [cljs-thread.core :refer [spawn future]])`).
- **Open: the cljs-thread library sources' home.** They are in NO ref of ~/src/eve (git log --all file list: zero
  hits) and not in the atom.broker clones' indexes — Eve's integration build expects them supplied externally.
  Hunt in progress (filesystem + GitHub). Unit 2's NOT-YET claim (1) ("nothing spawns a worker") is already
  falsified by the runner above; the report will correct all three NOT-YET claims explicitly.

## Stage 1 — the dependency (IN PROGRESS)

Banked: `0d637ff` — deps.edn :cljs alias carries the Eve git dep (pin d241de3); probe + paths helper + bridges.

### What the git dep alone does NOT give a cljs consumer (the real wiring problem, solved)
Eve's root `:paths` are its **JVM host row** (hosts.edn :jvm). A tools.deps dep contributes only its root
`:paths`/`:deps` — a consumer cannot select Eve's `:node-test` alias, where the cljs row lives as
`:replace-paths`. First compile failed exactly there: `Invalid :refer, var eve.host/aget does not exist`
(`eve.deftype-proto.data` → `eve.host`): on the JVM row, `eve.host` is the Clojure adapter's .cljc (macros);
the cljs runtime side is `adapters/core/cljs-compiler/src/eve/host.cljs` (`(:require-macros [eve.host])`),
which only the cljs row carries.

**Eve's sanctioned consumer mechanism** (hosts.edn header; doc/adopter-tiers.md §0; Eve's own bb.edn
`classpath` task): a host = Eve's `src` + one hosts.edn row of adapter roots on the classpath after src.
Cohost's cljs build now composes exactly that: `dev/eve-cljs/eve_paths.clj` reads the pin from deps.edn,
locates the gitlibs checkout (`$GITLIBS/libs/eve/eve/<sha>`), reads ITS hosts.edn, and emits the `-Sdeps`
`:paths` for the `:cljs` row. No Eve files copied; the row comes from the pinned checkout itself.

### Wall 2: `node:`-prefixed requires vs stock cljs.main
With the row on the classpath the compile advanced to Eve's Node thread adapter
(`adapters/thread/node/src/eve/thread_host.cljs`): `No such namespace: node:fs`. Census: exactly ONE file in
Eve's cljs-row sources uses `node:` string requires (fs, os, path, worker_threads); plain spellings
(`"fs"`, `"crypto"`, `"http"`, `"worker_threads"`) resolve automatically under `:target :nodejs` (verified
by micro-probe), the `node:` spellings do not — shadow-cljs (Eve's own compiler) accepts them, cljs.main
1.12.42 does not. Cohost-side bridge, standard cljs machinery, no Eve source touched: four one-line
foreign libs (`dev/eve-cljs/bridges/node_*.js`: `globalThis.__eve_node_<m> = require("node:<m>")`) provided
as `:foreign-libs {:provides ["node:<m>"] :global-exports {"node:<m>" __eve_node_<m>}}`. (First attempt via
`:module-type :commonjs` failed — Closure's CJS conversion mangles the re-export; `:global-exports` verified
working on a micro-probe: `T2 platform: linux`.)

### Wall 3: Eve's runtime init recipe (consumer-side, from Eve's own tests)
- Direct `(eve.shared-atom/atom …)` after load: `No protocol method IMemRegion.-load-i32 defined for type
  null` — slab regions don't exist until **`eve.process-slab/init!`** runs (creates the six slab classes'
  SABs/regions + root SAB synchronously; its Promise covers only the WASM upgrade).
- With ps/init! alone, shared-atom's constructor still failed: `Failed to alloc state block:
  :out-of-memory` against a virgin domain SAB (telescope dump: 65,536 descriptors, one 265 MB FREE block,
  SAB=MIRROR OK) — eve.shared-atom's own allocator/domain path is not the surface Eve's current tests
  exercise. **Eve's current SharedAtom root is `eve.atom`** (atom.cljc: `atom` :1398, `atom-domain` :1446,
  `shared-atom?` :1418; self-contained, no require of eve.shared-atom). Eve's batch4 recipe (fresh-env):
  reset eve.map/eve.set pools → `(set! a/*global-atom-instance* (a/atom-domain {}))` → `eve.sab/get-env`.
  Probe rewritten to that recipe (eve.shared-atom attempt kept, guarded, for the record). Run in flight.

### cljs.thread recon update (step 2)
`eve.thread-host`'s own docstring names the lineage: "the mesh cljs-thread grew" / "the later cljs-thread
lineage's fat-kernel route — new Worker(kernel-source, {eval: true}), the kernel being the compiled program
this process runs (process.argv[1])… The program's entry point calls run-thread! first." Eve's smoke main
(`test/eve/runner/eve_smoke_main.cljs`) uses the cljs-thread LIBRARY directly: namespaces
`cljs-thread.core` (`init!`, macros `in`/`on-when`), `cljs-thread.state`, `cljs-thread.strategy.fat-kernel`
(`install! {:kernel-source-str …}`), with worker pools :core/:future/:injest. The library sources' home is
still unlocated (not in Eve, not in atom.broker clones); GitHub check next.

## Next
1. Probe run result → bank output; regressions (`bb boot:cljs`; cli:check cljs row).
2. Decide the durable call-site wiring (bb.edn boot:cljs/bench:cljs + bin/cohost ensure-cljs-build!) once
   step 2 shows whether the seat's own host build must carry Eve (the cljs adapter lives in that build).
3. Step 2: locate cljs-thread sources; read spawn API; write caps; run probe cljs row.

# CLJS-DEP lane STATUS — Eve as a dependency of cohost's cljs build + the cljs :thread-spawn cap

Branch: `muse/eve-cohost-cljs-dep` (from remote `muse/eve-cohost-impl` tip `40c75a1`, verified by ls-remote).
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

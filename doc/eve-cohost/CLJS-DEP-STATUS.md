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

## Next
1. Sweep eve.shared-atom's transitive requires for npm/string requires.
2. deps.edn edit → probe compile (require eve.shared-atom) → Node round trip (atom/swap!/deref), output banked.
3. Regressions: `bb boot:cljs` answers; `cli:check` cljs row vs standing 7/7 SAME baseline.
4. Step 2: locate cljs-thread sources; read its spawn API; write the adapter caps; run the probe cljs row.

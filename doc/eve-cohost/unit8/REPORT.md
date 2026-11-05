# Unit 8 REPORT: the shared-heap illusion on the cljs seat

Branch `agent1/eve-unit8-cljs-shared-heap` (cohost.cljc), from unit 7 (`agent1/eve-unit7-tiered-atoms`
@ 2c991ea) with the mainline `muse/eve-cohost-impl` @ 21e1d9f merged in (it carries unit 2's cljs half
e1de808 and unit 5). Spec: MEMORY-PLAN.md §4 Unit 8 plus the rulings in §§7–10. Status detail is in
STATUS.md next to this file, and the logs are in `logs/`. Every number below was printed on this box
(a fresh cloud container) on this branch, 2026-11-05 ET.

> **STATUS LOCATION:** github.com/johnmn3/atom.broker could not be attached because push access was
> denied in this session. Per the order, STATUS and REPORT live here on the code branch.

## What was built

On Node, the program's heap on the ClojureScript seat is now Eve's. Atoms (the shim's `Atom`, i.e.
`cohost.atom`) are Eve atoms. They live in in-process Eve domains over SharedArrayBuffers, which every
realm of the process maps. A `future` whose work can travel runs in a **worker realm**: a separate V8
isolate booted from the same image. Its captured values, the atoms it touches and its binding frame
cross **through Eve atoms**. Library and program code is unchanged: the probe program is
byte-identical, `pmap` is core's, and the future fill expands the same macro call.

| Piece | Where | Over which Eve facility |
|---|---|---|
| Realms: the core realm (main thread, stood up first, controls the rest) and worker realms | `src/cohost/hosts/cljs_eve.cljs` | `eve.thread-host/start-threads!`: fat-kernel workers, port mesh, SAB doorbell (Eve's Node thread adapter, unmodified) |
| The heap: program atoms in Eve in-process domains, sharded at Eve's slot limit | `cljs_eve.cljs` atom factory, installed into `cohost.atom` | `eve.atom/atom-domain`, `lookup-or-create-mmap-atom!` |
| A worker joins the heap | `cljs_eve.cljs` `install-realm!` / `join-domain` | `eve.process-slab/init-worker-slabs!` (Eve's worker path) plus each domain's root and reader-map SABs. SABs are handed over through Node's public `worker_threads.setEnvironmentData` at start and a `BroadcastChannel` afterwards |
| Mailboxes | the inbox Eve atom (a vector of tasks, claimed by CAS) and the outbox Eve atom (`{"t<id>" outcome}`) in the core's mail domain | Eve atoms; the mesh only rings (`{:cohost/task}`, `{:cohost/done id}`) |
| Crossing codec | `cljs_eve.cljs` `encode`/`decode` | Data crosses as Eve values. A layer atom crosses as its Eve handle (domain key + slot), and the receiver wraps the same Eve atom. A var-root fn crosses as its qualified name (eve.thread's rule: a name travels, a plain closure is refused) |
| The form-carrying future | `cohost.thread/future-form-call`; the `future` fill expands to `cohost.realm/future-form-call` with the form, its free locals' values and the closure; `pmap*` items carry `(fn [f xs] (apply f xs))` | eve.thread's `tfn` shape (`free-locals` reimplemented for a cljs env's `:locals`) |
| Seat cap | `cohost.adapter` `:realm-submit` (default nil: **every other seat is unchanged**; the fill expands as before wherever it is nil) | — |
| Worker realm life | `src/cohost/hosts/cljs_realm.cljs` `realm-main`: join the heap, boot as the core booted, add the source roots, serve tasks (`boot/eval-in` of the form in its namespace; a namespace unknown to the worker is required from the source roots, else made bare) | — |
| Entry points | `cohost.cli` and `cohost.run` on cljs: `run-thread!` first (a worker runs this image), then boot with `:eve-atoms true` (`COHOST_CLJS_EVE=0` turns the seat back to one realm) | — |
| Build | `cljs-cli.edn` and `cljs-node.edn` carry the four `node:` bridges; `bin/cohost`, `bb boot:cljs`/`bench:cljs` and `dev/bench_programs.clj` compose Eve's hosts.edn `:cljs` row (`dev/eve-cljs/eve_paths.clj` `eve-cljs-build-sdeps`). The browser build is untouched | Eve's consumption contract (hosts.edn row) |

When a future's values cannot cross (a closure, a host object, an atom whose root sits in one realm's
host seat), the future runs as a closure in the core realm on the cooperative leaf, as before this unit,
and `stats` counts it. That is the layer's existing path for work that is not data, not a substitute
heap. In every gate run below the count was **0**: every future crossed.

### Two cohost defects found and fixed on the way (both cljs seat)
1. **`require` as a function** (inside a `try` or a fn, anywhere a top-level directive is not) was the
   host's cljs `require`. It compiled to `goog.require`, loaded nothing and returned as though it had.
   This is why the probe printed `load-clojure.spec.alpha OK` and then failed `spec-conform` with
   `Cannot read properties of undefined (reading 'alpha')`. The failure predates this unit: it is in
   every banked cljs row since the original probe. Fix: `boot/install-runtime-loader!`, called by the
   cljs entry points, routes `require`/`use` calls through cohost's loader.
2. **Deref of a layer atom by core fns compiled before the fill.** During boot `@x` stays host plumbing,
   so `memoize`'s `@mem` called `cljs.core/deref` on a tagged map. This was the battery's memoize case,
   which went to 218/221 once the fill was on. Fix: on ClojureScript the layer's value is a record
   (still a map tagged `:cohost/kind ::atom`) implementing `IDeref`. Other seats keep the plain map.

## The gate

### 1. The probe's cljs row, flipped
`bb dev/eve_probe.clj jvm cljs` (logs/probe-row-jvm-cljs.log), with dev/eve_probe_program.cljc unchanged:

```
SUMMARY|jvm|ok=23 unsupported-shim=0 fail-other=0 wall-ms=1137
SUMMARY|cljs|ok=23 unsupported-shim=0 fail-other=0 wall-ms=24011
```
Before this unit (CLJS-DEP FINAL): `ok=22 fail-other=1`, and the futures ran as cooperative tasks in one
realm. Now every load line is OK on both hosts, and the 10 threading-surface cases print:

| case | jvm | cljs | where it ran on cljs |
|---|---|---|---|
| future-basic | 3 | 3 | worker realm 1 |
| future-capture-local | 42 | 42 | worker realm 1 (local `x` crossed) |
| future-capture-atom | 1 | 1 | worker realm 1 (the atom crossed by Eve handle; the worker's swap is seen by the core's deref) |
| future-binding | 3 | 3 | worker realm 1 (`*print-length*` frame crossed through the inbox atom) |
| promise | 7 | 7 | core realm (no thread) |
| delay | 2 | 2 | core realm |
| agent | 1 | 1 | core realm (see remainders) |
| pmap | (2 3 4) | (2 3 4) | worker realm 1, three tasks (`inc` crossed by name) |
| spec-conform | 2 | **2** (was FAIL) | core realm (fix 1 above) |
| test-stacktrace | **26** | **0** | — |

The realm trace in the same run shows 7 tasks handed over, 7 completed in realm 1, and 0 closures.

**22 of 23 cases answer the JVM's values. test-stacktrace does not.** It prints
`(count (.getStackTrace (Thread/currentThread)))`, the number of the running JVM's stack frames (26
under `bin/cohost :jvm`). The cljs seat's shim answers an empty trace, and so does the jank seat's.
No honest value on a JavaScript host equals a JVM frame count; returning 26 would be a stand-in. I
left it as is, and it is the one gate item that is not met. **Operator's call:** rule the case out of
the 23 as a host-measurement, or name what the cljs seat should answer.

### 2. The worker-crossing case (the case the original probe could not pose)
`dev/u8/realm_crossing_program.cljc`, run by `bb dev/u8/gate.clj` on `:jvm` and `:cljs`
(logs/gate-run3.log), compared as values (an Eve map prints in hash order):

```
ROW|crossing|made-elsewhere|SAME        [{:made :elsewhere, :v [0 1 2]} {:made :elsewhere, :v [0 1 2 3]}]
ROW|crossing|atom-born-elsewhere|SAME   {:born :elsewhere, :touched :here}
ROW|crossing|contended-swaps|SAME       [:done 1000]
ROW|crossing|frame-and-local|SAME       [42 2]
SUMMARY|crossing|same=5/5
TRACE|crossing|handed-over=5 completed-in-worker=5 closures=0
```
- **made-elsewhere**: the worker creates the map in its own heap and publishes it into an atom the core
  created. The core reads it back out of the shared slabs, and a second future reads it in the worker
  and extends it.
- **atom-born-elsewhere**: the atom is created in the worker realm's heap, in a domain the worker minted
  and announced. It is returned to the core by Eve handle, then swapped there.
- **contended-swaps**: core and worker each swap one atom 500 times through Eve's CAS. The final value
  is exact.
- **frame-and-local**: a captured local and a binding frame, both through the inbox atom.

(The de-risk probe before any cohost wiring, `dev/u8/u8_worker_probe.cljs`, logs/u8-worker-probe.log:
a worker started through eve.thread-host joins the main realm's domain from the SABs, and both swap one
atom 2000 times. Final `{:n 4000 ...}` exact.)

### 3. Batteries
| run | result | standing |
|---|---|---|
| `bb boot:cljs` (the fill on, realms on; logs/boot-cljs.log) | booted 554 / host 87 / natives 41 / skipped 140 / errors 0; **battery 219/221** (FAIL numeric tower, FAIL defmacro &form &env: the pristine pair); **libraries 62/62** | 219/221, 62/62 |
| `bb boot:jvm` (logs/boot-jvm.log) | booted 465 / host 202 / natives 66 / skipped 58 / errors 0; **battery 221/221; libraries 62/62** | 221/221, 62/62 |
| `bb cli:check cljs` (logs/cli-check-cljs.log) | **7/7 SAME** | 7/7 SAME |
| `bb boot:jank` (AOT; logs/boot-jank.log) | see the addendum below | 219/221 + 62/62 on a fresh box (unit 7) |

## Named Eve-side findings (consumed as-is, not filled from cohost)
1. **Integer map keys corrupt (cljs, in-process domain).** `(swap! a assoc 1 {:z [1 2]})` reads back as
   `{2 {:z [1 2]}}`. A small integer key whose value holds a nested collection comes back incremented.
   Scalar values under integer keys, and keyword or string keys, are fine. Measured on the core realm
   with plain atoms, with no realms involved. Cohost's transport keys its outbox by string. **User
   programs whose atoms hold integer-keyed maps of collections will see this.** It should go to the Eve
   line.
2. **No public worker-side opener for an in-process domain.** `cljs_eve/join-domain` rebuilds the domain
   state with Eve's own private `mmap-claim-slot!` and `make-domain-state`, the steps
   `open-heap-domain!` takes.
3. **`atom-domain` cannot run on a worker.** Its `init-domain-views!` asks each slab instance for its wasm
   memory, and instances registered from SABs by `init-worker-slabs!` have none. A worker realm opens
   its domains with `open-heap-domain!` alone (private, same note as 2).
4. **Class-6 overflow on a worker.** `init-worker-slabs!` registers the overflow SAB's views but does not
   wire the coalescing allocator, so a block over 1 KB allocated by a worker realm is unverified. No gate
   value needed one.
5. **A list published into an atom read back nil in one transport measurement.** It was the defect in 1
   (the outcome was filed under a bumped integer key), not lists. The codec still tags seqs; harmless.

## Remainders (named)
- **test-stacktrace** (above): the one probe case not JVM-equal; needs the operator's ruling.
- **Agents stay in the core realm.** An agent's drain runs on the cooperative leaf. Shipping each action
  to a realm would park a cooperative task on a wait, which re-runs its early actions on re-entry (the
  leaf's named distance). Values are the JVM's. Agent actions over Eve state in worker realms would need
  a drain written as a realm task: future work.
- **Worker boot wall**: paid once, at the first crossing future. Through `bin/cohost :cljs` the whole
  probe row takes about 24 s, against 49 s for the cooperative row banked before (logs differ in box and
  build; not a benchmark).
- **Realm-local wrapper state**: watches, validators and meta belong to each realm's wrapper (unit 7's
  process-local rule at realm scale). Watches fire for the publishing realm's swaps.
- **Frame entries whose values cannot cross** are not conveyed. They are counted and traced (0 in the
  gate runs).
- **Namespaces loaded in the core after the worker booted** are required by the worker from the same
  source roots on first use; one that cannot load there becomes a bare namespace seeing core.
- **The browser seat**: unchanged (no Eve in the browser build). Its main thread cannot `Atomics.wait`,
  and eve.thread-host's browser adapter is a different row.
- **Kinds**: `:disk` and `:net` are not hosted on cljs. They throw loudly (Eve's Node disk domains need
  its N-API addon, not built here). Unit 7 hosts them on the JVM and jank seats.
- **COHOST_CLJS_REALMS** (worker count, default 1): only the default was exercised by the gate.

## Commits (in order, on the branch)
4d8771f merge; probe; implementation; worker domain opener; outbox keys + codec; runtime loader;
LayerAtom record; plus STATUS commits. See `git log agent1/eve-unit7-tiered-atoms..` for the full list.

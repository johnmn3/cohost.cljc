# Perf pass: state and handoff

Written 2026-10-30, 2:05 PM ET, on branch `claude/eager-euler-ci8ev7`.
This file says where the perf work stands, what each change did when measured, and
what is left. A new session should read it first, then the files it names.

## The request

> "Let's do a perf upgrade pass. First, try to find all the places where we're being
> dumb and fix it. Improve all existing perf. Second, we'll create a higher level adapter
> layer that cohosts can opt into gradually. But providing higher level impls of higher
> level fns that are faster, that shortcut the cljc indirection, we can let those cohosts
> provide that. If the cohost provides no impl, it falls back to your cljc/clj impl."

- **Pass 1:** remove the needless costs. Mostly done; the remaining items are listed below.
- **Pass 2:** the opt-in "natives" layer. **Landed** (2:15 PM ET).
  - Adapter `:natives` maps a qualified name to a fn, or to `:host` for the host core's
    var of that name.
  - Boot (`boot.cljc` `native-of`, `intern-native!`) and library loads intern the native
    instead of evaluating the stock def. A name without an entry boots the stock
    definition.
  - The boot and library reports count natives.
  - Opted in on jolt and the JVM (`hosts/java.clj` `natives`) and on ClojureScript:
    frequencies, group-by, partition, re-seq, sort, sort-by, `clojure.string/join`.
  - basilisp opts into none, so it shows the fallback.
  - Batteries green (2:13 PM ET): jolt and JVM 133/133 and 61/61, with 6 core natives
    plus join. ClojureScript is 131/133, the two host limits.
  - Not yet benched or corpus-checked.
- **Then:** measure before against after on every host, write `doc/PERF.md`, and run an
  adversarial review.

## Resume

```bash
cd cohost.cljc && git checkout claude/eager-euler-ci8ev7
```

```bash
bb boot:jolt
```

Also run `bb boot:jvm` and `bb boot:cljs`. Each boots core.clj and runs the battery: 133
behaviors plus 61 library cases. basilisp's `bb boot:lpy` takes about 5 minutes; it has
not been run on this pass's changes.

```bash
jolt -Scp src -m cohost.run --bench --quick
```

Quick bench on one host, a few minutes. A full measurement uses `bb bench:jolt`,
`bench:jvm`, `bench:cljs`, `bench:browser`, and `bb bench:lpy --scale=0.05` with
`bb bench:jvm --scale=0.05`. Each writes `target/bench-<host>[-s<scale>].edn`.

```bash
bb bench:report target
```

`bb bench:report --compare doc/perf/before-4ce56eb target` prints the before/after table.

- **"Before":** commit `4ce56eb`. Its results are in `doc/perf/before-4ce56eb/`. They are
  scale 1 for clj, jolt, cljs and chromium. They were measured while other agents shared
  the 4-CPU box, so they are noisy. For clean before-numbers, re-run the benches at
  `4ce56eb` in a worktree, back to back with the after-run.
- **Batteries on this tree** (2:03 PM ET): jolt 133/133 and 61/61; JVM 133/133 and 61/61.
  ClojureScript is 131/133 and 61/61. Its two failures are the same as at `4ce56eb`:
  `(= 1 1.0)` is true because JS has one number type, and a macro's `&env` is the
  analyzer env. Both are known host limits, not regressions.
- **jolt corpus** (`bb corpus:jolt <jolt's test/chez/corpus.edn>`): not run on this pass.
  Run it before calling pass 1 done.

## After `19dbd1a`: the review fixes (validated)

These commits fix what the correctness review of the perf diff found
(`doc/perf/findings/review-*.json`).

- **Inlining runs after macroexpansion, as on the JVM.**
  - In the arguments of a macro whose expansion cohost rewrites (`rewritten-macro?`:
    `:cohost/wrapped`, not `:cohost/host`), the codemod neither inlines nor takes the
    `instance?` shortcut (`*inline?*`).
  - The expansion's rewrite inlines, seeded with the locals of the macro's `&env`
    (`:env-locals`). `wrap-macro` takes `&env` as whichever implicit argument is a map:
    basilisp passes it first.
  - This fixes `(-> 10 (- 1 2))`, a local or parameter named like a core fn, and the
    forms `assert` and `is` report.
- **Head resolution** happens once per rewrite (`head-var`, `*heads*`). The `instance?`
  shortcut applies only to the stock `instance?`.
- **Other fixes:**
  - **ClojureScript:** `:chunks` with an index-walking `:chunk-reduce`.
    `(apply + (range 10))` threw.
  - **jolt:** `:host-data?` without `vector?` and `fn?`.
  - **The MultiFn** caches no misses.
  - **Deftype and defrecord** fields and method parameters are locals.
  - **The JVM:** aliases skip host fns that have `:inline`.
  - **`may-make-macro?`** reads `defn` attr-maps.
  - **`(catch C e)`** uses the imported class.
  - **`take-from-host!`** marks a macro before writing its meta.
- **jolt:** `:lazy-seq` uses `make-lazy-seq`. **basilisp:** `:rewrite-inline? true`.
- **Battery:** five new rows ("perf passes"). jolt and the JVM pass 138/138 and 61/61;
  ClojureScript 136/138 and 61/61, its two host facts as before. basilisp and the jolt
  corpus: see the commit that records them.
- **Tried and backed out: walking each form once.** That meant leaving a rewritten macro's
  arguments raw and rewriting them only in the expansion. It loses the type hints of
  locals inside expansions (the checkcast the battery's "hinted argument" row tests), and
  jolt's `&env` does not carry hints (its values are nil). Doing it needs another way to
  carry hints, for example in a cohost meta key that the host keeps on its locals. The
  double walk is the largest boot cost left.
- **Not benchmarked yet:** these commits. `doc/perf/after` is `19dbd1a`.

## Measured so far: jolt, quick runs, stock core median ms

The settled before/after numbers, on every host, are in `doc/PERF.md`. This table is the
quick runs this pass used along the way.

The same box, `--quick`, so ±15%. The "own" column is jolt's own core on the same run.

| case | before (`4ce56eb`) | now | × jolt's own, before → now |
|---|---:|---:|---:|
| fn-call | 10.9 | 1.34 | ×6.4 → ×0.83 |
| loop-arith | 28.5 | 0.51 | ×55 → ×1.0 |
| reduce-range | 9.8 | 4.95 | ×2.1 → ×1.1 |
| lazy-pipeline | 1203 | 75 | ×196 → ×12 |
| transduce | 379 | 31 | ×50 → ×3.5 |
| into-vector | 613 | 33 | ×56 → ×3.2 |
| transient-vector | 611 | 24 | ×141 → ×1.2–6 |
| map-lookup | 41 | 21 | ×3.9 → ×1.3 |
| keyword-lookup | 377 | 9.6 | ×74 → ×1.7 |
| destructure | 620 | 30 | ×87 → ×4.2 |
| frequencies | 1366 | 111 | ×56 → ×3.5 |
| group-by | 918 | 82 | ×44 → ×3.8 |
| sort | 278 | 112 | ×9.7 → ×3.7 |
| sort-by | 4581 | 89 | ×168 → ×3.4 |
| set-build | 662 | 51 | ×35 → ×4.1 |
| distinct | 700 | 102 | ×14 → ×1.7 |
| partition | 228 | 72 | ×24 → ×7.4 |
| atom-swap | 197 | 13 | ×41 → ×2.8 |
| multimethod | 83 | 19 | ×8.1 → ×1.9 |
| str-build | 80 | 11 | ×21 → ×3.1 |
| string-join | 1154 | 96 | ×346 → ×25 |
| regex | 59 | 40 | ×30 → ×18 |
| format | 25 | 6.8 | ×4.3 → ×0.85 |

The JVM, ClojureScript and Chromium have not been re-benched since `4ce56eb`; their
batteries pass. basilisp has been neither re-benched nor re-run.

## What pass 1 changed (all in this commit)

- **The codemod** (`rewrite.cljc`):
  - `(.m x ...)` calls a prebuilt dispatcher, `cohost.shim/dot$m`, instead of building a
    closure on every call.
  - `(instance? Literal x)` goes straight to `shim/instance-of?`, without the `dot`
    round trip.
  - A checkcast of a hinted local calls a prebuilt `cast$<token>`.
  - Statics that are the host's own fn (`RT-first` is `first`) are emitted as the host
    symbol, which the host's compiler can inline. Where several host names hold the same
    fn, the shortest wins: on jolt, `+` and `+'` are one fn, but only `+` is inlined.
  - On jolt and ClojureScript, core.clj's `:inline` expansions are applied at rewrite
    time, as the JVM compiler does. ClojureScript keeps var meta unevaluated, so boot
    captures the `:inline` fns.
  - A member symbol's namespace is dropped, as the JVM does: a syntax-quoted inline
    spells `(. RT (cohost.core/aget ...))`.
  - Passthrough interop qualifies imported class names. This fixes
    `clojure.repl/source-fn` on the JVM ("Unable to resolve classname: LineNumberReader"),
    a bug that predates this pass.
- **The shim** (`shim.cljc`):
  - Hot predicates and string/regex capabilities are bound once (`h-*`), not looked up per
    call.
  - `canon` is memoized, and `instance-of?` tries `interface-preds` before `canon`.
  - `host-object?` gets a `:host-data?` fast path and a per-type cache.
  - The MultiFn has a method cache keyed on the method, prefer and hierarchy maps, like
    the JVM's `methodCache`.
  - `compare-with` replaces the old comparator wrapper.
  - Stock chunked branches run over the host's own chunks (adapter `:chunks`); the
    stand-ins remain for basilisp.
  - Fixed `Numbers-unchecked_minus`, which had no 1-arity on the JVM.
- **Adapters:**
  - `cap` does one lookup.
  - New capabilities: `capability`, `interned-var` (one var, not the namespace's whole
    map), `rewrite-inline?`, `host-data?`, `chunks`.
  - JVM: `resolve-core` and `interned-var` are direct; type hints remove reflection from
    `StringBuilder.append`, the Matcher calls and the exception constructors.
  - basilisp: `resolve-core` is one lookup.
  - ClojureScript: `var-of` and `interned-var` are O(1).
  - jolt: `host-data?` covers its own data, including vectors (chunks).
- **Boot** (`boot.cljc`, `policy.cljc`):
  - The macro scan runs only after a form that can make a macro (it ran after all 721
    forms), plus one scan at the end of each file. A library load works the same way.
  - `strip-string-tags` returns at once past the last `^"`.
  - Companion files are read once.
  - The list of the host's dynamic vars is built during boot, not on every `refer-core!`.
  - The policy lists are computed once per boot.
  - `ns-arg-wrap` has fixed arities.
  - When the host has chunks, cohost.core takes the eight chunk fns from it.

## The planned items: all implemented (the commit after `5506876`)

Each item below was on this list and is now in the code, tested on all four hosts and the
jolt corpus in one round.

1. **regex:** `:re-find-at` and `:re-match-full` (JVM and jolt) return a match's groups and
   spans as delays, forced only when `.group` or `.start(g)` asks. The Matcher asks the
   group count once.
2. **One walk per form:**
   - A macro whose expansion cohost rewrites keeps its arguments as written. They are
     rewritten once, in the expansion, as the JVM compiler sees them after macroexpansion.
     The expansion's rewrite reads the macro's `&env` for locals.
   - A local hinted with a checked class or StringBuilder reaches that rewrite through a
     *marker* local (`cohost__hint_<class>_<depth>__<name>`), since jolt's `&env` carries
     names only. A rebinding without a hint leaves a deeper, untyped marker, and the
     deepest one wins.
   - `:arglists` lose their type hints, as the params do: the JVM read `^long` there as a
     primitive interface.
   - A def name's code-valued meta (`:test`, `:inline`) is rewritten.
3. **Rewrite memos:** `simple-class-name`, `class-sym?`'s prefix test, `*kw-unrenames*`
   (by the renames map's identity), head resolution per rewrite, and `instance?`'s class
   rewritten once.
4. **Prebuilt `is$<token>` predicates** (`shim/install-is!`), built on the first use of a
   class.
5. **A method the shim lacks** gets its dispatcher built once (`shim/install-missing-dot!`).
6. **`reduce` with core's `+` or `*`** hands the host its own (`shim/host-reducers`).
7. **Natives:**
   - **JVM:** the shared set plus `into`, `reverse`, `replace` and five `clojure.string`
     fns, 66 in core.
   - **jolt:** the shared set (`hosts/java.clj` `natives`): the lazy seq fns, reduce,
     collections, atoms, comp and partial, and `clojure.string` predicates and case, 63
     in core.
   - **ClojureScript:** 46.
   - **basilisp:** 36.
   - **Left to the stock definition:** `clojure.string/split` on jolt and ClojureScript,
     and the string fns on ClojureScript, because the JVM's checkcast of a
     `^CharSequence` is not theirs.
   - A native keeps the stock docstring and arglists (`def-form-meta`). On ClojureScript
     they also go into the analyzer, which `(var x)` reads.
8. **Bench provenance:** every result records `:git` (HEAD, `-dirty`), and jolt's records
   `JOLT_TRACE`. jolt is benched both ways.
9. **StringBuilder:** a local known to hold one, by hint or by its init, and what `.append`
   on one returns, call the shim's method with no user-type dispatch.

**Also fixed in this round:**
- **Correctness review, adapters area:**
  - the chunk-reduce contract: `(.reduce (chunk-first s) ...)` goes to
    `shim/chunk-reduce`, which returns a `reduced` unopened; jolt's chunks are vectors,
    whose own reduce opened it;
  - jolt's lazy-seq wrapper is no longer `^:once`, so a rerun after a failure works.
- **basilisp:** wrapped macros get `&form` and `&env` in core.clj's order (basilisp passes
  `&env` first). Its `&env` battery row now passes.

**Still open:**
- **The perf finders that hit the usage limit** (adapters, shim-core, shim-rest) were not
  re-run: they are discovery, not planned work.
- **The correctness review's later rounds** (its boot/policy finder and its "find more"
  pass) were still running when this was written.

## Pass 2: the natives layer (landed as designed below; more opt-ins to come)

- **Capability:** an adapter capability `:natives`, a map of `{qualified-sym impl}`:
  - `'clojure.core/frequencies host-fn`: use this fn;
  - `'clojure.core/frequencies :host`: take the host core's own;
  - a host symbol: take that var of the host.
- **Boot:** `policy/decide` (or `boot/process-form!`) checks `(get natives name)` before
  evaluating the stock form. A hit interns the native the way `take-from-host!` does
  (`:cohost/native true` meta). A miss boots core.clj's definition, as now. Libraries
  (`load-source!`) do the same per library namespace, keyed `'clojure.string/join`.
- **Report:** the boot report counts `:native` forms, and the bench report lists them per
  host.
- **Opting in:** a host opts in one fn at a time. A native must pass the battery and the
  jolt corpus, and print JVM Clojure's answer in the bench (the report already checks
  stock answers against the JVM).
- **First candidates,** from the remaining ratios: the lazy seq fns (map, filter,
  partition, take), frequencies, group-by, into, `clojure.string/join`, and re-seq. Each
  host's own implementation is ×3–25 faster on jolt today.
- **Chunks first:** the `:chunks` capability (pass 1) is the first such opt-in. Taking
  `policy/chunk-names` from the host when the adapter has chunks is the pattern to
  generalize.

## Files

- `doc/perf/findings/`:
  - `trace-lazy-seqs.json`: per-element cost model; Fixes A–D.
  - `trace-transients-dispatch.json`
  - `find-boot-loader.json`: 13 findings, most landed.
  - `find-codemod-output.json`: 12 findings; items 4–8 above.
- `doc/perf/before-4ce56eb/`: the before-numbers.
- `dev/perf-workflows/`: the finder and trace workflow scripts.

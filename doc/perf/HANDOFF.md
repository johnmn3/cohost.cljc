# Perf pass: state and handoff

Written 2026-10-30, 2:05 PM ET, on branch `claude/eager-euler-ci8ev7`; brought up to date
4:30 PM ET. This file says where the perf work stands, what each change did, and what is
left. A new session should read it first, then the files it names.

**State:** every planned item is implemented, tested on all four hosts and the jolt corpus,
and on `main` at `3e59a25`. That commit is measured on every host in `doc/PERF.md`; its
results are in `doc/perf/after/`.

## The request

> "Let's do a perf upgrade pass. First, try to find all the places where we're being
> dumb and fix it. Improve all existing perf. Second, we'll create a higher level adapter
> layer that cohosts can opt into gradually. But providing higher level impls of higher
> level fns that are faster, that shortcut the cljc indirection, we can let those cohosts
> provide that. If the cohost provides no impl, it falls back to your cljc/clj impl."

- **Pass 1:** remove the needless costs. Done, with the planned items below.
- **Pass 2:** the opt-in "natives" layer. **Landed** (2:15 PM ET); natives on every host
  since `3e59a25`.
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
  - (Those counts are from 2:13 PM ET; today's are under "The planned items" below.)
- **Then:** measured before against after on every host (`doc/PERF.md`), with an
  adversarial review whose fixes are below.

## Resume

```bash
cd cohost.cljc && git checkout claude/eager-euler-ci8ev7
```

```bash
bb boot:jolt
```

Also run `bb boot:jvm`, `bb boot:cljs` and `bb boot:lpy`. Each boots core.clj and runs the
battery: 139 behaviors plus 61 library cases. basilisp's is the slowest; its boot alone is
66 s.

```bash
jolt -Scp src -m cohost.run --bench --quick
```

Quick bench on one host, a few minutes. A full measurement uses `bb bench:jolt`,
`bench:jvm`, `bench:cljs`, `bench:browser`, and `bb bench:lpy --scale=0.05` with
`bb bench:jvm --scale=0.05`. Each writes `target/bench-<host>[-s<scale>].edn`.

```bash
bb bench:report target
```

`bb bench:report --compare doc/perf/before target` prints the before/after table.

- **"Before":** commit `4ce56eb`, in `doc/perf/before/`. `doc/PERF.md` says how it was run.
- **"After":** commit `3e59a25`, in `doc/perf/after/`. jolt with `JOLT_TRACE=0` is in
  `doc/perf/after-jolt-trace0/`.
- **Batteries on `3e59a25`:**
  - jolt 139/139 and 61/61; JVM 139/139 and 61/61.
  - ClojureScript 137/139 and 61/61. Its two failures are known host limits:
    `(= 1 1.0)` is true because JS has one number type, and a macro's `&env` is the
    analyzer env.
  - basilisp 138/139 and 61/61. Its one failure is `(= 1 1.0)`.
- **jolt corpus** (`bb corpus:jolt <jolt's test/chez/corpus.edn>`): 4,297 of 4,942 rows on
  `3e59a25`; the baseline passed 4,186 of 4,833. Of the rows in both runs, none regressed
  and 25 more pass.

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
- **Walking each form once** was backed out here: it lost the type hints of locals inside
  expansions, and jolt's `&env` does not carry hints. `3e59a25` does it with marker
  locals instead (item 2 under "The planned items").

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

## The planned items: all implemented (`3e59a25`)

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
8. **Bench provenance:** every result records `:git` (HEAD, `-dirty`), except Chromium's,
   whose page takes no flags. jolt's records `JOLT_TRACE`; it is benched both ways, with
   no measurable difference.
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
- **The largest gaps left,** measured on `3e59a25` (stock core against the host's own):
  - transient vectors on ClojureScript, ×16.9 on Node;
  - multimethod calls, ×3.7 to ×5.0 on every host;
  - `transduce`, ×3.0 to ×4.0 on jolt and ClojureScript;
  - jolt's lazy `count`/`filter`/`map`, ×3.9;
  - basilisp's `re-seq`, `format` and `sort-by`, which are not natives there.
  `doc/PERF.md` has them all.
- **The perf finders that hit the usage limit** (adapters, shim-core, shim-rest) were not
  re-run: they are discovery, not planned work.
- **The correctness review's later rounds** (its boot/policy finder and its "find more"
  pass) never reported.

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
- **Chunks first:** the `:chunks` capability (pass 1) is the first such opt-in. Taking
  `policy/chunk-names` from the host when the adapter has chunks is the pattern to
  generalize.

## Files

- `doc/perf/findings/`:
  - `trace-lazy-seqs.json`: per-element cost model; Fixes A–D.
  - `trace-transients-dispatch.json`
  - `find-boot-loader.json`: 13 findings, most landed.
  - `find-codemod-output.json`: 12 findings; items 4–8 above.
- `doc/perf/before/`: the before-numbers (`4ce56eb`).
- `doc/perf/after/`: the after-numbers (`3e59a25`); `doc/perf/after-jolt-trace0/`: jolt with
  `JOLT_TRACE=0`.
- `dev/perf-workflows/`: the finder and trace workflow scripts.

# jank: the course to fast (plan, 2026-11-01 ET)

Branch `claude/youthful-euler-gquikf` at `98e5eb2`. jank 0.1 (apt `0.1-noble`). Read first:
doc/HANDOFF.md (jank section), doc/hosts/jank-J0.md, doc/PERF.md, doc/perf/HANDOFF.md.

## The scoreboard ("fast" means these numbers)

1. **Startup**: `boot-ms` + `libs-ms` in `target/bench-jank.edn`. Measured today (quick run on
   `98e5eb2`): boot **44.2 s**, libraries **36.2 s**. For scale: JVM Clojure 1.3 s, jolt 2.5 s
   boot (doc/PERF.md). Goal: jolt's range.
2. **Runtime, stock core against jank's own core**: the `xN` column of `bb bench:jank`
   (`bb bench:report`). Goal: every case within x1.5, as the JVM (x1.1) and jolt (x1.4) are.
3. **Correctness never moves**: `bb boot:jank` battery and library battery stay at or above
   their numbers before each step, and every other host's batteries + the jolt corpus are
   unchanged after any shared-layer change (the regression in doc/hosts/PLAN.md's rules).

Today's quick run (one warm call + one timed call per side: noisy, shape only):

| case | host ms | stock ms | xN |
|---|---:|---:|---:|
| transient-vector | 4.76 | 55.09 | x11.55 |
| into-vector | 6.27 | 61.94 | x9.87 |
| set-build | 13.59 | 117.29 | x8.62 |
| group-by | 25.88 | 214.34 | x8.28 |
| atom-swap | 9.64 | 66.03 | x6.84 |
| frequencies | 43.4 | 279.8 | x6.44 |
| destructure | 19.44 | 118.66 | x6.1 |
| format | 5.3 | 208.29 | x39.25 (jank's own format is C-style: not comparable) |
| assoc-map, map-lookup, partition, transduce, lazy-pipeline, multimethod | | | x1.9-x2.6 |
| fn-call, loop-arith, equality, pr-str, conj-vector, distinct | | | x0.99-x1.3 |
| sort, sort-by, regex | | — | stock side errors: no `:sort-array!` / `:re-find-at` (step 1) |
| string-join | 1170.85 | 30.79 | jank's own `clojure.string/join` gives a wrong answer (108,889 vs 58): a host fact |

## Rules for every step

- One step, one commit, pushed. Each step's commit message states the scoreboard numbers
  before and after, measured by the commands named here (no estimates).
- Measure with the full bench (`bb bench:jank`, not `--quick`) before and after a perf step;
  run it alone on the box (other runs skew it).
- A shared-layer change (`src/cohost/*.cljc`) runs the all-host regression before its commit.
  Prefer a `:jank` branch or a capability with a default that leaves other hosts byte-identical.
- A perf change never trades correctness: no battery row may go red. Divergent host behavior is
  a host fact in doc/REPORT.md, never a weakened row.

## Step 1. Correctness to the done-bar (prerequisite: a bench of failing code measures nothing)

The open list (diagnosed by the previous lane, doc/HANDOFF.md): `:re-find-at`/`:re-match-full`
(std::regex with group spans, probe-verified), `:sort-array!` (in-place sort, probe-verified),
`:edn-read-string`, `Math` statics through the adapter's `:math` map, refs
(ref-set/alter/commute over the shim's transactions), exception printing (`:exception-string`),
clojure.data's EqualityPartition (protocol supers walk), StringBuilder `str`, `with-local-vars`
(the var's dynamic flag), the small `:new-instance` default bug (adapter.cljc returns the
throwing fn instead of throwing), and the `:type-method-style` doc (name `:cohost-types`).
Document the two Character rows as host facts (jank 0.1 characters are ASCII-only).
**Done-bar**: battery 139 minus documented host facts; libraries 61/61; bench runs every case
on both sides.

## Step 2. Measure, then profile (no code changes)

1. Full `bb bench:jank`; record `target/bench-jank.edn` and the table in doc/PERF.md as the
   baseline column.
2. Profile the boot and the five worst cases with jank's own profiler (`jank --profile
   --profile-output <file> ...`) and with `--perf` (Linux perf events; install `perf` only with
   the user's approval). Write the top costs, with their share, to doc/perf/jank.md.
3. Split the boot's 44 s: reading, the codemod, jank's eval/JIT per form, `scan-macros!`, the
   companion files (time each with the adapter's `:nano-time` around boot phases).

Every later step names which of these measured costs it attacks.

## Step 3. Cheap, broad wins in the adapter and shim

Each item is one commit with its before/after bench cases.
- **Type tests by tag, not by name string.** The adapter's predicates call `(type x)` (a string)
  and compare strings (`:transient?` does `starts-with?` on every call). Add one `cpp/raw`
  helper returning `x.get_type()` as an integer, and test against constants of
  `jank::runtime::object_type` (read them from the compiler, as `object_type::max` was).
  Attacks: transient-vector, into-vector, set-build, frequencies, group-by.
- **Cohost-instance guards as one C++ call.** `cohost-inst?` and `jank-impl?` go through
  `host/instance-desc` (a capability map lookup, then `cpp/cohost_obj.is_cohost`) on every
  `RT-count`/`RT-conj`/`RT-nth`/assoc and every interface test. On jank, bind the C++ tag test
  once to a shim var (as `h-host-data?` caches its capability) and check it first.
- **Capability lookups out of hot paths.** `(defn x [..] ((cap :k) ..))` does a map lookup per
  call; the hot ones (count, nth, conj, get, deref, the type predicates) get their capability
  fn read once into a var at install, on jank (other hosts keep theirs unless the bench says so).
- **Syntax-quote metadata.** jank's reader expands syntax-quote with a `:jank/source` map on
  every quoted symbol, built at run time on every macro expansion. A jank-only codemod pass drops
  the `(clojure.core/with-meta x (clojure.core/hash-map :jank/source ...))` wrappers from
  expansions cohost evaluates (keep `&form` metadata the stock macros copy). Attacks: boot
  time, destructure, every macro-heavy path.

## Step 4. Natives for jank (M6)

`:natives` opts in jank's own implementation of a stock fn, one name at a time (README, "the
natives layer"; the other hosts carry 36-66). Start from the cases step 2 ranks worst:
`into`, `transient`/`persistent!`/`conj!`/`assoc!`, `frequencies`, `group-by`, `set`,
`assoc`/`get`, `swap!`/`reset!`, `nth`, `count`, `str`. A name lands only when both batteries
stay green with it; one that diverges is a host fact, not a native (never `clojure.string/join`,
whose jank answer is wrong; never `format`, which is C-style). Natives must not see cohost type
instances (jank's own fns panic on them): a native that can receive one keeps the shim's guard.
**Done-bar**: no bench case above x1.5 that a native can reach.

## Step 5. Type hints through to jank

jank's compiler uses a parameter's `:tag`: it evaluates the tag and resolves it as a C++ type,
which must be a jank object type, a trait-convertible type, or a raw pointer
(compiler+runtime/src/cpp/jank/analyze/processor.cpp, `analyze_fn_arity`, the `tag_kw` block).
Today cohost strips every hint on jank (`strip-meta`), because JVM class hints are no C++ types.

1. **H0, probe before building.** In raw jank (no cohost), time a hot loop with and without
   native parameter hints: `(defn f [^double a ^double b] (+ a b))` against the untyped `f`,
   the same with `long`/`jank::i64`, and a `loop` whose locals are hinted. Find the exact hint
   spellings jank accepts for C++ `long`, `double`, `bool`, `int` (bare symbol, `cpp/long`, a
   string), whether return-type and `let`/`loop` local hints are used at all, and whether core
   `+`/`<`/`inc` on native-typed values compile to unboxed C++ or box back. Write the measured
   ratios to doc/perf/jank.md. **If no case gains, stop here** and record why.
2. **H1, the mapping.** A jank-only codemod table: `long` -> jank's 64-bit integer type, `double`
   -> `double`, `boolean` -> `bool`, `int` -> C++ `int` (whatever H0 found the spellings to be);
   every other hint stays stripped (class hints, `^String`, arrays). Applied to `fn*` parameter
   vectors (and to return/local hints only if H0 measured them used).
3. **Semantics stay the JVM's.** A primitive-hinted parameter on the JVM coerces its argument
   (`^long` takes a Double by truncation, rejects a non-number with ClassCastException); jank's
   C++ conversion may differ (truncate, round, or throw). Add battery rows for prim-hinted fns
   called with mismatched arguments, expected values from JVM Clojure 1.12.0, before switching
   the mapping on; the existing row "a hinted argument is checked" must stay green. Where jank's
   conversion differs, the codemod keeps the shim's check in front of the native parameter.
4. **H2 (only if H1 pays):** the stock core's own hints (`^long`/`^double` in core.clj's
   numeric fns, `^ints` etc. stay stripped), measured on loop-arith and reduce-range.
**Done-bar**: the H0 ratios recorded; with H1, loop-arith and reduce-range faster than without,
both batteries unchanged.

## Step 6. Startup: compile ahead of time

jank compiles modules ahead of time (`jank compile-module <ns>`, `-O 0-3`, `--runtime static`,
`--output-target object`). Two stages:
1. **cohost's own namespaces AOT.** cohost.adapter, scan, shim, rewrite, boot, policy, battery,
   the jank adapter: static sources, JIT-compiled at every start today. Probe `jank
   compile-module cohost.run` (with `-O3`) and run `bb boot:jank` against the compiled modules;
   measure the load time before/after (a `:nano-time` stamp before `boot!`).
2. **A snapshot of the booted core.** The boot's output is code: the rewritten forms it
   evaluates, in order, plus the var surgery it does through the adapter (intern!, meta, macro
   marking, refers). Add a record mode (a capability-level hook around `:eval` and those calls;
   jank-only to start) that writes one generated module per booted namespace (`cohost.core`,
   `cohost.walk`, ...) under `target/jank-snapshot/`, keyed by a hash of every input (stock
   sources, cohost sources, jank version); `jank compile-module` them with `-O3`; a later boot
   with a fresh snapshot requires the compiled modules instead of booting. Probe first: compile
   time and load time of one large generated module; that jank's AOT keeps vars, metadata,
   macro flags and the `cpp/raw` type layer working when loaded.
**Done-bar**: `boot-ms` + `libs-ms` within jolt's range with a fresh snapshot; both batteries
unchanged when booted from it; a stale snapshot is detected and rebuilt, never loaded.

## Step 7. Upstream (in parallel, no code here)

Report to jank-lang/jank with minimal repros (each verified on 0.1-noble): a macro's `&form` and
`&env` (`((args))` and the macro name); the lexer rejecting any non-ASCII character, even in a
comment; `^Class` read as `:tag (quote Class)`; `class`/`instance?`/`indexed?`/`rseq` TODO stubs;
`(catch :default e)` binding nil; `push-thread-bindings` refusing an array map;
`clojure.string/join`'s wrong answer; count/conj/assoc/meta panicking on an object type they do
not know (each fix upstream removes a shim guard here).

## Order and what each step is checked by

Step 1 -> 2 -> 3 -> 4 -> 5 (H0 decides whether H1/H2 run) -> 6. Step 7 any time. The reviewer
(this repo's other session) re-runs each step's named commands and compares with its commit
message: the boot report, both batteries, the bench table, and the regression for shared
changes.

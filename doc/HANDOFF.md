# Handoff — 2026-10-31 ET, branch `claude/eager-euler-ci8ev7`

Read first: doc/hosts/PLAN.md (the plan and its rules), README status table, doc/REPORT.md.
Hosts landed (M0–M5): ClojureWasm, Phel, let-go. In progress: **clojurust**. Then **jank**,
then M6 (natives) per host.

## The regression: run, clean (2026-10-31 ET, this session)

Every landed host's battery and the jolt corpus were re-run twice: on `f934eff` (the shared
changes it carried), and again on the shared fixes of this session (the shim's `Numbers/divide`,
the policy's `:special` list, `boot/strip-string-tags`). Both times every number matched:
jolt 466/199/63/63/0 139/139 61/61; JVM 465/202/66/58/0 139/139 61/61; cljs 518/87/46/140/0
137/139 61/61; lpy 529/130/36/96/0 138/139 61/61; cljw 555/145/91/0 137/139 61/61; phel
591/73/127/0 139/139 61/61; let-go 569/105/117/0 137/139 61/61. Corpus (jolt commit 76166d17,
4,942 rows): stock 4298/507/137 (one more pass than before: the `#?@` row now reads as the JVM
reads it), rows stock-passes-host-fails 6, host-passes-stock-fails 176.

A fresh box has none of the hosts: `dev/install/*.sh` (plus babashka 1.13.229 and the Clojure CLI
1.12.6.1673 from their GitHub releases) restore them; installs need the user's approval. The
corpus is jolt's `test/chez/corpus.edn` at commit 76166d17 (`git clone` then `git checkout`).

## The CLI (merged from claude/cohost-cli-spike @ 00fe5fe, reviewed and fixed)

doc/CLI.md is current: design, verified matrix, differences from `clojure`. REPORT.md
"The `cohost` command, and what it found" lists the cohost bugs it exposed (all fixed).
Fixes over the spike: `-e` prints every non-nil value with the stock `prn` (the spike's
"clojure.main -e is silent" was wrong); errors to stderr, stop, exit 1, innermost cause;
`-X` prints nothing; alias `:main-opts`/`:exec-fn`/`:exec-args`/`:ns-default` from the
tools.deps basis (`-Sverbose -Spath` → `.basis` `:argmap`); kpath exec args; `-i`/`-e` order;
`*command-line-args*` (root + `set!` binding, before inits); namespace `user`; REPL over piped
stdin; `-J` dropped; config file deleted before `System/exit`; cljs rebuild on `.cljs`
changes; :phel and :lg wired (dev/phel_cli.phel, dev/lg_cli.lg). Test harness used: a demo
project with tools.cli (Maven jar from ~/.m2; Maven Central was 429-rate-limiting) + a
`:local/root` lib; compare each case with real `clojure` output. Not done: Phel's 5-case
matrix after the fixes (spot-checked only); `(let [a 1])` empty body is still an error on
ClojureWasm (its own `let` check, documented).

## clojurust (in progress: src/cohost/hosts/cljrs.cljrs)

Run: `bb boot:cljrs` (sets CLJRS_IR_THRESHOLD=1, CLJRS_NO_JIT=1, `--src-path target/cljrs-ns`).
Measured 2026-10-31 ET (this session): 791 forms, stock 561 / host 89 / skipped 141 /
**errors 0**; **battery 93/139**; **libraries 0/61** (each library's require fails: "var not
found cohost.adapter/push-thread-bindings"). Wall: about 905 s, of which the garbage collector
is about 730 s (see "GC" below); the boot's own work is about 35 s, the battery about 67 s.

Fixed this session:
- errors 3 -> 0: `fn` and `defn-` are special forms of its evaluator with **no var** in its core,
  so taking them from the host found nothing and the stock defs evaluated. Policy
  `host-overrides` gained `:special` (skipped, reason `:host-special-form`).
- the 13-minute hang before the first form: `strip-string-tags` ran one `cond` loop over 140,000
  characters in one call. A fn's first call is not lowered and its evaluator expands a macro again
  on every pass (measured: 30,000 iterations of a cond loop, 35.8 s unlowered, 55 ms lowered).
  The loop is now special forms only, its strings and comments skipped by fns of their own.
  (`CLJRS_EAGER_LOWER=1` also fixes it, but lowering core.clj's `destructure` at definition ran
  the box out of memory: exit 137.)
- the stack overflow after the boot: the adapter's `require-in` ran `eval` in its own namespace
  (eval in a fn evaluates in that fn's namespace), so `refer-all!` referred cohost.core's vars
  into `cohost.hosts.cljrs`, whose tree-walked fns then called stock `vec`/`get`, which called
  the shim, which called back: infinite recursion. `eval-in` moves the form into its namespace.

Next, by the battery's groups: binding push/pop (`:push-thread-bindings` — every library needs
it), `macroexpand-1`, `:sort-array!`, `:re-find-at`, deref of a volatile/delay ("cannot be cast
to Future"), `case=`/`protocol-fn` (types and protocols), then the rest of the 46 rows → libraries
61/61 → bench EDN + PERF column → REPORT section + README row → wire `:cljrs` in bin/cohost.

**GC (the open performance question).** Its collector starts at a third of system memory (5.3 GB
here) and then marks for minutes: 366 s at `take-nth`, about 440 s later. Measured with
`cljrs -X debug:gc run ...` and `CLJRS_MEMORY_SOFT_LIMIT_MB`: at 1 GB, 9.2 M objects in use,
169,000 live (27 MB), mark 42 s; every sampled stack is `PersistentList::trace` (a list's tail is
an `Arc`, traced through without a mark: lists sharing a tail re-walk it). The structure exists
only while a boot runs (after `boot!` returns, a mark takes 7-15 ms; during a 60-form boot it
climbs 10 ms -> 0.8 s -> 3.4 s), and no var reaches it (a walk eight levels deep through atoms,
maps, vectors and lists finds no long list). Not yet found: what holds it (interpreter frames,
IR regions — `region::trace_active_regions` traces every object of an open region as a root —
or closures). A smaller soft limit makes it worse (more marks of the same structure).

clojurust facts found so far (put them in REPORT's host section when it lands):
- Special forms (by full head text; a qualified one is a call of a stub var): def fn* fn if do
  let* let loop* loop recur quote var set! throw try defn defn- defmacro defonce and or . ns
  require letfn in-ns alias protocol* deftype* load-file binding with-out-str await. Macros
  named like them can't be defined (policy `:cljrs :host`). Its IR takes a special form only by
  a head that is a plain symbol: a head with metadata (core.clj lazy-seq's `^{:once true} fn*`)
  compiles as a var load — `as-code` strips it.
- **IR bug (0.2.32 and 0.2.33)**: `tiered/ir_interp.rs` KnownFn::Identical compares the
  addresses of its two argument slots → `identical?`, `true?`, `false?` are always false in a
  lowered fn. The lowerer uses it only where the ns maps the name to clojure.core's own var, so
  the adapter interns ns-own vars holding the native builtins into every cohost source ns
  before it loads (`cohost-source-nses` in cljrs.cljrs — add any new cohost namespace there).
  This was the cause of the earlier `(declare cat)` stack overflow (shim `dot` leaked `absent`).
- Sentinels (all-ns alter-meta! alter-var-root apply create-ns eval find-ns intern ns-* resolve
  swap! vary-meta …) are intercepted when the callee VALUE is that native fn; as values they
  throw; the IR intercepts only some — the adapter's `walked` macro keeps a fn tree-walked.
  `vary-meta` changes a Var's meta only (adapter wrapper fixes other values).
- `in-ns` refers ALL of clojure.core into the ns it enters, through the ns's
  `(:refer-clojure ...)` filter; cohost namespaces get `(ns X (:refer-clojure :only [...]))`
  (host dynamic vars added to that list). There is no `refer`: `require` is the only refer and
  it loads from a file first, once — the adapter writes an empty file per cohost-made ns under
  `target/cljrs-ns` (on the source path), then `(require '[X :refer ...])`.
- `(apply str many)`/join quadratic; string `nth` O(n) (`:string-chars` cap → vec); a
  delay/lazy-seq made in IR throws (memo by hand in rewrite inst-c; `walked` caps); eval in a
  fn evaluates in that fn's ns (adapter's `:eval` wraps `in-ns`); symbols with meta rejected by
  intern/ns-resolve (`plain`); `type` is coarse (symbols: List, Vector, user.T…); an `in-ns`
  inside a fn body moves that body's own resolution.
- Debug method that worked: a probe ns under a scratch dir (`cljrs run --src-path src
  --src-path <dir> probe.cljrs -- <root>`) that boots a core.clj truncated just past the
  failing form and evals pieces one at a time; `gdb -batch` gives Rust frames (no symbols).

## Rules worth re-reading (doc/hosts/PLAN.md)
Never edit vendored sources under resources/; host facts go in REPORT with a battery row;
never weaken a test; commit + push after each milestone; long runs in the background;
`.cljc` edits by script (the editor hook reformats them); `pkill -f` patterns can match your
own shell — kill by PID.

# Handoff — 2026-10-31 ET, branch `claude/eager-euler-ci8ev7`

Read first: doc/hosts/PLAN.md (the plan and its rules), README status table, doc/REPORT.md.
Hosts landed (M0–M5): ClojureWasm, Phel, let-go. In progress: **clojurust**. Then **jank**,
then M6 (natives) per host.

## FIRST: the regression for this commit has NOT been run

This commit carries shared-layer changes (adapter, rewrite, boot, shim, scan, policy) from
two pieces of work. The PLAN rule is: re-run every landed host's battery + the jolt corpus;
nothing that passed may regress. Run it before anything else (≈1–2 h, background):

    for h in jolt jvm cljs lpy cljw phel lg; do bb boot:$h > /tmp/reg-$h.log 2>&1; done
    bb corpus:jolt /home/user/jolt-lang/jolt/test/chez/corpus.edn > /tmp/reg-corpus.log 2>&1

Last green baseline (before this commit): jolt 466/199/63/63/0 139/139 61/61; JVM 465/202/66/58/0
139/139 61/61; cljs 518/87/46/140/0 137/139 61/61; lpy 529/130/36/96/0 138/139 61/61; cljw
555/145/91/0 137/139 61/61; phel 591/73/127/0 139/139 61/61; let-go 0 errors 137/139 61/61;
corpus stock 4297/508/137, rows stock-passes-host-fails 5, host-passes-stock-fails 176.
Any drop is a regression from this commit: fix at its source.

Shared changes most likely to move numbers: `cohost.scan/resolve-conditionals` in
`adapter/read-forms` (no-op without `#?` in the text); empty `let*`/`loop*`/`catch` bodies now
`nil` (rewrite.cljc); shim `-toString` of a namespace = its name; let-go `:dynamic-var?` treats
earmuffed core vars as dynamic (watch the lg battery); Phel `(:k m d)` → `(get m :k d)`;
`eval-str` `:on-value` option; cljs `*cljs-warnings*` single-segment off; strip-string-tags
uses `host/string-chars` (new `:string-chars` cap, default identity).

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

## clojurust (in progress — the adapter is src/cohost/hosts/cljrs.cljrs, uncommitted until now)

Run: `bb boot:cljrs` (sets CLJRS_IR_THRESHOLD=1, CLJRS_NO_JIT=1, `--src-path target/cljrs-ns`).
Last measured boot (before the CLI work): 791 forms, stock 561 / host 89 / skipped 138 /
**errors 3** (`fn` bootstrap def, `defmacro fn`, `defmacro defn-`: "fn* expects vector or
arity clauses" — these are policy-host names whose stock defs still evaluate; find why the
policy `:cljrs :host` list doesn't keep them from evaluating), then a **stack overflow in the
battery** (not yet located; use the same method below). Next: errors 0 → battery (139 minus
documented host facts) → libraries 61/61 → bench EDN + PERF column → REPORT section + README
row → wire `:cljrs` in bin/cohost.

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

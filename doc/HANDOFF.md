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

Next, by the battery's groups: `:sort-array!`, `:re-find-at`, deref of a volatile/delay ("cannot be cast
to Future"), `case=`/`protocol-fn` (types and protocols), then the rest of the 46 rows → libraries
61/61 → bench EDN + PERF column → REPORT section + README row → wire `:cljrs` in bin/cohost.

**GC: found and fixed (cohost side).** Its `vec` of a string walks it as a list and makes a list
of the remaining characters at every step (`ValueIter::next`, `builtins.rs`); a collection can
start at any allocation, and every object a running builtin allocated stays rooted until it
returns, so a collection during `(vec core-clj-text)` traced every suffix: quadratic, 366 s and
440 s pauses. Found with an instrumented clojurust (scratch build: `PersistentList::trace`
reporting walks over 20,000 cells, `PersistentList::cons` printing a backtrace at 30,001). The
adapter now builds a text's vector 512 characters at a time (`string-vec`): the 60-form boot's
slowest mark went from 21 s to 15 ms; the full boot reaches its report in about 2.5 minutes.
Upstream bug to file: `vec`/`ValueIter` over a string (and over a list: `GcPtr` per step).

**Open: clojurust crashes intermittently.** About half the full runs since end with a
segfault or "memory allocation of 140193633298272 bytes failed", both inside the IR's `str` on a
value an IR-made closure captured (`ir_interp::alloc_closure` -> `dispatch_known_fn`); caught under
gdb in the battery's "atoms" case. A run with the collector held off (12 GB threshold) also
segfaulted, so suspect the IR's escape analysis (a value that escapes, allocated in a region) and
the asynchronous lowering for the nondeterminism. This is a clojurust bug; report it upstream
with the gdb trace. Last completed run: battery 95/139, libraries 0/61 (then failing on
clojurust's own `the-ns`, which calls a `find-ns` its IR does not intercept — fixed since, not
yet seen in a completed run).

Since the last commit: `:binding-style :with-bindings` and `:with-thread-bindings!` in the
contract (the codemod's `scoped-bindings` pass writes push/try/pop pairs as one
`cohost.shim/with-thread-bindings` call; the loader uses the capability); clojurust's
`macroexpand-1` (a macro value called through a per-arity caller: &form is that call);
`the-ns` tree-walked. Regression on these: every number unchanged.

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

## jank (in progress: src/cohost/hosts/jank.jank)

Installed from jank's apt repository (`dev/install/jank.sh`): package `0.1-noble`, jank-0.1-alpha.
Run: `bb boot:jank`. Measured 2026-10-31 ET: 791 forms, stock 542 / host 159 / skipped 90 /
**errors 0**; **battery 103/139**; **libraries 29/61** (walk, set, zip, template whole). A boot
plus both batteries runs in about 35 s.

jank facts found (REPORT's host section when it lands; each verified on the binary):
- Its lexer rejects any non-ASCII character, in a string or a comment: cohost's sources are
  ASCII now (em dashes `--`; the battery's three non-ASCII characters are `\u` escapes, so its
  rows read the same on every host). The stock sources are ASCII.
- A macro's fn takes [&form &env & args], but jank's expander passes &form `((args))` and &env
  the macro's own name: `:expand-macro` (the let-go route) expands cohost's macros with the call
  form and an &env of the locals.
- Type hints are C++ types to its compiler, and its reader keeps `^Class` as `:tag (quote
  Class)`: the codemod's `strip-meta` now drops a quoted-symbol tag too. (User idea, open:
  translate JVM primitive hints to jank's native types for speed; measure with the bench.)
- Its compiler honors `:inline`, and core.clj's write raw JVM interop: `:inline-meta :strip`,
  `:rewrite-inline? true`.
- Its syntax-quote expands at read time through `clojure.core/concat*` (private): the adapter
  interns it into cohost.core.
- `(catch :default e)` binds nil; a jank value is caught as `cpp/jank.runtime.object_ref`, a
  compile error as `cpp/jank.error_ref` (its message through a `cpp/raw` helper); `try-all` has a
  `:jank` branch for the three.
- No records, types or protocols: cohost's own records (Iter, SB, MList, Matcher) are maps tagged
  `:cohost/standin` there. `class` and `instance?` throw TODO; `type` answers name strings
  (`volatile_`, `re_pattern`, `persistent_vector`, `big_integer`; a map entry is a vector).
- `push-thread-bindings` takes a hash map only; `indexed?`, `rseq` and refs' `sync` are TODO
  stubs; `(/ 1 3)` is 0 (no ratios); no clock (C++ `<chrono>` through `cpp/raw`), no `Math/`
  (C++ `<cmath>`).

Next, by the battery's groups: the A3 type layer (deftype/defrecord/defprotocol/reify; read
doc/hosts/jank-J0.md first: one `cpp/raw` object type is possible but jank's count/conj/assoc/meta
abort on an unknown type), `:sort-array!`, `:re-find-at`, `format` (jank's prints C-style), the
deref of a volatile/delay, native C++ exceptions in a few rows, `with-local-vars`, refs.

## Rules worth re-reading (doc/hosts/PLAN.md)
Never edit vendored sources under resources/; host facts go in REPORT with a battery row;
never weaken a test; commit + push after each milestone; long runs in the background;
`.cljc` edits by script (the editor hook reformats them); `pkill -f` patterns can match your
own shell — kill by PID.

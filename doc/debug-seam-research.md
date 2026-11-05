# Jank debugging & REPL through the cohost seam

Answering Jeaye's three questions — REPL workflows, source positions, debug
information — for jank as reached through cohost.cljc's source-transform
(codemod) layer, with a seam-by-seam mapping onto stock Clojure's (JVM)
default debugging features and a validation checklist for the capstone
close-out.

Versions and method. Installed jank is **0.1** (the .deb, "apt 0.1-noble"
per `src/cohost/hosts/jank_host.jank:5` in the cohost tree). The jank book
(book.jank-lang.org) and the jank source clone used here
(`~/workspace/cohost-install/jank-src`, main @ f3d82d6) describe *current*
jank; every behavior below that is marked "probed" was measured on the
installed 0.1 binary itself, so the load-bearing claims are 0.1 facts, not
book aspirations. Where a claim rests only on the book or on current-jank
source, it is flagged. Cohost citations are to the release7 tree
(`muse/httpbench` worktree, base 82c9b77 — the code the probes ran);
probes through the seam ran `bin/cohost :jank` from scratch projects in
/tmp on 2026-11-04.

---

## (a) Jeaye's three questions, answered for jank through cohost

### 1. REPL workflows

**Plain jank** has two REPL surfaces, both first-class:

- `jank repl` — a terminal REPL that also starts an nREPL server
  ("jank has nREPL support both via Leiningen projects and directly from
  the `jank` command. This has been tested to work with both CIDER and
  Conjure." — book, *Hello, nREPL!*,
  https://book.jank-lang.org/getting-started/04-hello-nrepl.html).
- The evaluator behind it maintains the Clojure REPL state vars: jank's
  `safe_eval` shifts `*1`/`*2`/`*3` on success and binds `*e` on any
  failure — a raw C++ exception, a non-ex-info object, and a
  `std::exception` are each wrapped into an `ex-info` carrying a captured
  native trace (`compiler+runtime/src/cpp/jank/evaluate.cpp:697-752`).
  Probed on 0.1: after `(inner 5)` throws, the REPL prints the `#error`
  dump and `*e` holds the exception.

**Through cohost**, the REPL is cohost's own driver, not jank's
(`src/cohost/cli.cljc:139` `repl!`: "clojure.main's REPL, a line at a
time… an error is reported and the REPL goes on"). It mimics the JVM's
surface — banner `Clojure 1.12.0`, `user=> ` prompt, values printed, the
session namespace tracked — but the error path is `report-error!`
(class + message only, see §a.3) and **nothing in cli.cljc, boot.cljc or
shim.cljc ever sets `*1`/`*2`/`*3`/`*e`**. Probed through the seam
(2026-11-04): after a throw, evaluating `*e` prints `nil`, and `*1`
prints `nil`. The stock vars exist (stock core declares them) but the
driver never maintains them. nREPL through cohost: cohost has no nREPL
server of its own, and jank's nREPL serves jank's own core, not the
cohost-booted stock core — there is no nREPL-into-cohost session today
(UNKNOWN as a product question; nothing in the tree wires one).

So: the day-to-day REPL *shape* survives the seam; the REPL *state
protocol* (`*e`, `*1`…) does not — and that is a cohost-driver gap, not a
jank gap, since jank's own REPL maintains exactly those vars.

### 2. Source positions

**Plain jank** tracks positions rigorously. Every form its reader reads
carries `:jank/source {:file … :module … :start {:offset :line :col}
:end {…}}` metadata (`compiler+runtime/src/cpp/jank/runtime/core/meta.cpp:117-160`,
`meta_source`/`source_to_meta`), and macro expansion provenance is kept
alongside (`read/source.hpp:56-60`: the source struct holds the
`macro_expansion` form). Its diagnostics use them: probed on 0.1, an
unresolved symbol prints the fancy report —

```
─ analyze/unresolved-symbol ──…
error: The symbol `nosuchvar` could not be resolved.
  1  │ (ns bad1)
  2  │ (defn f [x] (nosuchvar x))
     │              ^^^^^^^^^ Found here.
     │ ^ Expanded from this macro.
```

— file name, line gutter, caret span, and a per-error-code book URL
(also `parse/unterminated-list`: "^ List started here."). Runtime traces
use them too: probed uncaught `ex-info` under `jank run-main` prints a
clojure-style `#error` map whose `:trace` frames are demunged and
positioned — `["boom3/inner (arity 1)" "boom3.jank" 2]`,
`["boom3/outer (arity 1)" "boom3.jank" 3]` — original file, original
line (demunging in `src/cpp/jank/util/try.cpp:37-58`: symbols like
`clojure_core$subs_…_2` become `clojure.core/subs (arity 2)` for frames
whose file ends `.jank`/`.cljc`).

**Through cohost's codemod**, positions survive the *rewrite* but are
weakened at both *ends* of the pipeline:

- *The rewrite preserves them.* `rewrite.cljc:842` `carry-meta` puts the
  original form's metadata on every rewritten form; `strip-meta`
  (rewrite.cljc:825) removes only JVM directives (`:tag` class hints,
  `:rettag`, optionally `:inline`/`:arglists` hints). `:jank/source`,
  `:line`, `:column`, `:file` are never stripped. And evaluation is by
  form object, not by re-printed text: the jank adapter does not
  override the default `:eval` capability, `(fn [form] (eval form))`
  (`src/cohost/adapter.cljc:318`, used via `adapter.cljc` `eval-form`),
  so the analyzer sees the carried meta.
- *The read end loses the file and the absolute line.* The jank adapter
  reads a file by scanning each top-level form's extent and calling
  jank's `read-string` on that form's substring alone
  (`src/cohost/hosts/jank_host.jank:39-46`). Jank's reader takes the
  `:file` of `:jank/source` from its `*file*` var
  (meta.cpp, `source_to_meta`), whose root binding is
  `"NO_SOURCE_PATH"` (`src/cpp/jank/runtime/context.cpp:42`) and which
  jank binds to a real path only when *jank itself* loads a file
  (`context_dynamic.cpp:44`). Cohost's loader never binds it. So a form
  read through the seam is positioned at best relative to its own text
  (line 1 = the form's first line) under a placeholder file name —
  within-form relative positions are the reader's; the file name and
  the form's offset within the file are not carried. (What exactly
  `read-string` attaches is check #2 in the validation list; the code
  path leaves no other possibility for the file component.)
- *The AOT end re-derives positions from generated text.* Snapshot
  recording drops all position metadata on purpose — `snap-drop-meta`
  = `#{:line :column :end-line :end-column :file :source-span
  :jank/source}` (`src/cohost/boot.cljc:179`, "Reader metadata the host
  adds again when it reads the snapshot's source") — and the emitter
  prints each namespace's recorded forms, one per line, into a
  generated module file named by namespace under the build tree
  (`dev/perf/jank_snapshot_emit.clj:224` `module-src`, `:257`
  `module-file`). A trace frame in a built app therefore names the
  *generated* file and a line that counts top-level forms in it — the
  namespace is right; the user's file and line are not in the binary.

And the sharpest seam fact, probed 2026-11-04: **a defn whose body
contains an unresolved symbol loads silently through the seam**
(`bin/cohost :jank -M:main` ran, printed its result, exit 0; the var
resolves normally), where plain jank fails the load with
`analyze/unresolved-symbol` and JVM Clojure fails with "Unable to
resolve symbol". The error is deferred to **first call** — and there
it escapes as an *uncatchable native C++ exception*: a stock
`(catch Throwable e …)` around the call does not catch it (the
adapter's `:catch-all` is `cpp/jank.runtime.object_ref`, which per the
adapter's own header "misses a native C++ exception",
`src/cohost/hosts/jank_host.jank:5-9`), and the top-level report is
`Execution error (ExceptionInfo).` / `a native C++ exception`, exit 1.
Jank's actual analyze diagnostic — the symbol, the position, the
caret box — is inside that native exception and the seam's report
discards it. The load-time compile check a Clojure programmer relies
on thus fails *later*, *uncatchably*, and *without its message*.

### 3. Debug information

**Plain jank**:

- Internal/compiler errors and native crashes render through cpptrace:
  "Stack trace (most recent call first):" with demunged Clojure frames
  (`util/try.cpp:80-105`, formatter config; probed for the
  missing-`-main` error, `runtime/uncaught-exception` box + trace).
- AOT binaries carry debug source maps for generated code **by
  default**: `jank compile --help` lists `--no-debug  Disable debug
  source map generation for generated code` (probed on the installed
  0.1 binary). Cohost's build does not pass it
  (`src/cohost/build_jank.clj` step 4: `jank -O3 … compile …
  --runtime static`, no `--no-debug`), so built apps keep the maps.
  The lein route's default profile is likewise a "debug executable
  with fewer optimizations" (book, *AOT compiling*).
- The debugger story is the native one, documented in the book
  (*Troubleshooting → How to get a stack trace*,
  https://book.jank-lang.org/troubleshooting/stack-trace.md):
  `gdb --args jank run foo.jank`, `backtrace`, and break-on-throw via
  gdb's `catch throw` / lldb's `breakpoint set -E c++`.
- Introspection of the transform itself: `JANK_PRINT_IR=1` and
  `JANK_PRINT_CODEGEN=1` print each compiled function's IR / generated
  C++ (book, *Printing jank's IR or codegen*). Profiling:
  `--profile`/`--profile-output` (a `jank.profile` file) and `--perf`
  (Linux perf sampling) on the CLI; `jank.perf` is a bench framework,
  not a profiler.
- Some JVM "exceptions" are not exceptions at all: "Division by
  integer `0` is undefined behavior" (book, *Differences from
  Clojure*). Probed on 0.1: `(/ x 0)` on integers kills the process
  with SIGFPE — exit 136, no error report, no trace. The gdb page
  exists precisely for this class.

**Through cohost**, the *top-level* error signal is deliberately
narrowed by cohost's CLI, on every host: `report-error!`
(`src/cohost/cli.cljc:55-64`) prints only

```
Execution error (ExceptionInfo).
<innermost cause's message>
```

to stderr and the run ends with exit code 1 — documented as a design
decision in `doc/CLI.md` ("The error report names the class and
message, not where it was thrown, and writes no full report file").
Probed through the seam: an uncaught `ex-info` from `-main` produced
exactly those two lines and `EXIT=1`. Compare clojure.main, which
prints `Execution error (ExceptionInfo) at probe.core/inner
(core.clj:5).` and writes the full `#error` report to a temp file.
Everything jank knows about the throw — the positioned trace, the
ex-data — is inside the exception object, but the seam's default
report throws it away. (User code can still catch and print; what the
shim's exception objects expose to stock `clojure.stacktrace` is
check #6.)

Names: vars print under their *rewritten* homes where the codemod
moved them — `#'clojure.core/map` prints `#'cohost.core/map`
(`doc/CLI.md`, "Names print under `cohost.*`"). User vars keep their
names; core/shim frames in any trace name `cohost.core`/`cohost.shim`
machinery, not `clojure.core`.

---

## (b) Mapping table: Clojure (JVM) default → jank → through the cohost seam

Verdicts are for **jank through cohost** (the seam column), with the
plain-jank state noted where it differs.

| # | Clojure (JVM) default feature | Jank equivalent | Through the cohost seam | Evidence |
|---|---|---|---|---|
| 1 | Uncaught exception report: `Execution error (X) at ns/fn (file:line).` + message | `#error` data dump with `:cause/:data/:via/:trace`, positioned demunged frames; exit 1 (probed) | **DEGRADED** — class + message only, no location, no trace, no report file; exit 1 preserved | cli.cljc:55-64; doc/CLI.md "names the class and message, not where it was thrown"; seam probe → 2 lines, EXIT=1 |
| 2 | Full error report file (`/tmp/clojure-*.edn`) | none (the dump is printed inline instead) | **LOST** | doc/CLI.md (explicit: "writes no full report file") |
| 3 | Stack traces with original file/line (`StackTraceElement`s) | cpptrace traces + `#error` `:trace`, original file/line for `.jank`/`.cljc` frames (probed) | **DEGRADED** — the CLI report prints none of it; in-program access via shim exceptions unverified (check #6); AOT frames cite generated module files (§a.2) | try.cpp:37-105; boot.cljc:179; snapshot_emit.clj:224-260 |
| 4 | Compile errors with source position ("Syntax error compiling at (f:12:3)") | Structured error boxes: code, excerpt, line gutter, carets, macro-expansion note, book URL (probed) | **DEGRADED** — a user defn with an unresolved symbol *loads silently* (probed, exit 0); at first call it throws a native C++ exception that stock `catch Throwable` cannot catch and the CLI reports only as "a native C++ exception" — jank's diagnostic never surfaces. Positions of errors that do fire at read time are form-relative under a placeholder file (§a.2) | seam probes sp2/sp2b; jank_host.jank:5-9,39-46; source.hpp:56-60 |
| 5 | Reader errors with file/line/col | `parse/*` error boxes with "List started here" positions (probed) | **UNKNOWN** — the seam reads via its own scan + per-form `read-string`; malformed file behavior through the seam unprobed (check #4) | bad2.jank probe (plain jank); scan/read-forms path |
| 6 | REPL `*e` bound to last exception | maintained by jank's own REPL/`safe_eval`, incl. wrapping C++ exceptions (evaluate.cpp:697-752; probed) | **LOST** — cohost's REPL never sets it; `*e` ⇒ `nil` after a throw (probed) | cli.cljc:139-156 (no var maintenance anywhere in cli/boot/shim) |
| 7 | REPL `*1`/`*2`/`*3` result history | maintained by jank's `safe_eval` | **LOST** — `*1` ⇒ `nil` after a successful form (probed) | same as #6 |
| 8 | `clojure.repl/pst` (print `*e`'s trace) | no `clojure.repl` ships with jank; jank's `clojure.stacktrace` is a stub: `print-cause-trace` = `(print (str tr))` (jank-src `src/jank/clojure/stacktrace.jank`) | **UNKNOWN** — cohost boots the *stock* `clojure.repl`/`clojure.stacktrace` (doc/REPORT.md: libraries list), so `pst` exists, but it walks the shim's exception objects; what it prints on jank is unprobed (check #7). Doubly moot while `*e` is unset (#6) | REPORT.md library table; stacktrace.jank (jank's own) |
| 9 | `clojure.stacktrace/print-stack-trace` etc. | stub (above) | **UNKNOWN** — stock version boots through cohost; fidelity depends on shim exception traces (check #6) | same as #8 |
| 10 | `*err*` routing: reports and `binding [*err* …]` to stderr / redirectable | jank core: `*out*`/`*err*` are boxes over stdout/stderr (jank core.jank:27-28) | **PRESERVED** (by construction) — stock core's `*err*` is a shim writer over the host stderr; cohost's own report path binds `*out*` to `*err*` (cli.cljc `err-println`); probes show reports on the error stream path with exit 1. Stream *separation* not separately verified (check #8) | cli.cljc:33-36,55-64; seam probes |
| 11 | Exit codes: 1 on uncaught throw from `-main`/`-e`; `System/exit` honored | jank: exit 1 on uncaught (probed, plain and seam); crashes give signal exits (136 for SIGFPE, probed) | **PRESERVED** | seam probe EXIT=1; cli.cljc `exit!` → adapter `:exit` |
| 12 | Compiler/macro expansion provenance ("compiled at", macro call sites) | source struct carries the macro-expansion form; diagnostics print "Expanded from this macro" (probed, plain) | **DEGRADED** — codemod carries meta, but the fancy diagnostic renderer is jank's *compiler* error path, which the seam's silent-load behavior (#4) bypasses for user code; AOT drops `:jank/source` entirely (boot.cljc:179) | source.hpp:56-60; rewrite.cljc:842; boot.cljc:179 |
| 13 | Debugger: JDI/JPDA breakpoints by line; agents | gdb/lldb; `catch throw` breaks on throw; AOT source maps on by default (`--no-debug` disables; probed in 0.1 `--help`) | **DEGRADED** — the native story still works on the process, and cohost's build keeps source maps (no `--no-debug` in build_jank.clj step 4), but the maps resolve to *generated* module files in AOT (§a.2), and there is no line-level Clojure debugger on either side of the seam | book troubleshooting/stack-trace.md; build_jank.clj step 4 |
| 14 | Profiling: JFR/jstat/`-prof` (via agents), `time` | `--profile` → `jank.profile`; `--perf` sampling; `JANK_PRINT_IR`/`JANK_PRINT_CODEGEN` for the transform itself | **UNKNOWN** — cohost exposes none of these flags through bin/cohost; whether the env vars/flags reach the snapshot launcher's jank invocation is unprobed (check #11). Cohost has its own per-form profiler for boot (boot.cljc `profile-form!`) — a boot diagnostic, not a user profiler | jank `--help` (probed); book troubleshooting/printing.md |
| 15 | Arithmetic exceptions (`(/ 1 0)` → ArithmeticException) | integer div-by-zero is *undefined behavior* → SIGFPE, no report (book + probed) | **DEGRADED** — through the seam it depends on the path: stock core's `/` is booted code (its own zero checks may fire first for some arg shapes), but any route that reaches jank's native integer division crashes the process with no Clojure-level signal (check #10 discriminates) | book differences-from-clojure.md "Division by integer 0 is undefined behavior"; plain-jank probe exit 136 |
| 16 | Names in traces/messages are the program's names | demunged `ns/fn (arity n)` (probed, plain) | **DEGRADED** — core fns appear under their rewritten homes (`#'clojure.core/map` prints `#'cohost.core/map`); user names survive | doc/CLI.md "Names print under cohost.*" |

Counts: **PRESERVED 2** (#10, #11) · **DEGRADED 7** (#1, #3, #4, #12, #13,
#15, #16) · **LOST 3** (#2, #6, #7) · **UNKNOWN 4** (#5, #8, #9, #14).

---

## (c) Validation checklist for the capstone close-out

Each check runs through cohost on jank unless it says plain jank. "Pass"
states what the output must show for the feature to count as working
through the seam. Checks 1-3 are the load-bearing ones.

1. **Uncaught throw, JIT (the report contract).** Scratch project,
   `-main` throws `(ex-info "kaboom" {:x 5})` from a defn at a known
   line, via `bin/cohost :jank -M:main`, streams separated
   (`1>/tmp/out 2>/tmp/err; echo $?`). Pass (current documented
   behavior): stderr is exactly `Execution error (ExceptionInfo).` +
   `kaboom`, exit 1, nothing on stdout. Any trace/location appearing
   is an improvement to record, not a failure.
2. **What source meta survives the read.** In `bin/cohost :jank -e`,
   evaluate `(meta #'inner)` / read a form from the probe file via the
   adapter's `read-forms` and print `(meta form)` and
   `(:jank/source (meta form))`. Pass criterion to *record*: the
   `:file` value and whether `:line` is file-absolute or form-relative.
   This settles §a.2's open point with a number.
3. **Unresolved symbol: load vs call.** The sp2 project (`nosuchvar`
   inside a defn body, line 7): (i) `-M:main` that only defines —
   exits 0 silently (probed); (ii) `-M:main` that *calls* the bad
   defn inside `(try … (catch Throwable e …))` — probed result: the
   catch does not fire; the run ends `Execution error
   (ExceptionInfo).` / `a native C++ exception`, exit 1. Re-run to
   confirm both halves stay stable, and record whether *any*
   catch type available through the seam (e.g. the adapter's
   `:catch-all` spelling) can catch it. JVM parity would be a
   load-time failure naming probe2/core.cljc:7.
4. **Reader error through the seam.** A user file with an unterminated
   list starting at a known line, loaded via `-M:main`. Record the
   message, whether a position appears, and which file/line it names.
   Plain-jank reference (same content as a .jank file): the
   `parse/unterminated-list` box with "^ List started here."
5. **REPL `*e`/`*1`.** Pipe `(defn inner [x] (throw (ex-info "r" {:x x})))`,
   `(inner 5)`, `*e`, `(+ 1 2)`, `*1` into `bin/cohost :jank`. Current
   measured state: `*e` ⇒ nil, `*1` ⇒ nil. Pass for parity: `*e`
   prints the ex-info, `*1` ⇒ 3. (Fix belongs to cohost's `repl!`:
   mirror clojure.main — set the vars around `eval-str`.)
6. **In-program trace access.** `-e` form: catch the ex-info from the
   probe and print `(map str (.getStackTrace e))`-equivalent through
   the shim — practically: `(require 'clojure.stacktrace)`,
   `(clojure.stacktrace/print-stack-trace e)`. Record whether frames
   appear and what file/line they carry. This decides rows #3/#9.
7. **`pst` through the seam.** After check 5's throw (or an explicit
   `(pst e)` on a caught exception): `(require 'clojure.repl)`,
   `(clojure.repl/pst e)`. Record the output shape vs the JVM's.
8. **`*err*` separation and rebinding.** (i) `-e '(binding [*err*
   *out*] (binding [*out* *err*] (println "x")))'`-style swap, or
   simpler: run check 1 with streams separated and confirm the report
   is on stderr only (done as part of check 1's setup). (ii) A program
   that binds `*err*` to a `StringWriter`-equivalent and prints it —
   pass if the captured text contains what `prn`-to-`*err*` wrote.
9. **AOT uncaught throw.** Build the throwing probe with
   `cohost -T:build build` (jank, static) and run the binary from
   another directory. Record: exit code (expect 1), the report shape,
   and — the AOT-specific question — whether any frame/file cited is
   the user's source or a generated `target/…/src/<ns path>.jank`.
   Compare with check 1.
10. **Integer divide-by-zero.** `-e` and a built AOT app evaluating
    `(/ 5 0)` on longs (and `(quot 5 0)`): record signal vs
    ArithmeticException per path. Plain-jank reference: SIGFPE, exit
    136. This decides row #15 per path.
11. **Transform introspection.** Run a JIT eval with
    `JANK_PRINT_CODEGEN=1` in the environment of `bin/cohost :jank -e`
    and confirm the *rewritten* forms' C++ is what prints (it should
    show shim/cohost.core names — direct evidence of what the host
    compiler actually receives). Record whether the env var propagates
    through bin/cohost's launcher at all.
12. **gdb break-on-throw.** On the AOT binary from check 9:
    `gdb --args ./run`, `catch throw`, `run`, `backtrace`. Pass: gdb
    stops at the throw and the backtrace resolves jank frames (source
    map present). Record whether frames resolve to generated or user
    files — the AOT debugger story in one shot.
13. **Macro-expansion error.** A user macro that throws (or expands to
    an unresolved symbol) during load through the seam: record whether
    the report identifies the macro call site ("Expanded from this
    macro" on plain jank) or only the expansion.
14. **Exit codes matrix.** `-e` throw ⇒ 1; `-X` missing fn ⇒ the
    tools.deps-style message + nonzero; script file that calls
    `(System/exit 3)` through the shim ⇒ 3. Pass: all three match the
    JVM CLI's codes.

---

## Headline gaps (for the close-out discussion)

**Close-out (2026-11-05, final gate on the collated
`muse/debug-seam` @ be95bb2): all five headline gaps are
closed.** (1) The report carries the trace —
`:clojure.main/trace` in the report file, root-cause-first.
(2) The REPL maintains `*e`/`*1`/`*2`/`*3` at clojure.main
parity (check #5 PASS on both hosts). (3) Unresolved symbols
fail the load with the symbol named (check #3 PASS on both
hosts), and native C++ exceptions are caught by a stock `catch`
through the seam's sibling clauses (check #3 native half PASS
on jank — the catch hole is closed). (4) Positions are real:
the jank read end carries file + file-absolute line (check #2
PASS), and the snapshot emitter writes forms at their original
lines (858 of 873 placed). (5) The native-crash class shrank to
its boundary: seam division throws ArithmeticException (check
#10 PASS on both hosts); only arithmetic written directly in
`cpp/` interop still traps SIGFPE — the passthrough is verbatim
by contract, the same line the JVM draws at JNI. The mapping
below is the pre-implementation measurement this close-out
started from; the gate's per-check table lives in
doc/guide/11-debugging.md, with the full gate account in
REPORTS.md on muse/cohost-reports.

1. **The seam's error report discards the trace by design**
   (cli.cljc `report-error!`): jank produces positioned, demunged
   traces; cohost's CLI prints class + message. Every debugging
   workflow that starts "run it and read the trace" stops here.
2. **`*e`/`*1`/`*2`/`*3` are never set by cohost's REPL** — a small,
   well-bounded driver fix (jank's own `safe_eval` is the reference
   implementation), and it unblocks `pst`-style workflows.
3. **Load-time analysis failures for user code don't fire — and the
   deferred failure is uncatchable and content-free** (the silent
   `nosuchvar` defn: loads fine, and at first call throws a native
   C++ exception that stock `catch Throwable` misses and the report
   renders as "a native C++ exception"). Errors move later, escape
   the language's own error handling, and lose their diagnostics —
   the transform's most dangerous debugging property.
4. **Positions are form-relative under a placeholder file in JIT, and
   generated-file positions in AOT** — the codemod itself is
   meta-faithful (`carry-meta`), but nobody binds `*file*` on the way
   in and the snapshot drops position meta on the way out.
5. **Native-crash class**: integer div-by-zero (and kin) produce
   signals, not exceptions — through the seam as on plain jank; the
   only tool is gdb, per jank's own troubleshooting page.

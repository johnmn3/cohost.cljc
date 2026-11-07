# Cohost portable installer — addendum report: the Jolt seat, and the ClojureCLR verdict

Successor session to INSTALL-PORTABLE-REPORT.md, on the same
branch `lane-m/cohost-install-portable`, based on the verified
three-seat tip `5952698418d711d1c4cd27ae1a2811cb9ff2ebc2`.
Operator order (Nov 7 2026): add Jolt and ClojureCLR as official
install.cljc seat targets, to the same proven standard. All
proofs ran on j.slaa.us under a claimed heavy-queue slot
(released at close), staged on the box's second disk
(/home/john/j/cohost-portable2 — the root disk sat at 100%).
Nothing was merged to main or to `release/eve-in-cohost`.

## Verdicts, up front

- **Jolt: PROVEN — a fourth seat.** `install.cljc` gains `:jolt`
  arms and a step-2 dispatch; every acceptance shape the three
  proven seats met, the Jolt seat met (details below), including
  the identical snapshot key.
- **ClojureCLR: NOT A SEAT — proven, with the missing facility
  named.** The seat itself is capable (script entry, subprocess,
  env, self-exe all probed working), but no ClojureCLR-loadable
  form of the snapshot build exists in the repo or in released
  libraries, so there is no `:cljr` arm to ship. The README and
  getting-started guide name it as not-a-seat, with the reason.

## Host facts, established firsthand

Reader features were probed with arm-selection files on the
actual hosts, never assumed:

- **Jolt** (jolt-lang/jolt v0.8.23 — the version cohost's own
  dev/install/jolt.sh pins): runs a file directly (`jolt run
  FILE`). Its reader features are **`:jolt` and `:clj`** (a
  `:clj`-only arm matches; first written arm wins), so `:jolt`
  arms are written ahead of `:clj` — the house reader-order rule
  (dev/lint_reader_order.clj names Jolt as one of the two hosts
  that also read `:clj`). It bundles working babashka.process
  (capture + exit codes), babashka.fs and clojure.java.shell;
  `(System/getenv ...)`, `(System/exit ...)` and a
  `/proc/self/exe` readlink through its java.nio.file emulation
  all probed functional.
- **ClojureCLR** (1.12.6, the `Clojure.Main` dotnet tool): runs
  a file directly (`Clojure.Main FILE`); namespaces via
  CLOJURE_LOAD_PATH. Its reader feature is **`:cljr` only** (a
  `:clj`-only arm does not match). All five shim facilities
  probed working: `System.Environment/GetEnvironmentVariable`,
  `System.Environment/ProcessPath` (the Clojure.Main path),
  `System.Diagnostics.Process` capture (stdout + ExitCode) and
  inherit (exit 7 propagated), `System.Environment/Exit`.

## What shipped for Jolt

One seat, in the established pattern, no change to the three
proven arms' behavior and no new regex literals:

- seat def gains `:jolt` ahead of `:clj`; the ns splice requires
  babashka.process under `:jolt` as under `:bb`.
- capture / sh! arms mirror the `:bb` arms (Jolt's bundled
  babashka.process); env rides the existing `:default`
  (System/getenv) arm; self-exe mirrors the `:bb` arm
  (/proc/self/exe readlink, `which jolt` fallback); the bottom
  handler gains a `:jolt` arm (catch Throwable, System/exit).
  Seat dispatch stays if-chains (the jank `case*` ban stands).
- Step 2 drives the **original snapshot build** under Jolt —
  the bb/clj shape — with one pinned dep:
  `-Sdeps '{:deps {io.github.jolt-lang/crypto {:git/url
  "https://github.com/jolt-lang/crypto" :git/tag "v0.2.0"
  :git/sha "e6981b853adc543120b785a0c29b6d7d6a10f0fe"}}}'`.
  The pin exists because the build's in-process hashing
  (dev/perf/sha256.clj) calls java.security.MessageDigest,
  which Jolt does not bundle: loading the build under bare Jolt
  faults with "No dependency provides
  java.security.MessageDigest … A library supplies one by
  declaring :jolt/provides in its deps.edn (RFC 0014)", and
  jolt-lang/crypto is that provider (Jolt's own conformance
  manifest says so; its deps.edn declares the provide). Both
  `:git/tag` and `:git/sha` are required — tag alone failed in
  Jolt's resolver. With the pin, the whole key chain runs under
  Jolt locally before any J proof: key.clj loads, its `*file*`
  sibling loads work, and `fresh-path` computes the snapshot
  key and reports staleness correctly.
- install.edn records `:seat :jolt` and the driver path under
  `:jolt`, by the existing generic code.

Proof verdicts (logs in the J staging, summarized in
INSTALL-PORTABLE-STATUS.md; every claim read from the logs):

- **Load check (all four armed seats): PASS.** From a neutral
  cwd with `--repo /nonexistent-bogus`: jolt ("cohost install:
  jolt jolt v0.8.23"), bb and clj locally; jank on J (this box's
  jank lexer rejects the em-dash in the file's first comment
  line — the pristine predecessor file fails byte-identically
  under the same command, so it is this box's jank build, not
  the edit; J's jank reads the file and its check is green).
  Each: banner, "no cohost checkout at /nonexistent-bogus",
  "cohost install failed: install: stopped", rc 1.
- **Boundary probe: PASS.** With jank poisoned and a pristine
  checkout, the Jolt-driven build tripped at its first jank
  touch — the snapshot key's `jank print-binary-version` at
  jank_snapshot_key.clj:45, Process error exit 99 — installer
  rc 1, and the invocations log holds exactly that one call.
  The same shape as the bb seat's probe.
- **Stage A (cold build): PASS.** clojure poisoned throughout,
  never invoked. SNAPSHOT-BUILT
  df88adacda69c0e2d4ac53df68476d9d5b6ea40d7dbac44bb5d9c1bbc87bde39
  — the identical key to the jank, bb and clj flows (the
  build's own timing line: 1,067,528 ms); PLACEMENT 857 of
  872; INSTALLER-RC=0; deps-new installed at the reference
  record `{:git/tag "v0.17.0", :git/sha "657fca6"}` with the
  skipping re-run; `cohost -e "(+ 1 2)"` → 3; `-Spath`
  correct.
- **Battery on the Jolt-built snapshot: 219/221 stock
  behaviors, 62/62 library cases, rc 0** — the proven flows'
  exact numbers (the 2 failures are the \u-escape cases that
  fail on every flow).
- **Stage B (fresh snapshot): PASS.** bb AND clojure poisoned:
  SNAPSHOT-FRESH on the same key, INSTALLER-RC=0, `-e` → 3,
  and neither poison was ever invoked.

## The ClojureCLR verdict, with its evidence

A `:cljr` arm was not shipped, because the seat cannot drive
the snapshot build — and that is proven, not inferred:

1. The original build (dev/perf/jank_snapshot_ensure.clj →
   jank_snapshot_key.clj) requires babashka.fs +
   babashka.process. Neither library has `:cljr` arms in any
   release: checked at the clj seat's pins (fs v0.5.24,
   process v0.5.22) and at the latest tags (fs v0.5.34,
   process v0.6.25) — zero `cljr` matches in either tree.
   Loading the ensure under Clojure.Main with the pinned
   sources on CLOJURE_LOAD_PATH fails at once:
   "Execution error (FileNotFoundException) at babashka.fs/…
   Could not locate clojure/java/io with extensions .cljr,
   .cljc, .clj, … on load path" — ClojureCLR has no
   clojure.java.io at all, and fs's arms are java.nio.file
   interop besides.
2. The jank-native build (dev/install/jank_build.cljc) loads
   cohost.install.shim, which is raw-jank C++ with no reader
   conditionals. Loading it under Clojure.Main fails at
   shim.cljc:11:2: "No such namespace: cpp".

The missing facility, named precisely: **a ClojureCLR reader
path for the snapshot build** — CLR arms for the build's driver
libraries, or a non-C++ arm of the install shim library that
jank_build.cljc could load. ClojureCLR's own house is in
order (every shim facility the installer itself needs probed
working, above), so the day either library ships `:cljr` arms,
this seat is a straightforward follow-on in the Jolt seat's
shape. Until then, shipping an arm would ship an installer
that runs and dies at step 2 — named in the docs instead.

## Boundaries, stated plainly

- The installed `cohost` command is unchanged: jank-compiled
  on every seat. The Jolt seat, like bb/clj, generalizes who
  *drives* the install.
- The inherited emit wart applies to the Jolt seat exactly as
  to the clj seat: the original build's emit step is a `bb`
  process (hardcoded in jank_snapshot_key.clj), so a Jolt-driven
  *cold* build needs bb present for the emit; a fresh snapshot
  needs neither bb nor clojure (stage B). Untangling that is a
  dev/perf change that would disturb snapshot-key parity —
  named, not taken.
- Jolt is pinned at v0.8.23 (the version cohost's Jolt rows
  were measured on; the crypto provider declares a
  `:jolt/min-version` of 0.8.0). The proofs ran the staged
  release binary, sha256-verified against the release sidecar.
- A JVM-pprint wart and the other predecessor boundaries are
  unaffected by this session.

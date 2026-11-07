# Cohost install lane — Jank-only install (Lane M)

Branch: `lane-m/cohost-install-jank`, forked from `release/eve-in-cohost`
@ 60353a0808e440cd798d43c61acf4b6fb966abee (verified on the fetched ref
and by ls-remote before forking). Worktree: ~/workspace/wt/cohost-install-jank.

## The order (operator, Nov 7 2026)

The install step must not require Babashka and the Clojure CLI. Jank is
the default runtime targeted on installation — the install says "you need
Jank." The installer installs the cohost tool in the same sense Clojure
installs its tool: after it runs, all Clojure commands run through the
`cohost` tool. The installer is CLJC running on top of Jank, fetched by
clone or curl. The deps-new step reads exactly:
`cohost -Ttools install-latest :lib io.github.seancorfield/deps-new :as new`.

## State: IN-PROGRESS (06:44 ET, Nov 7 2026)

Surveyed (firsthand, this tree):
- README Install (lines 14–32): requires Babashka + Clojure CLI; the
  deps-new step is `clojure -Ttools install-latest ...`; then
  `bash ~/cohost/dev/install/jank.sh`.
- bin/cohost is a Babashka script. Its dispatch delegates to the real
  `clojure` binary for: `:jvm`, every `-T` (tools), and `-Sdescribe`;
  and `resolve-deps` shells out to `clojure -Sverbose -Spath` for every
  other host run. So without `clojure`/`bb` the current launcher cannot
  run anything. The installed tool must be a Jank-side front-end.
- The Jank AOT path exists: stage-1 AOT objects (dev/perf/jank_aot_ensure.clj),
  the core snapshot + `cohost-jank` launcher binary (dev/perf/
  jank_snapshot_key.clj `ensure!`: record -> emit -> `jank compile -O3`),
  and `cohost.build-jank` for apps. The snapshot build is bb-driven today
  (emit is a bb script using babashka.fs).
- dev/install/jank.sh installs jank (brew/apt) idempotently; its snapshot
  step is skipped without bb — the installer must drive the build itself.
- Raw jank probe (target/probe/p2.jank, ran clean): spit/slurp work;
  cpp/raw + std::system spawns processes (exit captured); std::getenv
  works; *command-line-args* works. Inside cohost's booted Clojure there
  is NO process-spawn facility in the adapter — so the installer and the
  front-end are raw-jank CLJC, not cohost-booted programs.

Design (to be built):
- `install.cljc` at repo root (curl-able): run with `jank run install.cljc`.
  Lays down/refreshes the checkout (git), runs dev/install/jank.sh,
  builds the core snapshot + launcher by spawning the same jank steps
  ensure! runs (emit needs a bb-free path — under investigation),
  AOT-compiles the front-end into the `cohost` binary, writes it to the
  bin dir, records ~/.config/cohost/install.edn.
- The front-end (dev/install/cohost_tool.cljc, compiled -O3): a Jank port
  of bin/cohost's front-end — arg parsing, target selection, a tools.deps
  subset resolver (paths / :local/root / git deps / maven jars, aliases'
  argmap; org.clojure/clojure stripped as today), config writing, host
  launches. `-Ttools install|install-latest` implemented natively (git
  and maven lib recording in the real CLI's format). `:jvm` and other
  `-T` delegate to the real CLI only when it is present; absent, they
  name the gap instead of failing obscurely.

NEXT: probes — (1) raw jank reads .cljc + clojure.edn; (2) read
dev/perf/jank_aot_ensure.clj + jank_snapshot_emit.clj fs usage (the
bb-free emit path); (3) reference probe of the real CLI's -Ttools
install-latest: exactly what files it writes (scratch CLJ_CONFIG).

## Successor session pickup (07:18 ET, Nov 7 2026)

Predecessor died in a daemon restart after banking 597ac69 (pushed,
verified on the fetched ref). Picked up from its NEXT, no verified step
redone. Survey of the NEXT items, firsthand:

- dev/perf/jank_snapshot_emit.clj: babashka usage is ONLY babashka.fs
  (delete-tree, create-dirs, parent, path) + slurp/spit/read-string.
  Everything else is portable Clojure. A bb-free emit = same file with a
  small fs shim (path/parent are string ops; create-dirs/delete-tree via
  std::system or cpp std::filesystem).
- dev/perf/jank_snapshot_key.clj rebuild!: step 1 (record) is jank
  itself; step 2 is the ONLY bb call in the whole snapshot build
  (`bb dev/perf/jank_snapshot_emit.clj`); step 3 (compile) is jank.
  Its other bb surface is babashka.process/shell + babashka.fs, same
  shim class as dev/perf/jank_aot_ensure.clj (which is otherwise pure:
  shell out to `jank compile-module cohost.run -O3`).
- dev/perf/jank_inputs.clj: fs/glob + fs/components only — a directory
  walk (find via std::system, or cpp filesystem iterator).
- dev/perf/sha256.clj is JVM interop (java.security.MessageDigest) —
  does NOT run on jank. Replacement: sha256 via spawned
  sha256sum/shasum (std::system proven by the predecessor's raw-jank
  probe) with output captured through a temp file.
- Venue: this box rebooted (~07:13 ET) and is still hot (load 32/14/5
  at 07:17); jank is not on this box's PATH. All jank builds/proofs run
  on j.slaa.us under the heavy-queue slot `slot.cohostinstall`
  (queue was empty at check, 07:18 ET; J load ~5.2, jank +
  clojure at /usr/local/bin there).

NEXT: on J — claim slot; clone this branch to ~/cohost-install-jank;
(1) reference probe: real CLI `-Ttools install-latest` in a scratch
CLJ_CONFIG_DIR, record every file written; (2) raw-jank probes:
.cljc load + clojure.edn read, directory walk, sha256sum capture.

UPDATE (successor, ~13:00 ET): SUBSTRATE GREEN; TOOL + INSTALLER
WRITTEN; COLD BUILD IN FLIGHT.
- 30b47ab (verified on the fetched ref): fs shim, shim.cljc (probes
  green on J: sha256 byte-exact vs sha256sum, walk=41, env/self-exe/
  stat), jank_build.cljc (aot-ensure + snapshot-key ports).
- EMIT RUNS ON JANK (no bb): under jank + the babashka.fs shim, on a
  regenerated copy carrying six exact ASSERTED transforms (a silent
  non-match throws at build time; T1 goes by line after a
  backslash-count miss bit once): T1 phel lookbehind literal
  (phel-only path), T2 IObj guards x3 (with-meta throws on
  non-carriers = the guard), T3 Pattern instance?, T4 ^String hints,
  T5 bind only *print-meta*, T6 String.indexOf -> str/index-of.
  A/B vs bb emit on identical events: PLACEMENT IDENTICAL (857/872).
  Module bytes differ ONLY in (i) metadata spans — jank's pr-str
  ignores *print-meta* (probed firsthand) and (ii) >8-entry hash-map
  print order — jank's map hash differs from Clojure's. Both are
  standing upstream jank findings; byte parity is unachievable in
  principle on (ii), so the bar is functional (the gates through the
  jank-built tool). Owner: upstream jank, already on the release
  record; this lane adds the build-level evidence.
- The bb reference build in the J checkout COMPLETED meanwhile
  (SNAPSHOT-BUILT df88adac… in 791,536 ms; launcher present) — the
  tool can be proven against it immediately, and jank_build's fresh
  check against that bb-built snapshot doubles as a cross-flow key
  check.
- COLD BUILD: fresh clone of the branch on J (~/cohost-cold),
  jank_build ensure: AOT OK, record OK, emit OK (857/872), snapshot
  compile in flight at this bank.
- WRITTEN: dev/install/lib/cohost/tool.cljc (the `cohost` command:
  native -Ttools install/install-latest byte-modeled on the
  reference probe; default/:jank runs the snapshot via cfg EDN as
  bin/cohost does; other -T/:jvm/-Sdescribe delegate to a real
  clojure when present, else die naming the gap; other hosts die
  pointing at bin/cohost; deps = documented subset),
  tool_main.cljc (AOT entry), install.cljc at the repo root
  (SELF-CONTAINED installer).

NEXT: tool smoke against the bb-built snapshot in the J checkout
  (fresh check + run-main -e), then the cold build's launcher, then
  scratch-HOME install via install.cljc, then the no-clojure/no-bb
  PATH proof suite, then README/docs, then declaration.

UPDATE (successor, ~13:50 ET): EMIT BYTE-CLASS CLOSED; J DISK FULL;
BUILD MOVED LOCAL.
- T7 pr-meta (recursive meta printer for the jank emit copy) landed:
  placement back to 857/872; the defprotocol compile blocker is
  understood and fixed — defprotocol is :status :host in cohost's
  boot, so JANK's own defprotocol macro expands it and its expansion
  reads the sig forms' meta; meta-less modules fail with p0
  unresolved. pr-meta also drops jank's reader stamp
  (:jank/source {:module "user"}) which Clojure's reader never adds.
  Remaining module diffs vs bb emit, all classified: hash map/set
  print order (data-only), one double notation (1e+06 vs 1000000.0,
  same value). Snapshot key now sorts the whole input list -> key
  parity with the bb flow (df88adac...).
- Cold build #3 on J reached the launcher LINK and died:
  /usr/bin/ld: No space left on device — J's root is 96G/96G (38M
  free); the fleet's collective trees fill it; this lane's own
  footprint there is ~340M. Owner: build-box capacity (coordinator /
  operator), NOT this lane's making. The lane's slot.cohostinstall
  remains claimed on J.
- Fallback per install-what's-missing: the full build is running on
  THIS box (identical jank build cf627abb...; 6.6G free) in the lane
  worktree, target/local-build.log. The proof suite follows here;
  J can re-run it verbatim once space returns (proof.sh is written
  for J; a local variant runs the same steps).

NEXT: local build lands -> proof suite local (scratch HOME, PATH
  without clojure/bb: installer, -Ttools line vs the reference
  records, -e, -Spath, batteries 221+62) -> final STATUS +
  declaration; release the J slot at declaration.

## Update — Nov 7 ~15:00 ET: -Ttools at byte parity; battery 219+62

(Supersedes the fallback note above: J's disk was freed enough by
deleting this lane's own regenerable trees; all builds ran on J.)

- Snapshot-key parity with the bb flow proven (df88adac…); the cold
  build's launcher was linked entirely by the jank flow (121,490,968
  bytes); jank_build's fresh verdict: FRESH under the original's own
  rules. Tool AOT binary built by jank alone (70,624,600 bytes).
  Installer end-to-end green (INSTALLER-EXIT=0, tool installed into a
  scratch HOME, install.edn written).
- **The `-Ttools install-latest` line is at BYTE PARITY with the real
  Clojure CLI** (fresh scratch HOME): record `{:lib
  io.github.seancorfield/deps-new, :coord {:git/tag "v0.17.0",
  :git/sha "657fca6"}}`, checkout dir `657fca6c33cd…`, self-record,
  and the re-run prints exactly `new: Skipping, newest installed
  io.github.seancorfield/deps-new v0.17.0` — all identical to the
  reference probe. Defects found+fixed on the way: (1) jank regex
  literals are verbatim — `\\.` in a file is backslash+any-char, not
  an escaped dot (slug and .git-strip patterns never matched);
  tool.cljc is jank-only so its patterns use jank's dialect.
  (2) GitHub slug io.github.<user>/<repo> -> <user>/<repo>; mirror
  layout <scheme>/<host>/<owner>/<repo>. (3) Tag choice per
  tools.tools' own docstring: semver order, special-string versions
  (alpha a beta b milestone m rc cr snapshot) filtered, last wins;
  version keys zero-padded (Clojure compares vectors by length
  first — unpadded, v0.5.2.1 beat v0.17.0). (4) Mirror detection is
  directory existence (a --mirror clone IS the git dir).
- **Batteries on this lane's snapshot: libraries 62/62, core
  219/221.** Both failures are the \u-escape cases; via --eval the
  first case's `(count "h\u00e9llo")` returns 6 (jank's byte count)
  where 5 is expected. A control build of the same tree with the
  original bb emit is running on J to isolate emit-path vs
  tree/env. The JIT source boot shares part of the anomaly (its
  reader rejects a raw é in an eval'd source).
- Installer PATH-hint spacing fixed.

NEXT: read the bb control build's battery; then the final proof.sh
run on a rebuilt jank-only snapshot; commit all outstanding fixes
from J; final declaration; release the slot.

NOTE (successor session, Nov 7 ~09:54 ET): two-writer guard tripped — this tree has a LIVE writer (files touched within the last 15 min, a J-monitoring loop running, large uncommitted diff in flight); the new successor session STANDS DOWN and has written nothing else.

## CLOSE — Nov 7 ~15:25 ET: the proof is green end to end; lane complete

**The final proof (one run, J, target/proof-final.log on the cold
tree; script target/probe/proof.sh):** scratch HOME; PATH carrying
poisoned `clojure`/`bb` shims that log and exit 99 if ever invoked.
1. `jank run install.cljc -- --repo /home/john/cohost-cold` ->
   snapshot verified/built by jank alone, tool compiled by jank
   alone, `cohost` installed into the scratch HOME. Exit 0.
2. `cohost` resolves on the poisoned PATH.
3. `cohost -Ttools install-latest :lib io.github.seancorfield/deps-new
   :as new` -> `new: Installed io.github.seancorfield/deps-new v0.17.0
   {:git/tag "v0.17.0", :git/sha "657fca6"}`; records + gitlibs
   checkout byte-identical to the real Clojure CLI's (reference probe);
   re-run -> `new: Skipping, newest installed ... v0.17.0`.
4. In a scratch project: `cohost -e "(+ 1 2)"` -> `3`, exit 0;
   `cohost -Spath` -> the project's two roots; a script file runs.
5b. The poison log does not exist: clojure and bb were never invoked.
6. Batteries on the snapshot this build produced: **core 219/221,
   libraries 62/62** (see the routed finding below — the original bb
   flow scores identically on this tree).
   PROOF-EXIT=0.

**Run-path defects found and fixed in this closing stretch** (the
run path had never actually been exercised before): the tool now
writes bin/cohost's exact config map (:inits/:main from faithful
main-config/exec-config ports, :stock-root, :roots, :cwd, :snapshot?,
:module-paths — before, an invented shape sent cohost.cli into a
source boot that died slurping /clojure/core.clj); and exits call
shim/exit! directly (see F-JANK-DEFERRED-CPP-VAR below).

**Routed findings (not this lane's to fix):**
- F-BATTERY-U-ESCAPES (owner: the cohost twin/adapter line): the two
  \u-escape battery cases fail on this tree — `(count "h\u00e9llo")`
  comes back 6 (jank's byte count) where 5 is expected, and the
  battery's eval of the stored case sources errors. ISOLATED: the
  original bb flow rebuilt on the same tree scores the identical
  219/221 + 62/62 with the same two failures, and the JIT source boot
  shares the anomaly — it is a property of the tree/jank state, not
  of this lane's build path (placement parity 857/872 both ways;
  snapshot-key parity df88adac…). The release record's 221/221 was
  measured on the release branch state.
- F-JANK-DEFERRED-CPP-VAR (owner: upstream jank observation, repro
  banked in this lane's probes): a cpp-backed defn (body = one cpp
  call; a deferred_cpp_function) throws a nil-message ghost exception
  when invoked THROUGH a var, while the direct call works. Cost this
  lane a long bisect; the tool now calls shim/exit! directly. Joins
  the standing upstream-jank observation list (never filed, no patch).

**Named limits of the delivered tool (README/guide worded to match):**
-Ttools install-latest proven for git libs; -Ttools install with an
explicit coord and Maven tool installs follow the same code but are
unproven. Deps resolution is the documented subset; jar-only deps
are not loaded from. Per-project snapshots (D-8) are skipped (the
core snapshot serves every project). Other -T aliases, -Sdescribe
and :jvm delegate to a real Clojure CLI when present and name the
gap when absent; other hosts are bin/cohost's from a checkout.

**Deliverables:** install.cljc (self-contained CLJC installer on
jank, clone-or-curl), dev/install/ (shim + jank_build + the tool),
README + guide-01 install rewrite (Jank-only prerequisite; the
deps-new line exactly as the operator confirmed:
`cohost -Ttools install-latest :lib io.github.seancorfield/deps-new :as new`).
The heavy-queue slot.cohostinstall is released at this declaration.
J's root disk filled during this lane (fleet-collective; the lane
freed its own regenerable trees; ~11G free at close) — capacity is
the coordinator's/operator's line, not this lane's.

NEXT: none — lane complete. The branch is ready for the operator's
collation; main and the release branch were never touched.

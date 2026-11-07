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

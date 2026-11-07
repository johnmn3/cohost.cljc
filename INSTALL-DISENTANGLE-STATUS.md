# INSTALL DISENTANGLE — STATUS (Lane M, lane-m/cohost-disentangle)

Base: fetched remote tip of `lane-m/cohost-install-portable` =
92ad3e90cc3bf53ad4f0cdb9594fa48ed0bb7dc0 (ls-remote verified before
the branch was cut). Worktree ~/workspace/wt/cohost-disentangle.
Operator direction (Nov 7 2026, ~6:30 PM ET): (1) disentangle
snapshot-key parity from the emit driver; (2) get the snapshot build
off babashka.fs / babashka.process. Sequence per the brief: P1
analysis, then P2, then P1's prototype (P2's vendored emit is its
vehicle). Heavy work on J via heavy-queue, staged under /home/john/j
only (J root disk 100% full, verified ~6:22 PM ET).

## P1 ANALYSIS — what the snapshot key actually commits to (from sources)

Key construction (dev/perf/jank_snapshot_key.clj, `snapshot-key`):

    sha256hex( sha256sum-listing(sorted input files)
               + "jank " + jank-version + "\n" )

- Inputs (`input-files`, with dev/perf/jank_inputs.clj's
  `source-files`): every regular file under
  resources/cohost/clojure-1.12.0 and src/cohost (host cache files —
  __pycache__/*.pyc/*.lpyc — excluded), plus six named dev/perf
  scripts: jank_inputs.clj, jank_snapshot_record.jank,
  jank_snapshot_emit.clj, jank_snapshot_key.clj,
  jank_snapshot_ensure.clj, jank_snapshot_run.jank. The listing
  (dev/perf/sha256.clj) hashes each file's BYTES beside its
  repo-relative PATH STRING; the caller sorts the whole list by
  path string.
- `jank-version` is the stdout of `jank print-binary-version` — a
  property of the jank BINARY. Only when JANK_BIN names a non-PATH
  binary does the line also carry that binary's real path, size and
  mtime (X1-7: print-binary-version does not move when a patch
  changes the compiler). Still properties of the binary file, not
  of any driver.
- NOT in the key: the emitted modules' bytes; events.edn; any input
  file's mtime; the build driver's identity, version or runtime;
  hostname, user, or cwd (paths are repo-relative; every flow runs
  the build from the repo root). The stamp is written AFTER the
  build with the precomputed key, so the key is a pure function of
  the inputs, computed before any emit runs.

Where the emitting process's identity could enter — checked singly:

1. Emitted bytes: not keyed at all. Key parity across drivers is
   therefore guaranteed by construction; snapshot CONTENT
   equivalence across emitters is a separate question (the proofs
   answer it with an emitted-byte diff plus the battery).
2. Ordering: sorted by path string in two places; string sort is
   runtime-independent. A replacement glob must return the same
   SET of path strings (order is normalized by the sort).
3. Paths: glob results are repo-relative with "/" separators on
   every current driver on this platform.
4. Timestamps: none for inputs (see above).
5. The version line: produced by the jank binary itself; drivers
   only shell it.

Emit determinism (dev/perf/jank_snapshot_emit.clj): a pure function
of events.edn + the --host dialect. Output order derives from the
event sequence; sets are used for membership or printed sorted;
printing is bound (*print-length* nil, *print-level* nil,
*print-meta* true). The record step that produces events.edn runs
under the same jank binary on every seat. Cross-runtime
byte-identity of the emitted modules is plausible but unproven for
the clj/jolt emitters; the jank seat already emits through a
transformed copy under raw jank (dev/install/jank_build.cljc,
transforms T1–T7) and its snapshots are fresh to the bb flows —
behavioral evidence, not byte evidence. The proofs settle it
byte-wise.

CONCLUSION: the key ALREADY depends only on canonical content.
What couples the original build to bb is availability, not parity:
(a) `rebuild!` shells the literal "bb" for the emit step
(jank_snapshot_key.clj, in rebuild!) — a cold build of the original
chain needs bb on PATH even when driven by clj or jolt;
(b) the build files' babashka.fs/process requires (P2's subject).
jank_snapshot_key.clj is itself a KEYED input, so editing the "bb"
literal would change the key by design — the disentanglement
proceeds by proof + dependency removal around the frozen keyed
files. A PATH-level fake-`bb` launcher was considered and rejected:
it hides the dependency instead of removing it, and would silently
intercept any other bb use in the same process tree.

## P2 CENSUS — fs/process facilities the snapshot build calls

The install snapshot build chain (dev/perf/jank_snapshot_ensure.clj
→ jank_snapshot_key.clj → jank_inputs.clj + sha256.clj, rebuild! →
jank_aot_ensure.clj, emit = jank_snapshot_emit.clj):

| file | babashka.fs vars | babashka.process |
|---|---|---|
| jank_inputs.clj | components, glob, path, regular-file? | — |
| jank_snapshot_key.clj | absolutize, create-dirs, delete-if-exists, delete-tree, executable?, glob, last-modified-time, parent, path, real-path, regular-file?, size | shell |
| jank_aot_ensure.clj | absolutize, create-dirs, delete-tree, exists?, glob, last-modified-time, parent, path, real-path, regular-file?, size | shell |
| jank_snapshot_emit.clj | create-dirs, delete-tree, parent | — |

Union (the vendored slice): fs — absolutize, components,
create-dirs, delete-if-exists, delete-tree, executable?, exists?,
glob, last-modified-time, parent, path, real-path, regular-file?,
size (14); process — shell, in the shapes the call sites use:
(shell argv...) inherit + throw-on-nonzero, (shell {:out :string}
...), (shell {:out :string :in <string>} ...), (shell {:continue
true} argv...) → {:exit n}; return keys read: :out, :exit.

Adjacent, NOT in the install build chain: dev/perf/
jank_snapshot_cli.clj (the project-snapshot path; adds fs/file,
runs under bb only today) and dev/perf/lpy_snapshot_ensure.clj
(the Basilisp chain). Out of the vendoring scope; the vendored
namespaces would serve the cli unchanged if its process ever
resolved them.

## P2 DESIGN — vendored babashka.* namespaces, resolved by path

Mirror of the house precedent (dev/install/shim/babashka/fs.jank
gives the jank seat a babashka.fs by module-path precedence):

- NEW dev/perf/shim/babashka/fs.cljc — (ns babashka.fs), exactly
  the census slice, reader-conditional arms: :clj on
  java.nio.file + java.io (the string/path semantics copied from
  the proven jank shim where they exist: path, parent); :cljr on
  System.IO. No :bb/:jolt arm is ever loaded: bb resolves its
  built-in, jolt its bundled libraries — the shim directory is
  added only where the libraries are absent.
- NEW dev/perf/shim/babashka/process.cljc — (ns babashka.process),
  shell in the call-site shapes: :clj on java.lang.ProcessBuilder
  (direct argv, no sh -c — babashka.process/shell semantics),
  inherit by default, :out :string captures, :in string feeds
  stdin, throws ex-info on nonzero unless :continue; :cljr on
  System.Diagnostics.Process.
- install.cljc, clj arm ONLY (named change): the -Sdeps Maven pins
  {babashka/process 0.5.22, babashka/fs 0.5.24} are replaced by
  -Sdeps {:paths ["dev/perf/shim"]} — the ensure process resolves
  babashka.fs/process from the checkout itself. bb/jolt/jank arms
  byte-unchanged; the header comment updated to match.
- NO keyed file changes (see P1: any edit to a keyed file changes
  the key by design). The build files keep their
  (require '[babashka.fs ...]) forms untouched; only resolution
  changes.
- :cljr caveats banked up front: last-modified-time's :cljr arm
  returns a System.DateTime — the one call site reads .toMillis
  and sits on key.clj's JANK_BIN-only branch, so the default CLR
  path never calls it; size has a conforming :cljr arm
  (FileInfo.Length) on the same branch.

## PROOF PLAN (J, heavy-queue, /home/john/j staging)

A. Reference cold build, bb seat (unmodified flow) in a staging
   clone of this branch → key (expect df88adac…), battery via
   bb boot:jank (expect 219/221 + 62/62); save the emitted src
   tree + events.edn.
B. Jank-seat cold build (jank_build.cljc ensure) in the same clone
   after wiping target/ → key, battery; diff its emitted src tree
   against A's. [P1's two-emit-paths proof: bb emit vs jank emit.]
C. Standalone emit determinism: A's events.edn re-emitted under
   clj (vendored shim on the path) and under jolt into side dirs;
   diff -r against A's emitted tree. [P1, three more paths.]
D. Changed-seat cold build: full installer flow on the clj seat
   with the vendored shim (pins dropped) → key must equal
   df88adac…, battery 219/221 + 62/62. [P2(c): clj = changed seat,
   A = second seat.]
E. ClojureCLR attempt: Clojure.Main with the shim dir on
   CLOJURE_LOAD_PATH loads the ensure chain from the repo root;
   bank exactly where it stops. [P2(d).]

## Progress log

- Shim written: dev/perf/shim/babashka/fs.cljc + process.cljc
  (:clj + :cljr arms per the design above). One correction made
  while writing: top-level #?@ splicing is NOT allowed when a
  .cljc loads as a namespace ("Reader conditional splicing not
  allowed at the top level." from the clj reader, hit on the
  first smoke run) -- the arms are single (do ...) forms under
  #?. The jank-seat fs.jank shim never taught us this because it
  is a .jank file, not a .cljc namespace.
- LOCAL PROOF (hatch box, light): the reference key reproduces on
  this tree under bb + the real libraries:
      bb -e '(load-file "dev/perf/jank_snapshot_key.clj") (snapshot-key)'
      => df88adacda69c0e2d4ac53df68476d9d5b6ea40d7dbac44bb5d9c1bbc87bde39
  (so this box's `jank print-binary-version` matches J's -- the
  key is checkable locally at full strength), AND under the
  Clojure CLI with ONLY the vendored slice on the path:
      clojure -Sdeps '{:paths ["dev/perf/shim"]}' -M -e
        '(load-file "dev/perf/jank_snapshot_key.clj") (snapshot-key)'
      => df88adacda69c0e2d4ac53df68476d9d5b6ea40d7dbac44bb5d9c1bbc87bde39
  Identical. That one run exercises the shim's path/absolutize/
  parent (key.clj's load path), glob over both source trees +
  the brace-glob named scripts (the listing is the key), and
  shell {:out :string} (jank print-binary-version). The full
  cold-build proofs on J remain the acceptance bar; this is the
  key-path smoke, banked as such.

- install.cljc changed, clj arm ONLY (named change, per brief):
  the step-2 -Sdeps argument goes from
  {:deps {babashka/process 0.5.22, babashka/fs 0.5.24}} to
  {:paths ["dev/perf/shim"]}; header comment extended to say so.
  bb/jolt/jank arms byte-unchanged (git diff shows exactly the
  comment block + the one string). Parse checks after the edit:
  clj reader 19 forms OK; bb reader (vector wrap) 18 forms OK
  (the count differs by seat feature set, as before).

## State: shim + install.cljc change banked; J proofs next.

NEXT: heavy-queue claim on J, stage the clone under
/home/john/j, proofs A-E in order.

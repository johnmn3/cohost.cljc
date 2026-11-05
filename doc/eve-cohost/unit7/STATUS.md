# Unit 7 — tiered atoms (mem -> disk -> net) — STATUS

- Lane: agent1/eve-unit7-tiered-atoms, branched from muse/eve-cohost-impl @ 0fa021f (verified tip, 12:59 PM ET 2026-11-05)
- 12:59 PM ET — lane opened; reading MEMORY-PLAN.md unit-7 spec.
- 1:03 PM ET — **unit 5 is not in the mainline** (0fa021f carries units 2+3 only). Unit 5 is complete on
  `muse/eve-cohost-impl-u5` @ b142ffe (REPORT: probe 66/66, check 31/31, JVM 221/221 + 62/62, jank AOT
  219/221). Merged into THIS LANE only (bb68557; conflicts were doc-strings, the shim require, and
  boot's `:host-lacks` line = unit 2's `core-lacks` ∪ unit 5's atom-surface names). No muse/* branch touched.
- 1:03 PM ET — installs: jank `0.1-noble` (dev/install/jank.sh, apt), Clojure CLI 1.12.6.1673 (official
  linux-install.sh, sha256-pinned tarball), babashka v1.13.229 (official installer). All exit 0.
- 1:03 PM ET — Eve sources: SeniorCareMarket/eve main @ d241de3 (eve 0.9.1, adapter architecture; **no
  :jank host**). Eve-on-jank lives in the vendored line `johnmn3/atom.broker` @ `claude/eve-jank`
  7ae05d49 (attached read-only): `:jank` host row, region/jank (MAP_SHARED + __atomic + futex),
  native slab-io/typed-view flipped in, and `bench/bench/atom_bench.jank` driving
  `eve.atom/persistent-atom-domain` (file-backed mmap atoms) on jank. Flagship gate NOT declared
  (perf rows moved to VS Agent 2 at 13:02 ET; realm-signedness defect open on jank — affects TAG-REF
  references, not plain data). Decision: the two-process gate uses ONE Eve tree for both seats
  (atom.broker/eve @ 7ae05d49, its `:jvm` and `:jank` roots), so the file format is one format.
- 1:09 PM ET — **baseline on the one Eve tree** (atom.broker/eve @ 7ae05d49, `:jvm` roots; runner
  `dev/u7/clj-eve.sh`): unit 3/5's `memory_eve.clj` referenced `eve.mem/value+sio->eve-bytes`, which
  the adapter-architecture line moved to `eve.codec`; resolved from whichever line is present (one
  def, no behavior change). Unit 5's `probe_atom.clj` on this line: **PROBE-ATOM: 66 checks, 3
  failures** — all three in the "transition to host seat" rows: this Eve line no longer refuses a fn
  root (it holds it in-process by reference, realm-scoped), so the layer's host-seat transition never
  fires; deref identity still exact (PASS "transitioned root identical"). Eve-line delta, recorded;
  unit 7 does not rely on Eve's refusal (the tier checks representability itself).
- 1:09 PM ET — IMPLEMENTATION in progress (committed as built):
  - `cohost.atom`: kind from meta `:eve/kind` (:mem default | :disk {:eve/path :eve/name} | :net
    {:eve/endpoint :eve/domain-id :eve/name :eve/api-key}); disk/net roots must be representable —
    loud `:unrepresentable` error naming the value at creation, publish and promotion, never the host
    seat; tiered publishes ride the Eve handle's own CAS loop (cross-process), watches fire with the
    committing attempt's pair; promotion by meta change, upward only (`:downgrade`, `:re-point`,
    `:promotion-target-occupied`, `:missing-place`, `:unknown-kind`, `:kind-unavailable` all throw).
  - `cohost.atom-eve`: `:make-tier-atom` — disk = `eve.atom/persistent-atom-domain` +
    `lookup-or-create-mmap-atom!` (OS-resolved join); net = `eve.net.atom/remote-atom` adapted to
    IDeref/IAtom (Eve's ops verbatim); `root-digests` = `eve.atom/seal` + Eve's SHA-256 content digest.
  - `probe_tier.clj`: the layer probe (running).
- 1:15 PM ET — results so far (as printed):
  - `probe_tier.clj` (layer, JVM, one process): **PROBE-TIER: 55 checks, 0 failures** (before the
    `:fresh` change below; re-running). Eve itself also refuses a reference into a shared domain:
    "eve: a value with no eve bytes (a fn, a regex, an atom, a host object, ...) is held by reference,
    and a reference never reaches a shared domain".
  - `dev/u7/convey.clj`, two JVM processes in sequence on one disk atom: A `{:future-saw ["acme"
    :a-side]}`; B `{:future-saw ["acme" :plain], :own-binding-wins "b-own"}`; refusal of an
    unrepresentable conveyed binding `:unrepresentable`.
  - booted check `eve_cohost_check_u7.clj` first run: 17 checks, 7 failures — two causes, both fixed:
    (1) stock core's `(atom x :meta m)` is `(new Atom x)` then `reset-meta!`, so a kind arrives as a
    promotion of a fresh atom → added `:fresh` (never published) so that path is create-or-join;
    (2) layer errors carried `:cohost/class`, which the booted `ex-data` treats as a shim stand-in →
    plain ex-info now. Re-running.
  - jank side: first run created the domain files then sat in a futex wait with nothing printed
    (stdout buffered); killed; re-running with flushed stage markers.
- 1:18 PM ET — results (as printed):
  - booted `eve_cohost_check_u7.clj` re-run: **CHECK-U7: 17 checks, 0 failures** (boot: 501 stock /
    172 host / 61 natives / 88 skipped / errors 0).
  - `probe_tier.clj` re-run (+ fresh-join row): **PROBE-TIER: 56 checks, 0 failures**.
  - net kind across processes (`dev/u7/net_proc.clj`, Eve's "over sockets" shape: store + gateway on
    eve.http-server, gateway a member of disk domain gwdb): a cohost `:net` atom in one JVM process
    (50 swaps) and a cohost `:disk` atom on the gateway's domain in another (50 swaps) →
    `{:disk 50, :net 50, :count 100}` read back by a fresh net client. The disk member's swaps
    landed before the client's started (its own view `{:disk 50, :net 0, :count 50}`): exact, not
    interleaved in this run.
  - jank gate side: joins the domain written by Eve-on-jank (`STAGE joined` after ~3 min of JIT),
    now in the first deref.
- 1:19 PM ET — jank stall DIAGNOSED (gdb, main thread): `u7.two-proc/plain` → `eve.core/into` →
  `clojure.core/into` (host target `{}`) → `jank::runtime::reduce` → `object::seq` →
  `object_source` → `jank::runtime::meta` → `lazy_meta::get` → folly SharedMutex
  `lockExclusiveImpl` → futex wait forever. Eve's jank.md defect 4 ("`(meta <opaque_box>)` never
  returns"). Not a domain/region problem: the join and the deref completed. Fix in the probe (no jank
  patch): read the Eve map through eve.core's protocols (`reduce-kv`, `count`, `nth`). Re-running.
  JVM battery (`bb boot:jvm`) started in parallel.
- 1:21 PM ET — results (as printed):
  - jank side `read` on a jank-created fresh domain: exit 0, `RESULT {:side :jank, :tag "read", :value
    {:jvm 0, :ready 0, :done 0, :jank 0, :marks [], :count 0}, :seal {:ptr 1, :name-hash -1780362285,
    :blocks 4, :digest 2037527603}, ...}` — the seal is byte-for-byte the JVM side's seal of its own
    fresh domain (`{:ptr 1, :name-hash -1780362285, :blocks 4, :digest 2037527603}`): one encoding on
    both seats. Content digest now computed explicitly on both seats (codec bytes under
    `*shared-write*` → `eve.crypto/sha256`); JVM fresh root: `sha256:46e9f79f…eadb32 over 147 bytes`.
  - **JVM battery (`bb boot:jvm`, default config, this lane's tree): booted as stock 465 / from host
    202 / natives 66 / skipped 58 / errors 0; battery: 221/221; libraries: 62/62.**
  - GATE launched: `dev/u7/gate.sh target/u7-gate 200` (race N=200/side, then kill/rejoin legs).
- 1:25 PM ET — **GATE run 1 (race leg), as printed** (logs kept: target/u7-gate-run1, not
  committed): JVM `RESULT {:side :jvm, :tag "race", :kind :disk, :value {:jvm 200, :ready 2, :done 2,
  :jank 200, :marks [], :count 400}, :seal {:ptr 808, :name-hash -1780362285, :blocks 4, :digest
  -276256802}, :sha256 "sha256:24c28ddd…6743d2 over 147 bytes"}`; jank `RESULT {:side :jank, :tag
  "race", :value {:jvm 200, :ready 2, :done 2, :jank 200, :marks [], :count 400}, :seal {:ptr 808,
  :name-hash -1780362285, :blocks 4, :digest -276256802}, :sha256 "ERROR … eve: a value with no eve
  bytes … is held by reference … {:value-type \"eve.map/EveHashMap\"}"}`. Final value exact and equal;
  **seal identical from both processes**. The jank content-digest row errored: Eve-on-jank's codec
  takes its own EveHashMap as a reference (Eve-on-jank gap, named). Both sides now hash the
  materialized value's canonical bytes (JVM also prints the Eve map's own as a cross-check). Run 1
  stopped before its restart legs; run 2 launched clean. (Earlier attempt 0 died at jank compile on
  my `catch std.exception` arm — my error, fixed.)
- 1:32 PM ET — **GATE run 2 GREEN: `GATE-U7: 9 rows, 0 failures`** (logs banked in
  doc/eve-cohost/unit7/gate-logs/; 13:25:01–13:32:01 ET). Race N=200/side: final `{:count 400, :jvm 200,
  :jank 200}` equal from both; seal identical `{:ptr 808, :name-hash -1780362285, :blocks 4, :digest
  -2079092502}`; content digest identical `sha256:24c28ddd…6743d2 over 147 bytes` (= the JVM Eve map's
  own). Restart: JVM mark+halt (no close) → fresh jank rejoin sees `["jvm-killed"]`; jank mark+_exit →
  fresh JVM rejoin sees `["jvm-killed" "jank-killed"]`; final JVM vs jank: seal `{:ptr 819, :name-hash
  -1780362285, :blocks 6, :digest -264389891}` and `sha256:fad590ea…a2715d over 184 bytes` identical.
  The verdict's first tally said "1 failures" with all 9 rows OK (a truthy map counted as a failure;
  counter fixed, verdict re-run over the same logs). Next: jank AOT battery (`bb boot:jank`).
- 1:34 PM ET — regressions on this tree, as printed: unit 1 `CHECKS: 21 / 21 passed`; unit 3
  `111/111 PASS`; unit 5 `CHECK-U5: 31 checks, 0 failures`; unit 5 `PROBE-ATOM: 66 checks, 3
  failures` (the same three Eve-line rows as the pre-change baseline). Net run's gateway:
  `{:requests 273, :commits 50, :conflicts 1, :by-atom {"app/shared" 108}}`. REPORT drafted.
  Jank AOT battery compiling (`AOT-RECOMPILE`; snapshot stale → AOT modules path).
- 1:45 PM ET — jank AOT battery (`bb boot:jank`, AOT recompiled with unit-7 code, exit 0): booted as
  stock 544 / from host 141 / natives 28 / skipped 108 / errors 0; battery 219/221 (FAIL
  `Character/isWhitespace is Java's`, FAIL `Character outside ASCII`); libraries 62/62. Same command on
  the pre-unit-7 tree bb68557 on this box: **identical** (counts and FAIL pair) → no unit-7 change; the
  pair differs from J's standing pair at the box level (non-ASCII character rows).
- 1:46 PM ET — **COMPLETE.** REPORT at doc/eve-cohost/unit7/REPORT.md. Landing is the operator's call.

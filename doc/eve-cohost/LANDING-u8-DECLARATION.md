# Landing declaration — unit 8 (shared-heap illusion on cljs)

Collation lane, 2026-11-05 ET. Operator-ruled ~14:49 ET ("Land it").

**Base:** `muse/eve-cohost-impl` @ 71fd930 (units 1, 2 all seats, 3, 5, 7, 9).
**Landed:** `agent1/eve-unit8-cljs-shared-heap` @ 5a50853.
**Merge commit:** b744ad2. **Final mainline tip:** see the last commit on top of
this file's commit (tip verified on the fetched remote ref at declaration time;
the tip hash is in the landing STATUS file's final entry).

## Conflicts

One conflict, `src/cohost/boot.cljc`, one region: the `:host-lacks` expression.
HEAD (mainline, from the u7 landing): `(into (set host-lacks) (concat
(host/core-lacks) (when eve-atoms atom-surface-names)))`. Branch (inherited from
unit 7's line): the same union as `(cond-> (into (set host-lacks)
(host/core-lacks)) eve-atoms (into atom-surface-names))`. The two are the same
set in two formulations; the mainline formulation was kept, identical behavior
— the same resolution as LANDING-u7-u9's landing 1.

Everything else auto-merged and was verified as a union, nothing dropped:
- boot.cljc carries `install-thread-layer!` WITH unit 8's realm-submit block
  (cohost.realm `future-form-call` interned when the seat has `:realm-submit`),
  plus `install-atom-layer!` and `install-http-layer!`, all three wired in
  `boot!` and in snapshot replay.
- shim.cljc requires carry both sides: `[cohost.atom :as atom-layer]`,
  `[cohost.memory :as memory]`, `[cohost.thread :as thread]`,
  `[cohost.http :as http]`.
- Paren checks: boot 144 forms, shim 971 forms, thread 92 forms — all OK.

## Gates on the merged tree (as printed; log: gates-u8.log)

- `bb boot:jvm` — battery: **221/221** stock behaviors pass; libraries:
  **62/62** cases pass across 12 clojure.* libraries; rc=0.
- `bb cli:check cljs` — **7/7 SAME**; rc=0.
- `bb boot:cljs` — rc=0; battery 219/221, libraries 62/62; FAIL rows exactly
  the pristine pair: `numeric tower`; `defmacro &form &env`.
- Unit 8 spot-check, `bb dev/eve_probe.clj jvm cljs` (probe program unchanged):
  `SUMMARY|jvm|ok=23 unsupported-shim=0 fail-other=0`;
  `SUMMARY|cljs|ok=23 unsupported-shim=0 fail-other=0`; probe rc=0.
  (cljs wall 56,872 ms on this loaded 8 GB box vs 24,011 ms in unit 8's own
  run — counts identical.)
- Conflict-marker sweep on the committed merge tree (b744ad2): line-anchored
  `<<<<<<<` / `>>>>>>>` / `=======` over the whole tree, no extension filter:
  **0/0/0** (base 71fd930 also 0 line-anchored hits).

## Final state

The mainline carries units 1, 2 (all seats), 3, 5, 7, 8, 9. No unit code was
changed beyond the conflict resolution above; no force-pushes; every push
verified on the fetched remote ref.

# upstream/jank: the fleet's patches against jank-lang/jank

Pinned base: jank-lang/jank `f3d82d65499920f81216e9235bd5d3a34a968e3f` (2026-11-01). The installed
apt package `0.1-noble` (`jank compiler jank-0.1-alpha`) ships headers and `core.jank` byte-identical
to that commit; the compiler's `.cpp` files are only in the clone.

- `patches/series`: the patches in apply order (`git apply` from the clone's root), one per draft
  in `doc/upstream/jank-11..21`.
- `dev/install/jank-src.sh` (lane U0): builds the pinned sha with the series applied into
  `/usr/local/lib/jank-dev`; `JANK_BIN` points cohost's jank tasks and `dev/research` runners at it.

Each patch file starts with a header naming the draft, the files, and the repro command whose
"Expected" it makes true.

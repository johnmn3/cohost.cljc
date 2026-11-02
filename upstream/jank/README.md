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

## The source build (lane U0, U0-1, 2026-11-02)

`/usr/local/lib/jank-dev/bin/jank` is jank-lang/jank @ `f3d82d65` with the series applied, a Release
build. Use it with `JANK_BIN=/usr/local/lib/jank-dev/bin/jank` on every cohost jank command: `bb
boot:jank`, `bb boot:jank-jit`, the profiles, `bb bench:jank`, `bb bench:programs jank|jank-jit|jank-aot`,
`dev/research/run-jank.sh`, `run-cohost-jank.sh`, `upstream/run.sh`. Unset, they run `jank` on PATH.
A `JANK_BIN` binary gets its own AOT cache roots (`target/jank-aot-<12 hex of sha256(realpath)>`, and
the same suffix on `target/bench-programs/aot-*`), and its stamp carries the binary's path, size and
mtime, so a rebuild recompiles cohost's objects and the apt and source binaries never share them.

### Which LLVM, and why

jank needs Clang/LLVM 23 from jank-lang/llvm-project @ `e280c5056ed110a11411413da2b76da226f3113b`
(`compiler+runtime/bin/build-clang`). The apt package `jank 0.1-noble` already ships exactly that
build (`/usr/local/lib/jank/0.1/include/llvm/Support/VCSRevision.h`: `LLVM_REVISION e280c505...`;
`llvm-config.h` has only the version, 23.1.2; `clang-23 --version` names the sha too): `clang-23`, `libLLVM.so.23.1`, `libclang-cpp.so.23.1`, every `llvm/` and `clang/` header,
libc++ headers and the clang resource dir. It lacks the CMake package (`LLVMConfig.cmake`,
`ClangConfig.cmake`, `AddLLVM.cmake`, the exports), which jank's `cmake/llvm.cmake` and CppInterOp's
`add_llvm_library` need. So the script does not build LLVM (bin/build-clang: "an hour or two" per
jank's doc/build.md, more on 4 CPUs); it:

1. copies the package's LLVM-origin files into `/opt/jank-llvm/e280c505` (347 MB);
2. clones llvm-project at that sha (`--filter=blob:none --depth 1`: 1m47s, 2.9 GB checkout), runs
   its CMake configure only with bin/build-clang's options (12 s, no compile), and installs the
   `cmake-exports` and `clang-cmake-exports` components into the prefix;
3. stubs the 313 files those exports name that the package lacks (static component libraries as
   empty archives, tools as failing scripts, `libLTO`/`libRemarks`/`libclang` as empty shared libs;
   `llvm-ar`, `-nm`, `-ranlib`, `-objcopy`, `-objdump`, `-readelf`, `-strip`, `-addr2line` link to GNU
   binutils, because CMake takes `CMAKE_AR` from the compiler's directory). jank links only
   `libLLVM.so` and `libclang-cpp.so`; no stub reaches a link (`ldd jank`: those two). The list is
   `/opt/jank-llvm/e280c505/STUBS.txt`.

`--llvm-from-source` takes the other path (bin/build-clang, then `llvm_dir` = its install).

### Commands (what `bash dev/install/jank-src.sh` runs)

```
# deps (book: compiler+runtime/doc/build.md; on Ubuntu 24.04 only these were missing):
apt-get install -y entr libdouble-conversion-dev ccache
# (already present: cmake 3.28.3, ninja 1.11.1, libboost-all-dev 1.83, libssl/ffi/bz2/zstd/xml2-dev,
#  git-lfs, build-essential; libstdc++ 14 from gcc-14 was enough, no PPA)
git clone https://github.com/jank-lang/jank /usr/local/src/jank && cd /usr/local/src/jank
git checkout f3d82d65499920f81216e9235bd5d3a34a968e3f
git submodule update --init --recursive --depth 1
# the series, applied to the pinned tree (only files whose content changes are rewritten)
cd compiler+runtime
CC=/opt/jank-llvm/e280c505/bin/clang CXX=/opt/jank-llvm/e280c505/bin/clang++ ./bin/configure -GNinja \
  -DCMAKE_BUILD_TYPE=Release -Dllvm_dir=/opt/jank-llvm/e280c505 -Djank_local_clang=on \
  -DCMAKE_INSTALL_PREFIX=/usr/local/lib/jank-dev \
  -DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache
LC_ALL=C.UTF-8 cmake --build build -j4
cmake --install build
```

Which apt builds carry the sha where: both boxes measured so far (int-a's and int-b's, `jank
0.1-noble`) have it in `VCSRevision.h` and not in `llvm-config.h`; the script checks `VCSRevision.h`,
then `llvm-config.h`, then `clang-23 --version`. (The first version of the script, c85e131, checked
only `llvm-config.h` and refused both boxes; it was not caught here because this box's LLVM prefix
already existed when the script first ran.)

The installed binary needs no `LD_LIBRARY_PATH`: the configure passes `-Dllvm_dir` with
`-Djank_local_clang=on`, so `cmake --install` copies clang-23, libLLVM and libclang-cpp into
`/usr/local/lib/jank-dev/lib/jank/0.1/` and the binary's RUNPATH is `$ORIGIN/../lib/jank/0.1/lib`
(`readelf -d`; `env -i ... ldd` resolves both there). A hand configure with `jank_local_clang=on` and
no `llvm_dir` makes the install look for `build/llvm-install` and fail (u1); with
`-Djank_install_local_clang=OFF` it installs but then needs `LD_LIBRARY_PATH=/opt/jank-llvm/e280c505/lib`.

`LC_ALL=C.UTF-8` is required for the build, and wanted when running jank (apt or source) on any
source with non-ASCII string literals: jank_init calls `setlocale(LC_CTYPE, ".UTF8")`
(`src/cpp/jank/c_api.cpp:1102`, u1's finding), which glibc does not accept, so the lexer's
`mbrtowc` runs in the POSIX locale when LANG/LC_ALL are unset. The cohost runners do not set it
(that would change the apt baseline's conditions too); export it in the shell. Under the POSIX
locale jank's lexer (the apt binary too) reads
`"…"` as "Unterminated string", and phase 2 fails compiling jank's own
`src/jank/jank/nrepl/server/util.jank`. Repro on any jank: `printf '(println "a…b")\n' > u.jank;
LC_ALL=POSIX jank run u.jank`.

### Prices (this box: 4 CPUs, 15 GB, Ubuntu 24.04; ccache cold)

| step | wall | disk |
|---|---:|---:|
| jank clone + submodules | (not timed separately) | 213 MB |
| llvm-project clone at e280c505 | 1m47s | 2.9 GB |
| LLVM configure only + export install | 12 s | 37 MB build dir |
| LLVM prefix (copied from apt + exports + stubs) | seconds | 347 MB |
| jank configure | 9 s | |
| jank build, 429 steps, `-j4` | 204 s (160 s to the locale failure, 44 s after) | 377 MB build dir |
| jank install | 2 s | 449 MB (`/usr/local/lib/jank-dev`) |
| `jank-src.sh --patches`, empty series (no-op) | 8 s | |

### Check (U0-1 done-bar, both binaries, same box)

- `bb boot:jank` and `JANK_BIN=/usr/local/lib/jank-dev/bin/jank bb boot:jank`: `booted as stock: 542
  from host: 171 skipped: 78 errors: 0`, `battery: 143/145`, `libraries: 61/61`, identical output
  but for the source build's first-run "Building pre-compiled header" notes (196 s / 204 s with the AOT
  recompile).
- `bash dev/research/upstream/run.sh 12-i32-add`, with and without `JANK_BIN`: byte-identical output.
- `jank print-binary-version`: apt `...-cf627abb...`, source `...-8c51ef51...`.

## Re-building with patches (U0-2)

```
JANK_BUILD_LOCK=/home/user/locks/box.lock bash dev/install/jank-src.sh --patches
# measure a patch before it joins the series:
JANK_SERIES=/path/to/my/series bash dev/install/jank-src.sh --patches
```

`--patches` builds the series onto the pinned tree in a scratch git index (`git apply --cached`,
patch by patch, in `series` order) and rewrites in the clone only the files whose content differs
from that tree (removing a patch restores its files; a patch's new files are deleted), so unchanged
files keep their mtimes and ninja rebuilds only what the series changed; then `cmake --install`
(installs only changed files, ~2 s). It prints the compile time and the lock wait separately.

Price of a one-line change to a runtime `.cpp` (`src/cpp/jank/runtime/obj/nil.cpp`, a code line, not
a comment), from `.ninja_log`, 24 steps: 26.8 s of compile applying it, 26.5 s reverting it, on this box:

| step | s |
|---|---:|
| the changed `.cpp` (0.19, with jank's precompiled header) + `libjank.a` + phase-1 runtime archive + link `jank-phase-1` | 1.5 |
| `incremental.pch` (phase 2's precompiled header) | 4.9-5.0 |
| `classes/core-libraries`: phase 1 re-compiles clojure.core, clojure.string, clojure.walk and the nrepl server to C++ | 14.9 |
| the 16 phase-2 objects from that C++ | 0.01 each (ccache hits: the generated C++ is unchanged), except `clojure/string.cpp.o` 3.9-4.0 |
| link `jank`, the two runtime archives | 1.2 |

Every change to phase 1 (any compiler or runtime `.cpp`) re-runs `classes/core-libraries`: that
15 s is the floor of an incremental build. A change that also changes the C++ jank generates for
clojure.core (codegen, inlined runtime headers) misses ccache on `core.cpp.o` too. Wall clock adds
the lock wait on a shared box: the first priced run measured 131 s wall and the revert 95 s, of
which all but about 27 s each was waiting on `box.lock` for u1/u5 timing runs.

ccache: `CMAKE_*_COMPILER_LAUNCHER=ccache`; about half of the calls are uncacheable (the
precompiled-header compiles), the rest hit once the generated C++ repeats.

#!/usr/bin/env bash
# jank from source: jank-lang/jank @ the pinned sha, with upstream/jank/patches/series
# applied, installed into /usr/local/lib/jank-dev (bin/jank there). Point cohost at it with
# JANK_BIN=/usr/local/lib/jank-dev/bin/jank (bb boot:jank, bench:programs, dev/research).
#
#   bash dev/install/jank-src.sh            first build: deps, LLVM prefix, clone, series, configure, build, install
#   bash dev/install/jank-src.sh --patches  re-apply the series on the clone and rebuild only what changed
#   bash dev/install/jank-src.sh --llvm-from-source   build jank's LLVM snapshot (bin/build-clang) instead of
#                                           taking it from the apt package (hours on 4 CPUs; see upstream/jank/README.md)
#
# Run from cohost's root. Needs root (apt, /opt, /usr/local). Env knobs:
#   JANK_SRC          the jank clone (default /usr/local/src/jank)
#   JANK_DEV_PREFIX   install prefix (default /usr/local/lib/jank-dev)
#   JANK_LLVM         the LLVM prefix jank builds against (default /opt/jank-llvm/e280c505)
#   JANK_BUILD_LOCK   a file to flock around the compile (e.g. /home/user/locks/box.lock on a shared box)
#   JOBS              ninja -j (default nproc)
#
# The LLVM: jank needs Clang/LLVM 23 from jank-lang/llvm-project @ e280c505, the one its apt package
# ships (/usr/local/lib/jank/0.1: clang-23, libLLVM.so.23.1, libclang-cpp.so.23.1, every llvm/ and
# clang/ header, its llvm-config.h says LLVM_REVISION e280c505). The package has no CMake configs
# (LLVMConfig.cmake, ClangConfig.cmake, AddLLVM.cmake), which jank and CppInterOp need. So: copy the
# LLVM-origin files out of the package into $JANK_LLVM, clone llvm-project at the same sha, run its
# CMake configure only (seconds, no compile) with bin/build-clang's options, install just the
# cmake-exports and clang-cmake-exports components, and stub the files those exports name that the
# package lacks (static component libs, tools: jank links only libLLVM.so and libclang-cpp.so; the
# stubs are listed in $JANK_LLVM/STUBS.txt). Binutils the exports name (llvm-ar, -nm, -ranlib, ...)
# point at GNU binutils.
set -euo pipefail
# jank's lexer reads string literals by the C locale: under POSIX (a bare container) the "…" in
# jank's own src/jank/jank/nrepl/server/util.jank is "Unterminated string" and phase 2 fails.
export LC_ALL=C.UTF-8 LANG=C.UTF-8

JANK_SHA=f3d82d65499920f81216e9235bd5d3a34a968e3f
LLVM_SHA=e280c5056ed110a11411413da2b76da226f3113b
JANK_SRC="${JANK_SRC:-/usr/local/src/jank}"
JANK_DEV_PREFIX="${JANK_DEV_PREFIX:-/usr/local/lib/jank-dev}"
JANK_LLVM="${JANK_LLVM:-/opt/jank-llvm/e280c505}"
JOBS="${JOBS:-$(nproc)}"
APT_JANK=/usr/local/lib/jank/0.1

mode=full; llvm_src=0
for a in "$@"; do
  case "$a" in
    --patches) mode=patches ;;
    --llvm-from-source) llvm_src=1 ;;
    *) echo "unknown option: $a" >&2; exit 2 ;;
  esac
done

cohost="$(pwd)"
series="$cohost/upstream/jank/patches/series"
[ -f "$series" ] || { echo "run from cohost's root (no upstream/jank/patches/series)" >&2; exit 2; }
t0=$(date +%s)
stage() { echo "== jank-src: $* ($(( $(date +%s) - t0 ))s)"; }
locked() { if [ -n "${JANK_BUILD_LOCK:-}" ]; then flock "$JANK_BUILD_LOCK" "$@"; else "$@"; fi; }

# ---- dependencies (book: compiler+runtime/doc/build.md, Debian list; libstdc++ 14 is enough) ----
deps() {
  local need=()
  for p in curl git git-lfs build-essential entr libssl-dev libdouble-conversion-dev pkg-config ninja-build \
           cmake zlib1g-dev libffi-dev libbz2-dev libzstd-dev libxml2-dev libboost-all-dev ccache; do
    dpkg-query -W -f='${Status}' "$p" 2>/dev/null | grep -q 'ok installed' || need+=("$p")
  done
  if [ ${#need[@]} -gt 0 ]; then
    stage "apt-get install ${need[*]}"
    DEBIAN_FRONTEND=noninteractive apt-get install -y "${need[@]}"
  fi
  if [ ! -x "$APT_JANK/bin/clang-23" ]; then
    echo "the apt jank package is not installed: bash dev/install/jank.sh first (it provides the LLVM)" >&2
    exit 1
  fi
}

# ---- the LLVM prefix ----
llvm_prefix_from_apt() {
  [ -f "$JANK_LLVM/lib/cmake/llvm/LLVMConfig.cmake" ] && { stage "LLVM prefix present: $JANK_LLVM"; return; }
  grep -q "$LLVM_SHA" "$APT_JANK/include/llvm/Config/llvm-config.h" \
    || { echo "apt jank's LLVM is not $LLVM_SHA; use --llvm-from-source" >&2; exit 1; }
  stage "LLVM prefix from the apt package -> $JANK_LLVM"
  mkdir -p "$JANK_LLVM"/{bin,include,lib}
  cp -a "$APT_JANK/bin/." "$JANK_LLVM/bin/"
  for d in llvm llvm-c clang clang-c clang-tidy c++ x86_64-unknown-linux-gnu mach-o __libunwind_config.h \
           libunwind.h libunwind.modulemap unwind.h unwind_arm_ehabi.h unwind_itanium.h; do
    cp -a "$APT_JANK/include/$d" "$JANK_LLVM/include/"
  done
  cp -a "$APT_JANK"/lib/libLLVM.so* "$APT_JANK"/lib/libclang-cpp.so* "$JANK_LLVM/lib/"
  cp -a "$APT_JANK/lib/clang" "$JANK_LLVM/lib/"

  local ls="$JANK_LLVM.src"
  if [ ! -d "$ls/llvm" ]; then
    stage "clone jank-lang/llvm-project @ $LLVM_SHA (blob:none, depth 1)"
    git clone -q --no-checkout --filter=blob:none --depth 1 https://github.com/jank-lang/llvm-project.git "$ls"
    git -C "$ls" fetch -q origin "$LLVM_SHA" --depth 1
    git -C "$ls" checkout -q "$LLVM_SHA"
  fi
  stage "configure LLVM (CMake only, bin/build-clang's options) for its CMake exports"
  CC="$JANK_LLVM/bin/clang" CXX="$JANK_LLVM/bin/clang++" cmake -S "$ls/llvm" -B "$ls.build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$JANK_LLVM" \
    -DLLVM_ENABLE_RUNTIMES="libcxx;libcxxabi;libunwind;compiler-rt" -DLLVM_BUILD_LLVM_DYLIB=ON -DLLVM_LINK_LLVM_DYLIB=ON \
    -DLLVM_ENABLE_PROJECTS="clang;clang-tools-extra" -DLLVM_TARGETS_TO_BUILD=host -DLLVM_ENABLE_EH=ON -DLLVM_ENABLE_RTTI=ON \
    -DLLVM_INCLUDE_BENCHMARKS=OFF -DLLVM_ENABLE_BINDINGS=OFF -DLLVM_INCLUDE_EXAMPLES=OFF -DLLVM_INCLUDE_TESTS=OFF \
    -DLLVM_ENABLE_ZSTD=OFF -DLLVM_BUILD_OCAML_BINDINGS=OFF -DLLVM_ENABLE_OCAMLDOC=OFF > "$JANK_LLVM/llvm-configure.log"
  cmake --install "$ls.build" --component cmake-exports > /dev/null
  cmake --install "$ls.build" --component clang-cmake-exports > /dev/null

  stage "stub the imported files the apt package lacks"
  : > "$JANK_LLVM/STUBS.txt"
  cat "$JANK_LLVM/lib/cmake/llvm/LLVMExports-release.cmake" "$JANK_LLVM/lib/cmake/clang/ClangTargets-release.cmake" \
    | grep -oE '\$\{_IMPORT_PREFIX\}/[^ ")]+' | sed 's#^${_IMPORT_PREFIX}/##' | sort -u \
    | while read -r f; do
        p="$JANK_LLVM/$f"
        [ -e "$p" ] && continue
        mkdir -p "$(dirname "$p")"
        case "$f" in
          bin/llvm-ar|bin/llvm-nm|bin/llvm-objcopy|bin/llvm-objdump|bin/llvm-ranlib|bin/llvm-readelf|bin/llvm-strip|bin/llvm-addr2line)
            ln -s "/usr/bin/${f#bin/llvm-}" "$p"; echo "$f -> /usr/bin/${f#bin/llvm-}" >> "$JANK_LLVM/STUBS.txt" ;;
          *.a) ar rc "$p"; echo "$f (empty archive)" >> "$JANK_LLVM/STUBS.txt" ;;
          *.so*) "$JANK_LLVM/bin/clang" -shared -o "$p" -x c /dev/null; echo "$f (empty shared lib)" >> "$JANK_LLVM/STUBS.txt" ;;
          *) printf '#!/bin/sh\necho "%s: not in the apt LLVM (dev/install/jank-src.sh stub)" >&2; exit 1\n' "$f" > "$p"
             chmod +x "$p"; echo "$f (failing script)" >> "$JANK_LLVM/STUBS.txt" ;;
        esac
      done
  stage "LLVM prefix ready ($(wc -l < "$JANK_LLVM/STUBS.txt") stubs)"
}

# ---- the jank clone ----
clone() {
  [ -d "$JANK_SRC/.git" ] && return
  stage "clone jank-lang/jank @ $JANK_SHA with submodules -> $JANK_SRC"
  mkdir -p "$(dirname "$JANK_SRC")"
  git clone -q https://github.com/jank-lang/jank "$JANK_SRC"
  git -C "$JANK_SRC" checkout -q "$JANK_SHA"
  git -C "$JANK_SRC" submodule update -q --init --recursive --depth 1
}

# The series applied to the pinned sha, as a tree, then written into the clone touching only the
# files whose content differs from what is there: unchanged files keep their mtimes, so ninja
# rebuilds only what a patch (or a patch's removal) changed.
apply_series() {
  local idx; idx="$(mktemp)"; rm -f "$idx"
  local n=0
  (
    export GIT_INDEX_FILE="$idx"
    cd "$JANK_SRC"
    git read-tree "$JANK_SHA"
    while read -r p; do
      p="${p%%#*}"; p="$(echo "$p" | xargs)"; [ -z "$p" ] && continue
      git apply --cached "$cohost/upstream/jank/patches/$p" || { echo "patch does not apply: $p" >&2; exit 1; }
      echo "applied $p" >&2
    done < "$series"
    git write-tree > "$idx.tree"
  )
  local tree; tree="$(cat "$idx.tree")"; rm -f "$idx" "$idx.tree"
  cd "$JANK_SRC"
  local changed; changed="$(git diff --ignore-submodules --name-only "$tree" || true)"
  for f in $changed; do
    if git cat-file -e "$tree:$f" 2>/dev/null; then git checkout -q "$tree" -- "$f"; else rm -f -- "$f"; fi
    n=$((n + 1))
  done
  git read-tree "$tree"
  cd "$cohost"
  stage "series applied: tree $tree, $n file(s) rewritten"
}

configure() {
  local b="$JANK_SRC/compiler+runtime/build"
  if [ -f "$b/CMakeCache.txt" ] && grep -q "^llvm_dir:.*=$JANK_LLVM\$" "$b/CMakeCache.txt" \
     && grep -q "^CMAKE_INSTALL_PREFIX:.*=$JANK_DEV_PREFIX\$" "$b/CMakeCache.txt"; then
    return
  fi
  stage "configure jank (Release, llvm_dir=$JANK_LLVM, prefix $JANK_DEV_PREFIX, ccache)"
  (cd "$JANK_SRC/compiler+runtime" && CC="$JANK_LLVM/bin/clang" CXX="$JANK_LLVM/bin/clang++" ./bin/configure -GNinja \
     -DCMAKE_BUILD_TYPE=Release -Dllvm_dir="$JANK_LLVM" -Djank_local_clang=on \
     -DCMAKE_INSTALL_PREFIX="$JANK_DEV_PREFIX" \
     -DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache)
}

build_install() {
  stage "build (ninja -j$JOBS${JANK_BUILD_LOCK:+, under flock $JANK_BUILD_LOCK})"
  local s; s=$(date +%s)
  locked cmake --build "$JANK_SRC/compiler+runtime/build" -j "$JOBS"
  stage "build done in $(( $(date +%s) - s ))s"
  cmake --install "$JANK_SRC/compiler+runtime/build" > "$JANK_SRC/compiler+runtime/build/install.log"
  stage "installed: $JANK_DEV_PREFIX/bin/jank ($("$JANK_DEV_PREFIX/bin/jank" print-binary-version))"
}

if [ "$mode" = full ]; then
  deps
  if [ "$llvm_src" = 1 ]; then
    clone
    stage "build jank's LLVM snapshot from source (compiler+runtime/bin/build-clang)"
    (cd "$JANK_SRC/compiler+runtime" && CC="$APT_JANK/bin/clang" CXX="$APT_JANK/bin/clang++" locked ./bin/build-clang -j "$JOBS")
    JANK_LLVM="$JANK_SRC/compiler+runtime/build/llvm-install/usr/local"
  else
    llvm_prefix_from_apt
    clone
  fi
fi
[ -d "$JANK_SRC/.git" ] || { echo "no clone at $JANK_SRC: run without --patches first" >&2; exit 1; }
apply_series
configure
build_install
stage "done; total wall"

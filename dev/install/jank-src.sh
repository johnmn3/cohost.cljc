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
#   JOBS              ninja -j (default nproc)
#   JANK_SERIES       a series file to apply instead of upstream/jank/patches/series (patch names in it
#                     resolve against its own directory), e.g. to measure a patch before it joins the series
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
#
# The configure passes -Dllvm_dir with -Djank_local_clang=on, so cmake --install copies clang-23,
# libLLVM and libclang-cpp into $JANK_DEV_PREFIX/lib/jank/0.1 and sets the binary's RUNPATH to
# $ORIGIN/../lib/jank/0.1/lib: the installed jank needs no LD_LIBRARY_PATH. (Configured with
# jank_local_clang=on and no llvm_dir, the install looks for build/llvm-install and fails; with it off
# the binary needs LD_LIBRARY_PATH=$JANK_LLVM/lib.)
#
# Running jank (apt or source) also wants a UTF-8 locale: under POSIX its lexer rejects non-ASCII
# string literals (jank_init's setlocale(LC_CTYPE, ".UTF8") is not a glibc locale name).
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
[ -f "$cohost/upstream/jank/patches/series" ] || { echo "run from cohost's root (no upstream/jank/patches/series)" >&2; exit 2; }
series="$(realpath "${JANK_SERIES:-$cohost/upstream/jank/patches/series}")"
patchdir="$(dirname "$series")"
t0=$(date +%s)
stage() { echo "== jank-src: $* ($(( $(date +%s) - t0 ))s)"; }

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
  # Where the package records the snapshot differs between apt builds of 0.1-noble: some carry
  # LLVM_REVISION in include/llvm/Support/VCSRevision.h, some in include/llvm/Config/llvm-config.h;
  # clang-23 --version names it in both.
  local where=""
  for h in "$APT_JANK/include/llvm/Support/VCSRevision.h" "$APT_JANK/include/llvm/Config/llvm-config.h"; do
    if [ -f "$h" ] && grep -q "$LLVM_SHA" "$h"; then where="$h"; break; fi
  done
  if [ -z "$where" ] && "$APT_JANK/bin/clang-23" --version | grep -q "$LLVM_SHA"; then where="clang-23 --version"; fi
  [ -n "$where" ] || { echo "apt jank's LLVM is not $LLVM_SHA (checked VCSRevision.h, llvm-config.h, clang-23 --version); use --llvm-from-source" >&2; exit 1; }
  stage "apt LLVM is $LLVM_SHA (from $where)"
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
      git apply --cached "$patchdir/$p" || { echo "patch does not apply: $patchdir/$p" >&2; exit 1; }
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
  rewritten=$n
  stage "series applied: tree $tree, $n file(s) rewritten"
}

configure() {
  local b="$JANK_SRC/compiler+runtime/build"
  # A build dir configured by a JANK_LTO run carries -flto in its *_RELEASE flags: reconfigure it
  # plain (and drop its core-libs, built as bitcode-era objects), or the default build stays LTO.
  if [ -f "$b/CMakeCache.txt" ] && grep -q "^llvm_dir:.*=$JANK_LLVM\$" "$b/CMakeCache.txt" \
     && grep -q "^CMAKE_INSTALL_PREFIX:.*=$JANK_DEV_PREFIX\$" "$b/CMakeCache.txt" \
     && ! grep -q "^CMAKE_CXX_FLAGS_RELEASE:.*-flto" "$b/CMakeCache.txt"; then
    return
  fi
  local extra
  if [ -f "$b/CMakeCache.txt" ] && grep -q "^CMAKE_CXX_FLAGS_RELEASE:.*-flto" "$b/CMakeCache.txt"; then
    rm -rf "$b/core-libs"
    extra=(-DCMAKE_C_FLAGS_RELEASE="-O3 -DNDEBUG" -DCMAKE_CXX_FLAGS_RELEASE="-O3 -DNDEBUG"
           -DCMAKE_EXE_LINKER_FLAGS_RELEASE= -DCMAKE_SHARED_LINKER_FLAGS_RELEASE=
           -DCMAKE_AR=/usr/bin/ar -DCMAKE_RANLIB=/usr/bin/ranlib)
  else
    extra=()
  fi
  stage "configure jank (Release, llvm_dir=$JANK_LLVM, prefix $JANK_DEV_PREFIX, ccache)"
  (cd "$JANK_SRC/compiler+runtime" && CC="$JANK_LLVM/bin/clang" CXX="$JANK_LLVM/bin/clang++" ./bin/configure -GNinja \
     -DCMAKE_BUILD_TYPE=Release -Dllvm_dir="$JANK_LLVM" -Djank_local_clang=on \
     -DCMAKE_INSTALL_PREFIX="$JANK_DEV_PREFIX" \
     -DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache "${extra[@]}")
}

# Before phase 1 of a --patches build that rewrote any file: jank's phase 1 reuses
# build/core-libs/_cache/0/clojure/core.o whenever it is newer than core.jank, whatever the
# headers say, so an object-layout change ran the old core.o and segfaulted (u5, jit_closure::call);
# and the build-tree PCH (build/incremental.pch) and this prefix's cache entries are keyed by the
# binary version and flags, never by header contents (u1). Delete all three; phase 1 rebuilds them.
# A --patches run that rewrote nothing deletes nothing (ninja has nothing to do).
fresh_phase_1() {
  [ "$mode" = patches ] && [ "${rewritten:-0}" -gt 0 ] || return 0
  local b="$JANK_SRC/compiler+runtime/build"
  rm -rf "$b/core-libs" "$b/classes/core-libraries" "$b/incremental.pch"
  wipe_pch
  stage "fresh phase 1: removed $b/core-libs, classes/core-libraries, incremental.pch"
}

build_install() {
  fresh_phase_1
  stage "build (ninja -j$JOBS)"
  local s; s=$(date +%s)
  cmake --build "$JANK_SRC/compiler+runtime/build" -j "$JOBS"
  stage "build done: compile $(( $(date +%s) - s ))s"
  cmake --install "$JANK_SRC/compiler+runtime/build" > "$JANK_SRC/compiler+runtime/build/install.log"
  wrap_cache
  wipe_pch
  stage "installed: $JANK_DEV_PREFIX/bin/jank ($("$JANK_DEV_PREFIX/bin/jank" print-binary-version))"
}

# Builds of different series print the same binary version, and jank keys its
# PCH cache by that version and the flags only, never by header contents: two
# prefixes (or two series built into one) shared ~/.cache/jank/<version>/ and
# the second binary failed "codegen/internal-failure: Parsing failed" on the
# first one's incremental.pch (u2, u1, u5; coordinator NOTICE 1:30 PM ET).
# So the installed bin/jank is a wrapper that gives the prefix its own cache
# ($JANK_DEV_PREFIX/cache, through jank's XDG_CACHE_HOME: util/environment.cpp)
# and execs the real binary, bin/jank-real (same dir: its $ORIGIN RUNPATH holds).
wrap_cache() {
  local bin="$JANK_DEV_PREFIX/bin"
  # cmake --install wrote a fresh binary over bin/jank (or left the wrapper when unchanged)
  if ! head -c 2 "$bin/jank" | grep -q '#!'; then mv -f "$bin/jank" "$bin/jank-real"; fi
  cat > "$bin/jank" <<WRAP
#!/bin/sh
# dev/install/jank-src.sh: this prefix's own PCH cache (see wrap_cache there)
XDG_CACHE_HOME="$JANK_DEV_PREFIX/cache" exec "$bin/jank-real" "\$@"
WRAP
  chmod 755 "$bin/jank"
}

# After a build: drop the prefix's own cache, and (from before the wrapper, or a
# run of bin/jank-real directly) every entry in the user's cache that names
# this prefix (u1: -O3 and other flags get entries of their own). Delete each entry whose PCH names
# "$JANK_DEV_PREFIX/" (with the slash, so jank-dev never matches jank-dev-verify);
# the next run of that binary rebuilds its PCH. The cache dir is jank's own:
# $XDG_CACHE_HOME/jank, else ~/.cache/jank (util/environment.cpp).
wipe_pch() {
  rm -rf "$JANK_DEV_PREFIX/cache"   # every build: this prefix's headers may have changed
  local root="${XDG_CACHE_HOME:-$HOME/.cache}/jank" d n=0
  for d in "$root"/*/; do
    [ -f "$d/incremental.pch" ] || continue
    if grep -aqF "$JANK_DEV_PREFIX/" "$d/incremental.pch"; then rm -rf "$d"; n=$((n + 1)); fi
  done
  stage "PCH cache: cleared $JANK_DEV_PREFIX/cache; removed $n entries naming $JANK_DEV_PREFIX/ under $root"
}

if [ "$mode" = full ]; then
  deps
  if [ "$llvm_src" = 1 ]; then
    clone
    stage "build jank's LLVM snapshot from source (compiler+runtime/bin/build-clang)"
    (cd "$JANK_SRC/compiler+runtime" && CC="$APT_JANK/bin/clang" CXX="$APT_JANK/bin/clang++" ./bin/build-clang -j "$JOBS")
    JANK_LLVM="$JANK_SRC/compiler+runtime/build/llvm-install/usr/local"
  else
    llvm_prefix_from_apt
    clone
  fi
fi
# ---- optional: ThinLTO build of jank (JANK_LTO=thin; off by default) -- u5, U5-4 ----
# jank's CMakeLists sets CMAKE_INTERPROCEDURAL_OPTIMIZATION FALSE, and the apt-derived $JANK_LLVM has no LTO linker
# (no lld, no LLVMgold.so; llvm-ar is GNU ar, which cannot index bitcode; llvm-tblgen and llvm-profdata are stubs).
# lto_tools builds them once from jank-lang/llvm-project @ $LLVM_SHA (sparse checkout) against $JANK_LLVM's real
# libLLVM.so into $JANK_LLVM.lto/bin: a minimal llvm-tblgen (the option-parser backend + lib/TableGen, which libLLVM.so
# lacks), llvm-ar, llvm-profdata (two Debuginfod symbols stubbed: profile merging never uses them) and lld (standalone
# CMake build). Then configure passes -flto=thin to every C/C++ TU and links with that lld. JANK_PGO=<profdata> adds
# -fprofile-use (train with an -fprofile-generate build: JANK_PGO=generate). Use its own JANK_SRC/JANK_DEV_PREFIX.
# The flags go in the *_RELEASE variables: jank copies CMAKE_CXX_FLAGS and CMAKE_SHARED_LINKER_FLAGS into the runtime's
# JANK_AOT_FLAGS (compiler+runtime/CMakeLists.txt:713), and -flto there makes phase 1 emit core.o as bitcode it cannot
# load. The build dir must be compiler+runtime/build: jank treats only that as an in-tree build (environment_dynamic.cpp).
if [ -n "${JANK_LTO:-}" ]; then
  LTO_TOOLS="$JANK_LLVM.lto"
  lto_tools() {
    [ -x "$LTO_TOOLS/bin/ld.lld" ] && [ -x "$LTO_TOOLS/bin/llvm-ar" ] && return
    stage "LTO tools: llvm-tblgen, llvm-ar, llvm-profdata, lld from llvm-project @ $LLVM_SHA against $JANK_LLVM/lib/libLLVM.so"
    local src="$LTO_TOOLS/src" bin="$LTO_TOOLS/bin" cxx="$JANK_LLVM/bin/clang++" inc="$LTO_TOOLS/inc"
    mkdir -p "$bin" "$inc/llvm/Config"
    if [ ! -d "$src/lld" ]; then
      git init -q "$src" && git -C "$src" remote add origin https://github.com/jank-lang/llvm-project.git
      git -C "$src" config core.sparseCheckout true
      printf 'lld/\ncmake/\nlibunwind/include/\nllvm/utils/TableGen/\nllvm/lib/TableGen/\nllvm/tools/llvm-ar/\nllvm/tools/llvm-profdata/\n' \
        > "$src/.git/info/sparse-checkout"
      git -C "$src" fetch -q --filter=blob:none --depth 1 origin "$LLVM_SHA" && git -C "$src" checkout -q FETCH_HEAD
    fi
    echo '#include "llvm/Config/llvm-config.h"' > "$inc/llvm/Config/config.h"
    printf '%s\n' '#include "llvm/Support/LLVMDriver.h"' 'int TOOL_MAIN(int, char **, const llvm::ToolContext &);' \
      'int main(int argc, char **argv) { return TOOL_MAIN(argc, argv, {argv[0], nullptr, false}); }' > "$inc/driver_main.cpp"
    printf '%s\n' '#include "llvm/Debuginfod/BuildIDFetcher.h"' '#include "llvm/HTTP/HTTPClient.h"' \
      'void llvm::HTTPClient::initialize() {}' \
      'llvm::Expected<std::string> llvm::DebuginfodFetcher::fetch(llvm::object::BuildIDRef) const {' \
      '  return llvm::createStringError("debuginfod is not available in this llvm-profdata"); }' > "$inc/debuginfod_stub.cpp"
    local f=(-std=c++17 -O2 -fno-rtti -I"$inc" -I"$JANK_LLVM/include") l=(-L"$JANK_LLVM/lib" -lLLVM -Wl,-rpath,"$JANK_LLVM/lib")
    (cd "$src/llvm/utils/TableGen" && "$cxx" "${f[@]}" -I. -IBasic -ICommon llvm-min-tblgen.cpp Basic/TableGen.cpp \
       OptionParserEmitter.cpp Common/OptEmitter.cpp ../../lib/TableGen/*.cpp "${l[@]}" -o "$bin/llvm-tblgen")
    "$cxx" "${f[@]}" -DTOOL_MAIN=llvm_ar_main "$src/llvm/tools/llvm-ar/llvm-ar.cpp" "$inc/driver_main.cpp" "${l[@]}" -o "$bin/llvm-ar"
    ln -sfn llvm-ar "$bin/llvm-ranlib"
    "$cxx" "${f[@]}" "$src/llvm/tools/llvm-profdata/llvm-profdata.cpp" "$inc/debuginfod_stub.cpp" "${l[@]}" -o "$bin/llvm-profdata"
    cmake -S "$src/lld" -B "$LTO_TOOLS/lld-build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DLLVM_DIR="$JANK_LLVM/lib/cmake/llvm" \
      -DCMAKE_C_COMPILER="$JANK_LLVM/bin/clang" -DCMAKE_CXX_COMPILER="$cxx" -DLLVM_INCLUDE_TESTS=OFF \
      -DLLVM_TABLEGEN_EXE="$bin/llvm-tblgen" > "$LTO_TOOLS/lld-configure.log"
    cmake --build "$LTO_TOOLS/lld-build" -j "$JOBS" --target lld > "$LTO_TOOLS/lld-build.log"
    ln -sfn "$LTO_TOOLS/lld-build/bin/lld" "$bin/ld.lld"
  }
  configure() {
    local b="$JANK_SRC/compiler+runtime/build" fl="-flto=thin"
    case "${JANK_PGO:-}" in
      "") ;;
      generate) fl="$fl -fprofile-generate" ;;
      *) fl="$fl -fprofile-use=$JANK_PGO -Wno-profile-instr-unprofiled -Wno-profile-instr-out-of-date" ;;
    esac
    if [ -f "$b/CMakeCache.txt" ] && grep -qF "CMAKE_CXX_FLAGS_RELEASE:STRING=-O3 -DNDEBUG $fl" "$b/CMakeCache.txt" \
       && grep -q "^CMAKE_INSTALL_PREFIX:.*=$JANK_DEV_PREFIX\$" "$b/CMakeCache.txt"; then
      return
    fi
    lto_tools
    local ld="-fuse-ld=lld --ld-path=$LTO_TOOLS/bin/ld.lld -Wl,--thinlto-jobs=$JOBS"
    stage "configure jank with $fl (prefix $JANK_DEV_PREFIX)"
    rm -rf "$b/core-libs"
    (cd "$JANK_SRC/compiler+runtime" && CC="$JANK_LLVM/bin/clang" CXX="$JANK_LLVM/bin/clang++" ./bin/configure -GNinja \
       -DCMAKE_BUILD_TYPE=Release -Dllvm_dir="$JANK_LLVM" -Djank_local_clang=on -DCMAKE_INSTALL_PREFIX="$JANK_DEV_PREFIX" \
       -DCMAKE_C_FLAGS_RELEASE="-O3 -DNDEBUG $fl" -DCMAKE_CXX_FLAGS_RELEASE="-O3 -DNDEBUG $fl" \
       -DCMAKE_EXE_LINKER_FLAGS_RELEASE="$fl $ld" -DCMAKE_SHARED_LINKER_FLAGS_RELEASE="$fl $ld" \
       -DCMAKE_AR="$LTO_TOOLS/bin/llvm-ar" -DCMAKE_RANLIB="$LTO_TOOLS/bin/llvm-ranlib")
  }
fi
# ---- end ThinLTO ----
[ -d "$JANK_SRC/.git" ] || { echo "no clone at $JANK_SRC: run without --patches first" >&2; exit 1; }
apply_series
configure
build_install
stage "done; total wall"

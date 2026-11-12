#!/usr/bin/env bash
# Build the champaca bundle the :clj leg runs when `clojure` is not on PATH.
#
# The bundle is class modules only: Clojure's jars, a jlink image of the JDK
# classes they need, and lib/champaca. Building needs JDK 25 (jdeps and jlink)
# and jolt >= 0.8.26. Running the bundle needs neither.
#
# Upstream champaca does not yet keep one class identity per loader. Until
# that lands in b12n-oss/champaca, CHAMPACA_SRC must be a checkout with
# upstream/champaca/fold-full.diff (in this repo) applied — the fold
# supersedes the per-loader identity patch; it contains it:
#   upstream/champaca/fold-full.diff
#
# Usage:
#   CHAMPACA_SRC=/path/to/patched/champaca bash dev/build-clj-bundle.sh [OUT]
# OUT defaults to ${COHOST_CLJ_BUNDLE:-$HOME/.cache/cohost/cohost-clj},
# which is where bin/cohost looks when COHOST_CLJ_BUNDLE is unset.
#
# Versions pinned into the bundle: Clojure 1.12.0, spec.alpha 0.5.238,
# core.specs.alpha 0.4.74. jolt -Spath omits org.clojure/clojure (it is a
# terminal dependency), so this script finds the local Maven repo and
# downloads clojure-1.12.0.jar when it is not already there, then prepends
# it. At run time the bundle's app/* already supplies that jar, so a
# project's jolt -Spath is fine without it. The launcher is named cohost-clj.
# lib/champaca is what cohost execs; bin/cohost-clj cannot take a runtime -cp.
set -eu

die() { printf 'build-clj-bundle: %s\n' "$*" >&2; exit 1; }

command -v jolt >/dev/null 2>&1 || die "jolt is not on PATH (need >= 0.8.26)"
ver="$(jolt --version 2>/dev/null || true)"
num="$(printf '%s\n' "$ver" | sed -n 's/.*\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\).*/\1/p' | head -1)"
[ -n "$num" ] || die "could not read a numeric version from jolt --version (${ver:-no output}); need >= 0.8.26"
[ "$(printf '%s\n%s\n' 0.8.26 "$num" | sort -V | head -1)" = 0.8.26 ] \
  || die "jolt >= 0.8.26 required, found: ${ver}"

[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] \
  || die "JAVA_HOME must be a JDK 25. The JDK is only needed while building the bundle."
rel="$(sed -n 's/^JAVA_VERSION="\(.*\)"/\1/p' "$JAVA_HOME/release" 2>/dev/null | head -1 || true)"
case "$rel" in
  25*) ;;
  *) die "JAVA_HOME ($JAVA_HOME) reports Java ${rel:-unknown}; the bundle build needs JDK 25." ;;
esac

[ -n "${CHAMPACA_SRC:-}" ] \
  || die "CHAMPACA_SRC is unset. Point it at a champaca checkout with upstream/champaca/fold-full.diff (in this repo) applied — the fold supersedes the per-loader class identity patch; it contains it. Upstream b12n-oss/champaca does not have that fix yet."
[ -f "$CHAMPACA_SRC/tools/bundle.clj" ] \
  || die "CHAMPACA_SRC ($CHAMPACA_SRC) has no tools/bundle.clj; it is not a champaca checkout."

out="${1:-${COHOST_CLJ_BUNDLE:-$HOME/.cache/cohost/cohost-clj}}"
case "$out" in
  /*) ;;
  *) out="$(pwd)/$out" ;;
esac

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cat > "$work/deps.edn" <<'EOF'
{:paths []
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}
        org.clojure/spec.alpha {:mvn/version "0.5.238"}
        org.clojure/core.specs.alpha {:mvn/version "0.4.74"}}
 :override-deps {org.clojure/spec.alpha {:mvn/version "0.5.238"}
                 org.clojure/core.specs.alpha {:mvn/version "0.4.74"}}}
EOF

raw="$(cd "$work" && jolt -Srepro -Sverbose -Spath 2>&1)"
repo="$(printf '%s\n' "$raw" | sed -n 's/^mvn_local_repo *= *//p' | head -1)"
[ -n "$repo" ] || repo="${HOME}/.m2/repository"
cp="$(printf '%s\n' "$raw" | sed -n '/^mvn_local_repo *=/d; /^[[:space:]]*$/d; p' | tail -1)"
[ -n "$cp" ] || die "jolt -Srepro -Sverbose -Spath printed an empty classpath"
abs=""
set -f
IFS=':'
for e in $cp; do
  [ -n "$e" ] || continue
  case "$e" in
    /*) one="$e" ;;
    *) one="$work/$e" ;;
  esac
  abs="${abs:+$abs:}$one"
done
unset IFS
set +f
clojure_jar="$repo/org/clojure/clojure/1.12.0/clojure-1.12.0.jar"
if [ ! -f "$clojure_jar" ]; then
  echo "build-clj-bundle: fetching clojure-1.12.0.jar into $clojure_jar"
  mkdir -p "$(dirname "$clojure_jar")"
  curl -fsSL -o "$clojure_jar.partial" \
    "https://repo1.maven.org/maven2/org/clojure/clojure/1.12.0/clojure-1.12.0.jar"
  mv "$clojure_jar.partial" "$clojure_jar"
fi
abs="$clojure_jar:$abs"
for needle in clojure-1.12.0 spec.alpha-0.5.238 core.specs.alpha-0.4.74; do
  case "$abs" in
    *"$needle"*) ;;
    *) die "resolved classpath is missing $needle ($abs)" ;;
  esac
done

echo "build-clj-bundle: jolt bundle -o $out -m clojure.main --name cohost-clj"
(cd "$CHAMPACA_SRC" && jolt bundle -o "$out" -cp "$abs" -m clojure.main \
  --add-modules java.desktop,java.sql,jdk.unsupported --name cohost-clj)
[ -x "$out/lib/champaca" ] || die "bundle finished but $out/lib/champaca is missing"

# jolt loads champaca.api from source roots. init! then loads whatever this
# checkout's own sources name: cachedir.clj's runtime-files and
# translator-sources, and every (io/resource "...") literal. That list is
# read from CHAMPACA_SRC. It is not a fixed set of filenames (44ad7c4 has
# no archive.ss; a later checkout that adds one names it in runtime-files).
# lib/ and app/ do not carry those files. One directory holds both trees,
# so one -Scp entry finds champaca/api.clj and champaca/*.ss.
src_root="$out/champaca-src"
rm -rf "$src_root"
mkdir -p "$src_root"
[ -d "$CHAMPACA_SRC/src/champaca" ] \
  || die "CHAMPACA_SRC ($CHAMPACA_SRC) has no src/champaca; champaca.api would not load under jolt."
[ -d "$CHAMPACA_SRC/resources/champaca" ] \
  || die "CHAMPACA_SRC ($CHAMPACA_SRC) has no resources/champaca; init! reads the Scheme runtime from the source root."
cp -a "$CHAMPACA_SRC/src/." "$src_root/"
cp -a "$CHAMPACA_SRC/resources/." "$src_root/"

# Strings in the first vector of (def name) or (def ^:private name).
# mawk has no word-boundary atom; the name is matched as its own token.
champaca_def_strings() {
  awk -v name="$2" '
    {
      if (!grab && ($0 ~ ("\\(def[[:space:]]+" name "([[:space:]]|$)") ||
                    $0 ~ ("\\(def[[:space:]]+\\^:[^[:space:]]+[[:space:]]+" name "([[:space:]]|$)")))
        grab = 1
      if (grab) {
        line = $0
        while (match(line, /"[^"]+"/)) {
          s = substr(line, RSTART + 1, RLENGTH - 2)
          if (s ~ /\.(ss|clj|cljc|jolt)$/) print s
          line = substr(line, RSTART + RLENGTH)
        }
        if (index($0, "]")) exit
      }
    }
  ' "$1"
}

api_rel=""
for ext in jolt clj cljc; do
  if [ -f "$src_root/champaca/api.$ext" ]; then api_rel="champaca/api.$ext"; break; fi
done
[ -n "$api_rel" ] || die "copied champaca sources have no champaca/api.clj (or .cljc/.jolt)"
[ -f "$src_root/champaca/cachedir.clj" ] \
  || die "copied champaca sources have no champaca/cachedir.clj; its runtime-files vector is what init! loads from the source root"
rt="$(champaca_def_strings "$src_root/champaca/cachedir.clj" runtime-files)"
[ -n "$rt" ] || die "could not read runtime-files from $src_root/champaca/cachedir.clj"
lits="$(grep -hEo 'io/resource[[:space:]]+"[^"]+"' "$src_root"/champaca/*.clj 2>/dev/null || true)"
lits="$(printf '%s\n' "$lits" | sed -E 's/.*"([^"]+)".*/\1/')"
req="$(printf '%s\n' "$api_rel" "$rt" \
  "$(champaca_def_strings "$src_root/champaca/cachedir.clj" translator-sources)" \
  "$lits" | awk 'NF && !seen[$0]++')"
miss=""
while IFS= read -r rel; do
  [ -n "$rel" ] || continue
  [ -f "$src_root/$rel" ] || miss="${miss:+$miss$'\n'}$rel"
done <<EOF
$req
EOF
[ -z "$miss" ] || die "copied champaca sources are missing files this checkout loads from the source root:"$'\n'"$miss"
cat > "$out/manifest.edn" <<'EOF'
{:name "cohost-clj"
 :champaca-src "champaca-src"}
EOF
echo "build-clj-bundle: $out (champaca-src recorded in manifest.edn)"

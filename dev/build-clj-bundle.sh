#!/usr/bin/env bash
# Build the champaca bundle the :clj leg runs when `clojure` is not on PATH.
#
# The bundle is class modules only: Clojure's jars, a jlink image of the JDK
# classes they need, and lib/champaca. Building needs JDK 25 (jdeps and jlink)
# and jolt >= 0.8.26. Running the bundle needs neither.
#
# Upstream champaca does not yet keep one class identity per loader. Until
# that lands in b12n-oss/champaca, CHAMPACA_SRC must be a checkout with the
# per-loader identity patch applied:
#   johnmn3/atom.broker lane-m/jolt-clj-body @ ee947a3fdd
#   docs/clojure-runtime/multi-bodied/jolt-spike/per-loader-identity.experimental.diff
#
# Usage:
#   CHAMPACA_SRC=/path/to/patched/champaca bash dev/build-clj-bundle.sh [OUT]
# OUT defaults to ${COHOST_CLJ_BUNDLE:-$HOME/.cache/cohost/cohost-clj},
# which is where bin/cohost looks when COHOST_CLJ_BUNDLE is unset.
#
# Versions pinned into the bundle: Clojure 1.12.0, spec.alpha 0.5.238,
# core.specs.alpha 0.4.74. The bundle's launcher is named cohost-clj.
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
  || die "CHAMPACA_SRC is unset. Point it at a champaca checkout with the per-loader class identity patch applied (johnmn3/atom.broker lane-m/jolt-clj-body @ ee947a3fdd, docs/clojure-runtime/multi-bodied/jolt-spike/per-loader-identity.experimental.diff). Upstream b12n-oss/champaca does not have that fix yet."
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

raw="$(cd "$work" && jolt -Srepro -Spath)"
cp="$(printf '%s\n' "$raw" | sed '/^[[:space:]]*$/d' | tail -1)"
[ -n "$cp" ] || die "jolt -Srepro -Spath printed an empty classpath"
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
echo "build-clj-bundle: $out"

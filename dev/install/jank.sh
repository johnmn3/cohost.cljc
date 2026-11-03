#!/usr/bin/env bash
# jank: no versioned releases. macOS: jank's Homebrew tap (jank-lang/jank, a prebuilt
# arm64 build). Debian/Ubuntu: jank's apt repository (book.jank-lang.org,
# getting-started/01-installation.md). Records the installed version.
source "$(dirname "$0")/_lib.sh"
if [ -z "${COHOST_FORCE:-}" ] && have jank; then
  say "already installed: jank ($(command -v jank)); COHOST_FORCE=1 reinstalls"
  jank print-binary-version 2>/dev/null || true
  exit 0
fi
case "$os" in
  macos)
    [ "$arch" = aarch64 ] || unsupported jank "the Homebrew tap ships an arm64 build"
    need_brew
    brew tap jank-lang/jank
    # Homebrew 5+ refuses formulae from a tap until the user trusts it. That is the user's
    # call: stop with the command, unless COHOST_TRUST_TAPS=1 says to trust jank's formula.
    if brew trust --help >/dev/null 2>&1 && ! brew info jank-lang/jank/jank >/dev/null 2>&1; then
      if [ -n "${COHOST_TRUST_TAPS:-}" ]; then
        brew trust --formula jank-lang/jank/jank
      else
        die "Homebrew needs you to trust jank's tap first:
    brew trust --formula jank-lang/jank/jank
  then rerun this script (or rerun with COHOST_TRUST_TAPS=1)"
      fi
    fi
    if brew list jank >/dev/null 2>&1; then brew reinstall jank; else brew install jank; fi
    brew list --versions jank ;;
  linux)
    have apt-get || die "jank's packages are for Debian/Ubuntu (apt); see https://book.jank-lang.org"
    # jank ships as an apt package, which needs root: ask once, before the piped steps below
    say "jank's apt package installs system-wide, which needs root"
    as_root true
    have gpg || as_root apt-get install -y gnupg
    have lsb_release || as_root apt-get install -y lsb-release
    curl -fsS "https://ppa.jank-lang.org/KEY.gpg" | gpg --dearmor | as_root tee /etc/apt/trusted.gpg.d/jank.gpg >/dev/null
    echo "deb [signed-by=/etc/apt/trusted.gpg.d/jank.gpg] https://ppa.jank-lang.org $(lsb_release -cs) main" \
      | as_root tee /etc/apt/sources.list.d/jank.list >/dev/null
    as_root apt-get update
    as_root apt-get install -y jank
    dpkg-query -W jank ;;
  windows) die "jank has no Windows build; use WSL (Ubuntu) and rerun there" ;;
esac
jank check-health 2>&1 | tail -3 || true

# cohost's core snapshot for jank, so the first cohost command starts fast; bin/cohost builds
# it on first use too, under the same lock. COHOST_JANK_SNAPSHOT=off skips it.
repo="$(cd "$(dirname "$0")/../.." && pwd)"
if [ "${COHOST_JANK_SNAPSHOT:-}" != off ] && have bb && [ -f "$repo/dev/perf/jank_snapshot_key.clj" ]; then
  mkdir -p "$repo/target"
  if mkdir "$repo/target/jank-snapshot.lock" 2>/dev/null; then
    say "building cohost's core snapshot and launcher for jank (once: 11 minutes measured on a busy Linux box; log: target/jank-snapshot.log)"
    if (cd "$repo" && bb -e '(load-file "dev/perf/jank_snapshot_key.clj") ((resolve (quote ensure!)))') \
         >"$repo/target/jank-snapshot.log" 2>&1; then
      say "cohost's core snapshot for jank is built"
    else
      say "the snapshot build failed (target/jank-snapshot.log); cohost builds it on first use instead"
    fi
    rmdir "$repo/target/jank-snapshot.lock" 2>/dev/null || true
  fi
fi

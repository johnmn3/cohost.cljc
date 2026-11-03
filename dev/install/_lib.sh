#!/usr/bin/env bash
# Shared by dev/install/<host>.sh: the platform, checksums, the bin directory, package
# managers. Sourced, not run. Works on Linux (glibc), macOS (arm64 and x86_64) and Windows
# under Git Bash / MSYS2 (where a host publishes a Windows build).
#
# Env knobs (every script):
#   COHOST_HOSTS   where release archives are unpacked (default ~/.cache/cohost-hosts)
#   COHOST_BIN     where host binaries are installed (default /usr/local/bin when writable,
#                  else ~/.local/bin)
#   COHOST_FORCE=1 reinstall even when the pinned version is already on PATH

set -euo pipefail
orig_path="$PATH"

say() { printf '%s\n' "$*" >&2; }
die() { say "error: $*"; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# os: linux | macos | windows     arch: x86_64 | aarch64
case "$(uname -s)" in
  Linux*) os=linux ;;
  Darwin*) os=macos ;;
  MINGW*|MSYS*|CYGWIN*) os=windows ;;
  *) die "unsupported OS: $(uname -s)" ;;
esac
case "$(uname -m)" in
  x86_64|amd64) arch=x86_64 ;;
  arm64|aarch64) arch=aarch64 ;;
  *) die "unsupported CPU: $(uname -m)" ;;
esac

hosts_dir="${COHOST_HOSTS:-$HOME/.cache/cohost-hosts}"

if [ -n "${COHOST_BIN:-}" ]; then
  bin_dir="$COHOST_BIN"
elif [ "$os" != windows ] && [ -w /usr/local/bin ]; then
  bin_dir=/usr/local/bin
else
  bin_dir="$HOME/.local/bin"
fi

# sha256 of a file, hex, on every platform
sha256_of() {
  if have sha256sum; then sha256sum "$1" | cut -d' ' -f1
  elif have shasum; then shasum -a 256 "$1" | cut -d' ' -f1
  elif have certutil; then certutil -hashfile "$1" SHA256 | sed -n 2p | tr -d ' \r'
  else die "no sha256 tool (sha256sum, shasum or certutil)"; fi
}

# verify_sha256 <file> <expected hex>
verify_sha256() {
  local got; got="$(sha256_of "$1")"
  [ "$got" = "$2" ] || die "$1: sha256 $got, expected $2"
  say "$1: OK"
}

# expected sum for <name> from a checksum file in `sha256sum` format (or a bare hex)
sum_from() { awk -v n="$2" 'NF==1 {print $1; exit} $2==n || $2=="*"n {print $1; exit}' "$1"; }

fetch() { curl -fsSL --retry 3 -o "$2" "$1"; }

# install_bin <file> [name]: into $bin_dir, warning when $bin_dir is not on PATH
install_bin() {
  local src="$1" name="${2:-$(basename "$1")}"
  mkdir -p "$bin_dir"
  cp "$src" "$bin_dir/$name.tmp.$$" && chmod 755 "$bin_dir/$name.tmp.$$" && mv -f "$bin_dir/$name.tmp.$$" "$bin_dir/$name"
  # a downloaded binary on macOS carries no quarantine bit when curl fetched it, but clear it anyway
  [ "$os" = macos ] && xattr -d com.apple.quarantine "$bin_dir/$name" 2>/dev/null || true
  say "installed $bin_dir/$name"
  on_path_or_warn
}

on_path_or_warn() {
  case ":$orig_path:" in
    *":$bin_dir:"*) ;;
    *) say "note: $bin_dir is not on PATH; add it:  export PATH=\"$bin_dir:\$PATH\"" ;;
  esac
}

# already <cmd> <version-cmd...> -- <expected substring>: exit 0 early when installed
already() {
  local cmd="$1"; shift
  local args=() ; while [ "$1" != -- ]; do args+=("$1"); shift; done; shift
  [ -n "${COHOST_FORCE:-}" ] && return 0
  local out=""
  have "$cmd" && out="$("$cmd" "${args[@]}" 2>&1 || true)"
  if [ -n "$out" ] && case "$out" in *"$1"*) true ;; *) false ;; esac; then
    say "already installed: $1 ($(command -v "$cmd")); COHOST_FORCE=1 reinstalls"
    exit 0
  fi
}

# root for system package managers: nothing when root, else sudo
as_root() {
  if [ "$(id -u 2>/dev/null || echo 1)" = 0 ]; then "$@"
  elif have sudo; then sudo "$@"
  else die "needs root for: $* (no sudo)"; fi
}

need_brew() { have brew || die "Homebrew is needed on macOS: https://brew.sh"; }

unsupported() { die "$1 has no build for $os/$arch${2:+; $2}"; }

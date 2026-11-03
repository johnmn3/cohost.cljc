#!/usr/bin/env bash
# Phel v0.54.0 needs PHP >= 8.5: an existing PHP 8.5+ when there is one, else PHP from
# Homebrew (macOS) or ppa:ondrej/php (Debian/Ubuntu); then the release's standalone
# phel.phar, run by a `phel` wrapper pinned to that PHP.
source "$(dirname "$0")/_lib.sh"
ver=v0.54.0
already phel --version -- "${ver#v}"

php_ok() { "$1" -r 'exit(version_compare(PHP_VERSION, "8.5.0", ">=") ? 0 : 1);' 2>/dev/null; }
find_php() {
  local p
  for p in php8.5 php "${HOMEBREW_PREFIX:-/opt/homebrew}/bin/php" /usr/local/bin/php; do
    if have "$p" && php_ok "$p"; then command -v "$p"; return 0; fi
  done
  return 1
}

if ! php="$(find_php)"; then
  case "$os" in
    macos) need_brew; brew install php ;;
    linux)
      have apt-get || die "needs PHP >= 8.5 on PATH (no apt-get here to install it)"
      # add-apt-repository needs the Python that has apt's apt_pkg (python3 may be newer
      # than the one apt_pkg is built for)
      py=""
      for p in python3 python3.12 /usr/bin/python3; do
        if "$p" -c 'import apt_pkg' 2>/dev/null; then py="$p"; break; fi
      done
      [ -n "$py" ] || die "no python with apt_pkg for add-apt-repository (apt-get install python3-apt software-properties-common)"
      as_root "$py" /usr/bin/add-apt-repository -y ppa:ondrej/php
      as_root apt-get update
      as_root apt-get install -y php8.5-cli php8.5-mbstring ;;
    windows) die "needs PHP >= 8.5 on PATH (https://windows.php.net/download, or: scoop install php)" ;;
  esac
  php="$(find_php)" || die "PHP >= 8.5 still not found after installing"
fi
say "php: $php ($("$php" -r 'echo PHP_VERSION;'))"

dir="$hosts_dir/phel-$ver"
mkdir -p "$dir" && cd "$dir"
fetch "https://github.com/phel-lang/phel-lang/releases/download/$ver/phel.phar" phel.phar
say "phel.phar sha256 $(sha256_of phel.phar)"
# -d log_errors=0: PHP's ini (Homebrew's) also logs each warning to stderr, so it printed twice
printf '#!/bin/sh\nexec "%s" -d log_errors=0 "%s/phel.phar" "$@"\n' "$php" "$dir" > phel
install_bin phel
"$bin_dir/phel" --version

#!/usr/bin/env bash
# Phel v0.54.0 needs PHP >= 8.5 (the box has 8.3): PHP 8.5 from ppa:ondrej/php, then the
# release's standalone phel.phar, run by a `phel` wrapper pinned to php8.5.
set -euo pipefail
ver=v0.54.0
add-apt-repository -y ppa:ondrej/php
apt-get update
apt-get install -y php8.5-cli php8.5-mbstring
dir="${COHOST_HOSTS:-$HOME/.cache/cohost-hosts}/phel-$ver"
mkdir -p "$dir" && cd "$dir"
curl -fsSLo phel.phar "https://github.com/phel-lang/phel-lang/releases/download/$ver/phel.phar"
sha256sum phel.phar
printf '#!/bin/sh\nexec php8.5 %s/phel.phar "$@"\n' "$dir" > /usr/local/bin/phel
chmod 755 /usr/local/bin/phel
phel --version

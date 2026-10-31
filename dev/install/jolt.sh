#!/usr/bin/env bash
# jolt v0.8.23 (the version the existing jolt rows were measured on): the prebuilt
# Linux x86_64 archive, checked against the release's .sha256 sidecar.
set -euo pipefail
ver=v0.8.23
name="jolt-$ver-x86_64-linux"
dir="${COHOST_HOSTS:-$HOME/.cache/cohost-hosts}/$name"
mkdir -p "$dir" && cd "$dir"
base="https://github.com/jolt-lang/jolt/releases/download/$ver"
curl -fsSLO "$base/$name.tar.gz"
curl -fsSLO "$base/$name.tar.gz.sha256"
echo "$(cut -d' ' -f1 "$name.tar.gz.sha256")  $name.tar.gz" | sha256sum -c -
tar -xzf "$name.tar.gz"
install -m 755 "$name/jolt" /usr/local/bin/jolt
jolt -e '(+ 1 2)'

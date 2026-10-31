#!/usr/bin/env bash
# let-go v1.13.0: the goreleaser Linux amd64 archive (binary `lg`), checked against the
# release's checksums.txt. (Building needs Go 1.26+; the box has 1.24.)
set -euo pipefail
ver=1.13.0
asset="let-go_${ver}_linux_amd64.tar.gz"
dir="${COHOST_HOSTS:-$HOME/.cache/cohost-hosts}/let-go-v$ver"
mkdir -p "$dir" && cd "$dir"
base="https://github.com/nooga/let-go/releases/download/v$ver"
curl -fsSLO "$base/$asset"
curl -fsSLO "$base/checksums.txt"
sha256sum -c checksums.txt --ignore-missing
tar -xzf "$asset" lg
install -m 755 lg /usr/local/bin/lg
lg -version

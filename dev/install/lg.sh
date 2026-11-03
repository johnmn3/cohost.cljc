#!/usr/bin/env bash
# let-go v1.13.0: the goreleaser archive for this platform (binary `lg`; Linux amd64/arm64,
# macOS amd64/arm64), checked against the release's checksums.txt. No Windows archive:
# there `go install` builds it (Go 1.26+).
source "$(dirname "$0")/_lib.sh"
ver=1.13.0
already lg -version -- "$ver"
case "$os" in linux) gos=linux ;; macos) gos=darwin ;; windows) gos=windows ;; esac
case "$arch" in x86_64) garch=amd64 ;; aarch64) garch=arm64 ;; esac
if [ "$os" = windows ]; then
  have go || die "let-go has no Windows release archive; install Go 1.26+ and rerun (go install)"
  GOBIN="$bin_dir" go install "github.com/nooga/let-go@v$ver"
  "$bin_dir/lg" -version; exit 0
fi
asset="let-go_${ver}_${gos}_${garch}.tar.gz"
dir="$hosts_dir/let-go-v$ver"
mkdir -p "$dir" && cd "$dir"
base="https://github.com/nooga/let-go/releases/download/v$ver"
fetch "$base/$asset" "$asset"
fetch "$base/checksums.txt" checksums.txt
verify_sha256 "$asset" "$(sum_from checksums.txt "$asset")"
tar -xzf "$asset" lg
install_bin lg
"$bin_dir/lg" -version

#!/usr/bin/env bash
# ClojureWasm (cljw) v1.15.4: the prebuilt binary from the GitHub release (Linux x86_64,
# macOS arm64), checked against the release's .sha256 sidecar. The project moved to
# BuddhiLW in 2026-10; both repositories carry the v1.15.4 tag, so the fork is the fallback.
source "$(dirname "$0")/_lib.sh"
ver=v1.15.4
already cljw --version -- "${ver#v}"
case "$os-$arch" in
  linux-x86_64) asset=cljw-linux-x86_64.tar.gz ;;
  macos-aarch64) asset=cljw-macos-aarch64.tar.gz ;;
  *) unsupported cljw "release $ver ships linux-x86_64 and macos-aarch64 only" ;;
esac
dir="$hosts_dir/cljw-$ver"
mkdir -p "$dir" && cd "$dir"
ok=
for repo in clojurewasm/ClojureWasm BuddhiLW/ClojureWasm; do
  base="https://github.com/$repo/releases/download/$ver"
  fetch "$base/$asset" "$asset" && fetch "$base/$asset.sha256" "$asset.sha256" && { ok=1; break; }
done
[ -n "$ok" ] || die "could not download $asset $ver"
verify_sha256 "$asset" "$(sum_from "$asset.sha256" "$asset")"
tar -xzf "$asset" cljw
install_bin cljw
"$bin_dir/cljw" --version

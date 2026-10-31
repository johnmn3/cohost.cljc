#!/usr/bin/env bash
# ClojureWasm (cljw) v1.15.4: the prebuilt Linux x86_64 binary from the GitHub release,
# checked against the release's .sha256 sidecar. The project moved to BuddhiLW in
# 2026-10; both repositories carry the v1.15.4 tag, so the fork is the fallback.
set -euo pipefail
ver=v1.15.4
asset=cljw-linux-x86_64.tar.gz
dir="${COHOST_HOSTS:-$HOME/.cache/cohost-hosts}/cljw-$ver"
mkdir -p "$dir" && cd "$dir"
for repo in clojurewasm/ClojureWasm BuddhiLW/ClojureWasm; do
  base="https://github.com/$repo/releases/download/$ver"
  curl -fsSLO "$base/$asset" && curl -fsSLO "$base/$asset.sha256" && break
done
sha256sum -c "$asset.sha256"
tar -xzf "$asset" cljw
install -m 755 cljw /usr/local/bin/cljw
cljw --version

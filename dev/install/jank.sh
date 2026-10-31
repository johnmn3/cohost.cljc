#!/usr/bin/env bash
# jank: no versioned releases; jank's own Ubuntu apt repository (book.jank-lang.org,
# getting-started/01-installation.md). Record the installed package version.
set -euo pipefail
curl -fsS "https://ppa.jank-lang.org/KEY.gpg" | gpg --dearmor > /etc/apt/trusted.gpg.d/jank.gpg
echo "deb [signed-by=/etc/apt/trusted.gpg.d/jank.gpg] https://ppa.jank-lang.org $(lsb_release -cs) main" > /etc/apt/sources.list.d/jank.list
apt-get update
apt-get install -y jank
dpkg-query -W jank

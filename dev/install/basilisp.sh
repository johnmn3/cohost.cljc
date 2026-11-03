#!/usr/bin/env bash
# basilisp 0.5.1 (the version the existing basilisp rows were measured on), from PyPI as a
# uv tool. Installs uv first when it is missing (Homebrew on macOS, astral's installer
# elsewhere).
source "$(dirname "$0")/_lib.sh"
ver=0.5.1
already basilisp version -- "$ver"
if ! have uv; then
  if [ "$os" = macos ] && have brew; then brew install uv
  elif [ "$os" = windows ]; then powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"
  else curl -LsSf https://astral.sh/uv/install.sh | sh; fi
  export PATH="$HOME/.local/bin:$HOME/.cargo/bin:$PATH"
fi
# Python 3.13: basilisp's dependency immutables has prebuilt wheels for it (the newest
# CPython uv would pick otherwise builds it from source, which needs a C compiler)
uv tool install --force --python 3.13 "basilisp==$ver"
export PATH="$(uv tool dir --bin):$PATH"
basilisp version

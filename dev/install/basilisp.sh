#!/usr/bin/env bash
# basilisp 0.5.1 (the version the existing basilisp rows were measured on), from PyPI.
set -euo pipefail
uv tool install basilisp==0.5.1
basilisp version

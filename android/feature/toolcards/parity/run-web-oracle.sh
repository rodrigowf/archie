#!/usr/bin/env bash
# Regenerates src/test/resources/web-tool-oracle.json from the committed web tool cards (W-10).
# The oracle runs in a temp dir whose node_modules links to frontend's (no install needed).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cp "$here/web-oracle.test.tsx" "$here/vitest.oracle.config.ts" "$tmp/"
ln -s "$repo/frontend/node_modules" "$tmp/node_modules"
cd "$tmp"
REPO_ROOT="$repo" ORACLE_OUT="$here/../src/test/resources/web-tool-oracle.json" \
  "$repo/frontend/node_modules/.bin/vitest" run --config vitest.oracle.config.ts --root "$tmp"

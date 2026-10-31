#!/usr/bin/env bash
# Extracts one version's notes from CHANGELOG.md for the GitHub Release body.
#
#   release-notes.sh <version> <output-file> [changelog]
#
# The section starts at a heading "## <version>" (optionally followed by
# " (date)") and ends at the next "## " heading. Fails, with an explanation,
# if there is no such section or it is empty, so a release can't go out with
# blank notes. Used by .github/workflows/release.yml.
set -euo pipefail

version="${1:?usage: release-notes.sh <version> <output-file> [changelog]}"
out="${2:?usage: release-notes.sh <version> <output-file> [changelog]}"
changelog="${3:-CHANGELOG.md}"

awk -v v="$version" '
  /^## / { if (found) exit; if ($2 == v) found = 1; next }
  found { print }
' "$changelog" > "$out"

if ! grep -q '[^[:space:]]' "$out"; then
  echo "::error::CHANGELOG.md has no notes under a heading \"## ${version}\"." >&2
  echo "Before releasing, open a small PR that renames the \"## Unreleased\" heading to \"## ${version} ($(date -u +%Y-%m-%d))\" and starts a new empty \"## Unreleased\" above it." >&2
  exit 1
fi

#!/usr/bin/env bash
# Publish immutable, already validated phone artifacts for the current clean tag.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
[[ $# == 1 ]] || { echo 'Usage: tools/publish-blackberry-release.sh vX.Y.Z' >&2; exit 2; }
tag=$1
[[ $tag =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Expected a vX.Y.Z tag' >&2; exit 2; }
[[ -z $(git status --porcelain) ]] || { echo 'Commit source changes before publishing' >&2; exit 2; }
[[ $(git rev-parse "$tag^{commit}") == $(git rev-parse HEAD) ]] || { echo 'Tag must identify current HEAD' >&2; exit 2; }
python3 tools/blackberry_release.py package --version "${tag#v}" --require-clean
bundle="target/releases/BerryBoy-Advance-${tag#v}-BBOS7.1.zip"
notes="target/releases/release-notes-$tag.md"
python3 - "$tag" > "$notes" <<'PY'
import sys
from pathlib import Path
version=sys.argv[1][1:]
text=Path('CHANGELOG.md').read_text()
heading='## '+version+' '
if heading not in text: raise SystemExit('Release version is missing from CHANGELOG.md')
section=text.split(heading,1)[1].split('\n## ',1)[0]
print('Compiled package for BlackBerry OS 7.1 / Bold 9900.\n')
print('Download the BBOS7.1 ZIP, extract all files together and follow INSTALL.txt. '
      'Includes raw sibling CODs, JAD, ALX, native MIDlet JAR, provenance and SHA-256 checksums.\n')
print(section.split('\n',1)[1].strip())
PY
# Do not overwrite an existing release or its assets.
gh release create "$tag" "$bundle" "$bundle.sha256" --repo pitrecki/berryboy-advance \
  --verify-tag --title "BerryBoy Advance ${tag#v}" --notes-file "$notes" --prerelease --draft
gh release edit "$tag" --repo pitrecki/berryboy-advance --draft=false

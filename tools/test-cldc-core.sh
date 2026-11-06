#!/usr/bin/env bash
# Requires the SDK build and dependencies resolved by test-with-console.sh.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
[[ -f target/blackberry/classes/berryboy/Machine.class ]] || { echo 'Build native CLDC classes first' >&2; exit 2; }
dependencies=$(cat target/junit-console/dependencies.txt)
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "target/blackberry/classes:target/test-classes:$dependencies" \
  org.junit.platform.console.ConsoleLauncher execute \
  --scan-class-path=target/test-classes --include-engine junit-jupiter --include-tag core \
  --exclude-package berryboy.build --exclude-package berryboy.desktop \
  --fail-if-no-tests --disable-banner --details=summary \
  --reports-dir target/junit-console/reports/cldc

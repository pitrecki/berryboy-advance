#!/usr/bin/env bash
# Convenience entry point for the standard Maven/JUnit lifecycle on JDK 21.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
exec mvn verify "$@"

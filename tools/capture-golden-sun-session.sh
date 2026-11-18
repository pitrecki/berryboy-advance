#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
[[ $# == 2 ]] || { echo 'Usage: tools/capture-golden-sun-session.sh AGSE.properties output.gif' >&2; exit 2; }
capture_dir="target/golden-sun-session-$(date -u +%Y%m%dT%H%M%S)"
classes="$capture_dir-classes"
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-$PWD/target/font-cache}"
mkdir -p "$XDG_CACHE_HOME" "$classes"
capture_dirty=false
if [[ -n "$(git status --porcelain)" ]]; then capture_dirty=true; fi
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" --release 21 -d "$classes" src/test/java/berryboy/build/PhoneHarness.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$classes" berryboy.build.PhoneHarness "$PWD" "$PWD/$classes"
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -Xlint:all -Werror --release 21 -cp "$classes:target/classes" -d "$classes" tools/CaptureLaunchWalkthrough.java tools/CaptureGoldenSunSession.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Djava.awt.headless=true -cp "$classes:target/classes" berryboy.desktop.CaptureGoldenSunSession "$1" benchmarks/inputs/golden-sun-m1.tsv "$capture_dir" "$(git rev-parse HEAD)" "$capture_dirty"
python3 tools/encode-showcase.py "$capture_dir" "$2"

#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
[[ $# == 2 ]] || { echo 'Usage: tools/capture-launch-walkthrough.sh AGSE.properties output.gif' >&2; exit 2; }
capture_dir="target/launch-$(date -u +%Y%m%dT%H%M%S)"
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-$PWD/target/font-cache}"
mkdir -p "$XDG_CACHE_HOME" target/phone-ui
capture_dirty=false
if [[ -n "$(git status --porcelain)" ]]; then capture_dirty=true; fi
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" --release 21 -d target/phone-ui src/test/java/berryboy/build/PhoneHarness.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp target/phone-ui berryboy.build.PhoneHarness "$PWD" "$PWD/target/phone-ui"
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" --release 21 -cp target/phone-ui:target/classes -d target/phone-ui tools/CaptureLaunchWalkthrough.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Djava.awt.headless=true -cp target/phone-ui:target/classes berryboy.desktop.CaptureLaunchWalkthrough "$1" "$capture_dir" "$(git rev-parse HEAD)" "$capture_dirty"
python3 tools/encode-showcase.py "$capture_dir" "$2"

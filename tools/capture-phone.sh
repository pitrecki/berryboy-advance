#!/usr/bin/env bash
# Reproduce phone component captures; requires a local ROM/BIOS mapping and matching state.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
[[ $# == 3 ]] || { echo 'Usage: tools/capture-phone.sh scenario.properties state.bbs outputFolder' >&2; exit 2; }
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-$PWD/target/font-cache}"
mkdir -p "$XDG_CACHE_HOME" target/phone-tools
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" --release 21 -encoding UTF-8 -cp target/classes -d target/phone-tools tools/CapturePhone.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Djava.awt.headless=true -cp target/phone-tools:target/classes berryboy.desktop.CapturePhone "$1" "$2" "$3"

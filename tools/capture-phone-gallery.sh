#!/usr/bin/env bash
# Render the phone gallery from locally captured frame checkpoints.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
[[ $# == 2 ]] || { echo 'Usage: tools/capture-phone-gallery.sh scenesFolder outputFolder' >&2; exit 2; }
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-$PWD/target/font-cache}"
mkdir -p "$XDG_CACHE_HOME" target/phone-tools
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" --release 21 -encoding UTF-8 -cp target/classes -d target/phone-tools tools/CapturePhone.java tools/CapturePhoneGallery.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Djava.awt.headless=true -cp target/phone-tools:target/classes berryboy.desktop.CapturePhoneGallery "$1" "$2"

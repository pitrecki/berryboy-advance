#!/usr/bin/env bash
# Capture the real Swing view and guest framebuffer, even on a headless Linux host.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
java_command="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_command="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
mkdir -p target/docs-tools
"$javac_command" --release 21 -encoding UTF-8 -cp target/classes \
  -d target/docs-tools tools/CaptureDocumentation.java
"$java_command" -Djava.awt.headless=true -cp target/docs-tools:target/classes \
  berryboy.desktop.CaptureDocumentation "$@"

#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
[[ $# == 2 ]] || { echo 'Usage: tools/capture-showcase.sh /path/to/scenarios output.gif' >&2; exit 2; }
capture_dir="target/showcase-$(date -u +%Y%m%dT%H%M%S)"
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-$PWD/target/font-cache}"
mkdir -p "$XDG_CACHE_HOME"
mkdir -p target/docs-tools
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" --release 21 -encoding UTF-8 -cp target/classes \
  -d target/docs-tools tools/CaptureDocumentation.java tools/CaptureShowcase.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Djava.awt.headless=true -cp target/docs-tools:target/classes \
  berryboy.desktop.CaptureShowcase "$1" "$capture_dir" benchmarks/showcase.tsv "$(git rev-parse HEAD)"
python3 tools/encode-showcase.py "$capture_dir" "$2"

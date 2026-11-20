#!/usr/bin/env bash
# Finite local-asset experiment, with immutable compiled classes per invocation.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
[[ $# == 3 && ($1 == prepare || $1 == run) ]] || { echo 'Usage: tools/phone-reliability.sh prepare|run scene.properties freshOutput' >&2; exit 2; }
mode=$1
scene=$(realpath -- "$2")
output=$(realpath -m -- "$3")
classes="$output-classes"
[[ ! -e $output && ! -e $classes ]] || { echo 'Use fresh experiment and class directories' >&2; exit 2; }
[[ -f target/classes/berryboy/desktop/reliability/FrameGate.class ]] || { echo 'Run Maven compile first' >&2; exit 2; }
java_command="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_command="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-$PWD/target/font-cache}"
mkdir -p -- "$classes/ui" "$XDG_CACHE_HOME"
cp -a -- target/classes "$classes/host"
"$javac_command" --release 21 -d "$classes/ui" src/test/java/berryboy/build/PhoneHarness.java
"$java_command" -cp "$classes/ui" berryboy.build.PhoneHarness "$PWD" "$classes/ui"
"$javac_command" --release 21 -cp "$classes/ui:$classes/host" -d "$classes/ui" tools/phone-reliability/*.java
python3 - "$classes" <<'PY'
from pathlib import Path
import hashlib,json,sys
root=Path(sys.argv[1])
digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
sources=list(Path('tools/phone-reliability').glob('*.java'))+list(Path('src/main/java/berryboy/desktop/reliability').glob('*.java'))
sources += [Path(p) for p in ['tools/phone-reliability.sh','src/main/java/berryboy/desktop/scenario/FrameInputs.java','src/test/java/berryboy/build/PhoneHarness.java','src/test/fixtures/phone-ui/berryboy/phone/UiHarness.java','src/test/fixtures/phone-ui/javax/microedition/lcdui/Canvas.java','src/test/fixtures/phone/javax/microedition/lcdui/Display.java','src/test/fixtures/phone-ui/javax/microedition/midlet/MIDlet.java','src/test/fixtures/phone/berryboy/phone/PhoneAudio.java','platform/midp/berryboy/phone/PhoneSession.java','platform/midp/berryboy/phone/PhoneCanvas.java','platform/midp/berryboy/phone/PhoneStorage.java']]
manifest={'sources':{str(p):digest(p) for p in sorted(sources)},'classes':{str(p.relative_to(root)):digest(p) for p in sorted(root.rglob('*.class'))}}
(root/'build-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
PY
arguments=("$scene" "$output")
main_class=PrepareReliabilityOracle
if [[ $mode == run ]]; then
  main_class=RunPhoneReliability
  dirty=false
  if [[ -n $(git status --porcelain) ]]; then dirty=true; fi
  arguments+=("$(git rev-parse HEAD)" "$dirty")
fi
"$java_command" -Xms256m -Xmx512m -XX:+UseG1GC -Djava.awt.headless=true \
  "-Dberryboy.reliabilityManifest=$classes/build-manifest.json" -cp "$classes/ui:$classes/host" \
  "berryboy.desktop.reliability.$main_class" "${arguments[@]}"

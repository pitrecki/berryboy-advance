#!/usr/bin/env bash
# Build the independent library and adapter with the same pixel ABI.
set -euo pipefail
project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
source_root=
output=
color_mode=rgb555
jobs=2
while (($#)); do
  [[ $# -ge 2 ]] || { echo "Missing value for $1" >&2; exit 2; }
  case "$1" in
    --source) source_root=$2;;
    --output) output=$2;;
    --color-mode) color_mode=$2;;
    --jobs) jobs=$2;;
    *) echo "Unknown option: $1" >&2; exit 2;;
  esac
  shift 2
done
[[ $color_mode == rgb555 || $color_mode == rgb888 ]] || { echo '--color-mode must be rgb555 or rgb888' >&2; exit 2; }
[[ $jobs =~ ^[1-9][0-9]?$ ]] || { echo '--jobs must be 1..99' >&2; exit 2; }
[[ -f "$source_root/include/mgba/core/core.h" && -f "$source_root/CMakeLists.txt" ]] || {
  echo 'Use --source with an extracted mGBA 0.10.5 source tree.' >&2; exit 2;
}
source_root=$(cd -- "$source_root" && pwd)
output=${output:-$project_root/target/mgba-reference/$color_mode}
compiler=${CC:-cc}
for tool in cmake "$compiler" python3; do command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 2; }; done
defines=()
[[ $color_mode == rgb888 ]] || defines=(-DCOLOR_16_BIT)
mkdir -p -- "$output"
output=$(cd -- "$output" && pwd)
cmake -S "$source_root" -B "$output/build" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_COMPILER="$compiler" "-DCMAKE_C_FLAGS=${defines[*]}" \
  -DLIBMGBA_ONLY=ON -DM_CORE_GBA=ON -DM_CORE_GB=OFF \
  -DBUILD_GL=OFF -DBUILD_GLES2=OFF -DBUILD_GLES3=OFF -DBUILD_LTO=OFF \
  -DBUILD_QT=OFF -DBUILD_SDL=OFF -DUSE_DEBUGGERS=OFF -DUSE_ELF=OFF \
  -DUSE_FFMPEG=OFF -DUSE_ZLIB=OFF -DUSE_MINIZIP=OFF -DUSE_PNG=OFF \
  -DUSE_LIBZIP=OFF -DUSE_LZMA=OFF -DUSE_SQLITE3=OFF -DUSE_LUA=OFF -DUSE_DISCORD_RPC=OFF
cmake --build "$output/build" --target mgba --parallel "$jobs"
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror -D_GNU_SOURCE -DBUILD_STATIC -DM_CORE_GBA -DUSE_PTHREADS \
  "${defines[@]}" -I"$source_root/include" -I"$output/build/include" \
  "$project_root/tools/mgba-capture.c" "$output/build/libmgba.a" -lm -lpthread -o "$output/mgba-capture"
python3 - "$source_root" "$output" "$project_root/tools/mgba-capture.c" "$color_mode" <<'PY'
import hashlib, json, pathlib, subprocess, sys
source, output, adapter = map(pathlib.Path, sys.argv[1:4])
mode = sys.argv[4]
description = json.loads(subprocess.check_output([str(output/'mgba-capture'), '--describe'], text=True))
if description['reference_pixel_format'] != mode.upper(): raise RuntimeError('Reference mode mismatch')
if description['emulator_version'] != '0.10.5': raise RuntimeError('Reference requires mGBA 0.10.5')
paths = [source/'include/mgba/core/interface.h', source/'src/gba/renderers/software-private.h',
         source/'src/gba/video.c', output/'build/CMakeCache.txt', output/'build/libmgba.a', adapter, output/'mgba-capture']
description['files_sha256'] = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
description['library_and_adapter_defines'] = ['COLOR_16_BIT'] if mode == 'rgb555' else []
(output/'reference-build.json').write_text(json.dumps(description, indent=2)+'\n')
print(json.dumps(description))
PY

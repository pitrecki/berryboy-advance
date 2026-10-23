# Uploaded-game compatibility smoke, updated 2026-10-23

## Regression after the timing and DMA corrections

All eleven titles completed fresh **3600-frame** captures after the Thumb MUL,
ROM DMA source and shared ownership corrections: **39600 guest frames** and
**21725550 stereo samples**, with zero missing guest frames or software audio
underruns/overruns. Each title used the original BIOS and Start/A input script,
a separate new backup path and no player save. Four titles wrote initialization
backup files. Original player saves were not modified.

The production core is `b4889b933d7982aae7c758e28869263b656d3580`; capture metadata
records source base `3d2f0b9` with pending diagnostic-only changes. This is a
headless boot/intro smoke, not eleven gameplay or independent correctness passes.
Brightness, full RGB/PCM agreement, longer gameplay and physical playback remain
open. Per-title assets, hashes, counters and prior-BerryBoy comparisons:
[dma-game-regression-2026-10-23.json](../benchmarks/dma-game-regression-2026-10-23.json).

The original imported/uploaded-game validation follows below.

LeafGreen also has a [second player-save route](REFERENCE-VALIDATION.md#second-leafgreen-player-save-and-cold-cross-load):
one-tile indoor movement, generation-2 save and four cold cross-loads between
BerryBoy and mGBA. Every restored room matches all pixels at the new position;
the two generated save files remain different in 667 bytes. This is a narrow
progress/save result, not an overworld or complete gameplay qualification.

Eleven user-uploaded ZIPs supplied eleven unique USA-region ROMs (LeafGreen is
Rev-A). ZIP CRC verification and GBA header checksums passed; SHA-256 identities
were recorded. This verifies local asset identity, not independent dump provenance.
ROMs, BIOS, PNG/WAV captures and personal saves are excluded from Git.

Tested source: `1d9f541bde42b267937f692a40693b32a44af1c7`, clean working tree.
Host: Linux x86_64, Java 21.0.8+9. Full per-game hashes, counters, runtime metadata,
errors and replay evidence are in
[the machine-readable record](../benchmarks/uploaded-games-smoke-2026-10-22.json).

## Longer independent-reference validation

All eleven games also completed 3600-frame fresh-state captures. Independent
mGBA video/audio comparison now runs for every title and reports mismatches;
four game-written initialization saves match mGBA byte for byte. See
[REFERENCE-VALIDATION.md](REFERENCE-VALIDATION.md) for protocol, counts and limits.

## BIOS/open-bus follow-up

All eleven games now complete 1200 frames with zero missing guest frames and
zero software audio underruns/overruns, using the original input script and BIOS.
Each run records 658350 stereo samples. Metroid Fusion now passes the former
428-frame failure and reaches its animated space intro. Two Metroid captures
have byte-identical frame traces, WAV audio and final PNGs. Evidence and exact
asset/input hashes are in [the current record](../benchmarks/game-bios-bus-2026-10-22.json),
recorded against dirty source base `0d238b3`. Validation: 1017 host and 943
exported-core tests passed. Cold work deadline misses remain; full performance
and independent video/audio correctness are not qualified.

The failing sound channel has a zero waveform pointer and zero
volume. mGBA 0.10.5 also processes it and continues. The GBA prevents game code
from directly reading BIOS bytes: data reads retain the last BIOS prefetch word,
while unmapped addresses above the 16 KiB BIOS expose CPU prefetch byte lanes.
The bus now models these two behaviors; unimplemented I/O still fails explicitly.
Original tests cover protected byte/halfword/word reads, BIOS reentry, ARM load
sign extension and Thumb instruction-bus layouts. DMA open-bus latches and
self-modifying prefetch behavior remain unqualified.

Four ARM signed-byte observations match mGBA at the same mixer instruction:

| Read address | BerryBoy result | mGBA result |
| --- | --- | --- |
| `0x4007` | `0xffffffe0` | `0xffffffe0` |
| `0x4cd2` | `0x00000044` | `0x00000044` |
| `0x599d` | `0x00000040` | `0x00000040` |
| `0x6668` | `0x00000003` | `0x00000003` |

This is a bus spot check, not agreement of complete guest state. At the first
zero-waveform checkpoint BerryBoy retains BIOS word `0xe3a02004`, whereas mGBA
retains `0xe55ec002`; the recorded guest cycles also differ. Compare IRQ/SWI event
ordering and latch timing before making a full timing claim.
The reproducible standalone reference tool is
[mgba-fusion-probe.c](../tools/mgba-fusion-probe.c). With the local static mGBA
0.10.5 build used for this investigation, compile/run from the checkout:

```bash
cc -Wall -Wextra -Werror -O2 -DBUILD_STATIC -DM_CORE_GBA -DUSE_PTHREADS \
  -I/workspace/toolchains/mgba-reference/mgba-0.10.5/include \
  -I/workspace/toolchains/mgba-reference/build/include \
  tools/mgba-fusion-probe.c /workspace/toolchains/mgba-reference/build/libmgba.a \
  -lm -lpthread -o /tmp/mgba-fusion-probe
/tmp/mgba-fusion-probe '/workspace/roms/gba/Metroid Fusion (USA).gba' \
  /workspace/roms/gba/gba_bios.bin
```

The mGBA source, build and CMake installation stay outside Git. Its diagnostic
is version/ROM specific and does not replace the emulator regression suite.
The following sections retain the earlier failing evidence for comparison.

## DMA deadline follow-up (before BIOS/open-bus fix)

The whole-block DMA implementation advanced 458754 cycles in a single CPU step
in Metroid's startup clear, crossing a capture deadline. Four other titles
showed the same two-count anomaly. Devices kept running, but the host caller
could not observe each completed frame and then captured the same frame again
while catching up. DMA now yields after each transfer unit while retaining
channel ownership and priority. CPU instructions stay blocked until DMA finishes.
Original regressions cover a 65536-word transfer across a frame budget and
higher-priority preemption followed by resumption. Total DMA cycles are unchanged.

All eleven 1200-frame attempts were repeated with the original assets and input.
Ten now report `captured`, zero missing guest frames and zero software audio
buffer failures. Metroid Fusion retains the same exception; this separate defect
is not resolved by the DMA change. Evidence is in
[the follow-up record](../benchmarks/game-dma-resume-2026-10-22.json), tagged with
its actual dirty source base (`101b3fe`). Validation: 1005 host and 931
exported-core tests passed. Short host runs still do not qualify performance.

## Protocol and outcome (baseline)

All eleven games completed an initial 360-frame real-BIOS capture without an
exception. Six reports had no frame/audio counter failures; Wario Land 4, GTA,
Zone of the Enders and both Metroids each reported two missing guest frames.
All initial runs had zero software audio underruns/overruns. A diagnostic run without
BIOS failed for every game: eight required unavailable HLE SWI services, while
V-Rally and both Metroids accessed unavailable BIOS memory. This direct-entry
path is a development diagnostic, not the supported commercial-game boot path.

The longer test uses 1200 guest frames (about 20.1 seconds), real BIOS, no warmup,
no initial save and no peripheral configuration. At frames 360, 420, ..., 1140,
alternate Start and A, holding each for three frames. Capture every guest frame
and stereo PCM at 32768 Hz. These generic inputs do not constitute game-specific
routes or complete gameplay tests.

Ten runs completed without an exception; six reported `captured`, four reported
`failed` because each counted two missing guest frames, and Metroid Fusion raised
an exception after 428 frame records. Do not equate process exit zero with a
passing report. Every completed long run captured 658350 stereo samples and
zero software audio underruns/overruns, but had 15–78 cold work deadline misses.
No run establishes sustained performance, correct sound, physical presentation,
or BlackBerry compatibility. Independent reference captures are absent.

| Game | Code | Frame records | Report outcome | Missing guest frames | Observed final checkpoint |
| --- | --- | ---: | --- | ---: | --- |
| Golden Sun | `AGSE` | 1200 | captured | 0 | Interior scene with player character |
| Golden Sun - The Lost Age | `AGFE` | 1200 | captured | 0 | Black final checkpoint; progression needs investigation |
| V-Rally 3 | `AVRE` | 1200 | captured | 0 | Developer credit screen |
| Pokemon Emerald Version | `BPEE` | 1200 | captured | 0 | Black final checkpoint; progression needs investigation |
| Pokemon LeafGreen Version | `BPGE` | 1200 | captured | 0 | Professor introduction dialogue |
| Sonic Advance 3 | `B3SE` | 1200 | captured | 0 | Title screen with PRESS START |
| Wario Land 4 | `AWAE` | 1200 | failed | 2 | Animated intro scene; guest-frame counter anomaly |
| Grand Theft Auto Advance | `BGTE` | 1200 | failed | 2 | EMPTY FILE save selection; guest-frame counter anomaly |
| Zone of the Enders - The Fist of Mars | `AZEE` | 1200 | failed | 2 | Intro scene; guest-frame counter anomaly |
| Metroid Fusion | `AMTE` | 428 | exception | n/a | Exception after 428 captured frames |
| Metroid - Zero Mission | `BMXE` | 1200 | failed | 2 | Black final checkpoint; guest-frame counter anomaly |

Black final checkpoints do not alone establish a hang; the last 120 checkpoints
still contain changing images for these games. They require scene-specific
inputs and further inspection. Other visible checkpoints are observations,
not pixel-accuracy judgments.

A second Golden Sun run matched the first run's entire video/PCM hashes.
This is replay stability against the same emulator, not independent correctness.
Metroid Fusion repeated the same 428-frame trace and exception:
`Unimplemented bus / IO address 0x4179 [opcode=0xe1b530d9, pc=0x30037ac]`.
The bus behavior responsible is now isolated and corrected as described above.

## Local reproduction

Assets and their manifest are under `/workspace/roms/gba`. Exact scenario
properties, inputs, logs and summaries are preserved under
`/workspace/roms/gba/validation/2026-10-22/`. Original captures remain in
`target/uploaded-games-smoke-2026-10-22` and
`target/uploaded-games-input-smoke-2026-10-22`. Current captures and reports are
in `target/game-bios-bus-2026-10-22`; persistent properties/logs are in the
`bios-bus` subdirectory of the validation directory above.

After building and sourcing `/workspace/toolchains/activate.sh`, choose a copied
scenario, change its `output` to a new empty directory, and run:

```bash
java -Djava.awt.headless=true -cp target/classes \
  berryboy.desktop.ScenarioRunner /path/to/scenario.properties
```

The persisted properties point to the preserved input recording. Hash checks
remain enabled. Do not reuse a nonempty output directory. No binary assets or
screenshots are committed.

## Next compatibility work

The canonical [next roadmap and concrete commits](BENCHMARKS.md#next-roadmap-correctness-gameplay-then-sustained-playback)
uses the eleven independent longer-run comparisons and LeafGreen player-save
evidence. The Continue-menu difference is diagnosed as reference color-effect
precision; its exact RGB gate stays open. Next are BIOS/LCD and bus timing,
Sonic/Wario audio differences, then real gameplay and repeated player saves. Independent references and four initialization-save reloads already
exist; they are not pending asset collection.

Preserve the Metroid Fusion BIOS/open-bus and incremental DMA deadline/priority
regressions throughout. The [reference validation report](REFERENCE-VALIDATION.md)
distinguishes the exact LeafGreen restored-room checkpoint from the still
mismatching menu and full streams. Long warmup/measurement runs and SDK/device
qualification follow correctness; see the canonical plan for their gates.

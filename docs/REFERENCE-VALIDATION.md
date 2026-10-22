# Independent reference validation, 2026-10-22

## Scope

Eleven uploaded USA ROMs were run for 3600 native guest frames (about 60.27
seconds). Source: clean `dd6a780bdccf1b3085c982b722c2d2c1656ada64`, Linux x86_64,
Java 21.0.8+9. Each run starts with fresh backup memory and the supplied BIOS,
uses the recorded Start/A pulses until frame 1143, then leaves all buttons
released. This is longer boot/intro/attract-mode coverage, not a fixed gameplay
route or sustained performance qualification. Asset, input, runtime and output
hashes and actual work deadlines are in
[the evidence record](../benchmarks/long-reference-2026-10-22.json).

All eleven runs completed with zero missing guest frames, zero software audio
underruns/overruns and 1975050 stereo samples at 32768 Hz. Physical playback,
video presentation, audible quality and BlackBerry performance remain unverified.

## Independent comparison

mGBA 0.10.5 was built locally with the software renderer and audio HLE disabled.
The reference adapter schedules capture and input callbacks on native guest
clock deadlines; polling its instruction-step API can overshoot a deadline while
HALTed and must not be used for this comparison. Captures preserve RGB888 and
stereo PCM16 at 32768 Hz, default peripherals disabled and RTC epoch 2000-01-01
UTC. No initial save is loaded. mGBA uses blip_buf audio filtering; BerryBoy uses
its current sample clock. Exact PCM hashes are diagnostic rather than a
perceptual audio-quality metric. No tolerance or skipped-frame matching is used.

Every complete video/audio comparison currently reports **mismatch**. Counts
below identify exact per-frame RGB matches. They are not compatibility scores:
static screens can produce many matches, while one shifted animation frame can
produce many differences. First-frame mismatches expose the initial display
reset state; later timing/rendering/audio differences still require diagnosis.
Reference audio totals differ by only two samples at the end of these runs,
consistent with the respective output/flush phases, not a minute of proven A/V
synchronization. No audio drift or perceptual quality qualification is claimed.

| Game | Missing guest frames | Audio underruns/overruns | Exact RGB frames | Reference minus BerryBoy samples | Comparison |
| --- | ---: | --- | ---: | ---: | --- |
| Golden Sun | 0 | 0/0 | 910/3600 | +2 | mismatch |
| Golden Sun - The Lost Age | 0 | 0/0 | 2132/3600 | +2 | mismatch |
| V-Rally 3 | 0 | 0/0 | 3230/3600 | -2 | mismatch |
| Pokemon Emerald Version | 0 | 0/0 | 2970/3600 | +2 | mismatch |
| Pokemon LeafGreen Version | 0 | 0/0 | 3474/3600 | +2 | mismatch |
| Sonic Advance 3 | 0 | 0/0 | 2811/3600 | -2 | mismatch |
| Wario Land 4 | 0 | 0/0 | 2143/3600 | +2 | mismatch |
| Grand Theft Auto Advance | 0 | 0/0 | 3408/3600 | +2 | mismatch |
| Zone of the Enders - The Fist of Mars | 0 | 0/0 | 577/3600 | -2 | mismatch |
| Metroid Fusion | 0 | 0/0 | 698/3600 | +2 | mismatch |
| Metroid - Zero Mission | 0 | 0/0 | 3195/3600 | -2 | mismatch |

## Save initialization

Sonic Advance 3 (64 KiB Flash), Wario Land 4 (32 KiB SRAM), Metroid Fusion
(32 KiB SRAM) and Zero Mission (32 KiB SRAM) wrote non-erased backup data.
All four files are **byte-identical to the corresponding independent mGBA
files**. These runs establish initialization-write compatibility. They do not
establish a manually saved game, durable reload, or progress restoration.
The other seven runs did not write a save file; they are not counted as save
successes. Save data and game captures remain outside Git.

## Reproduction

[mgba-capture.c](../tools/mgba-capture.c) links against a local mGBA 0.10.5 static
library and emits a bounded binary RGB/PCM stream. Compile it using the same
include/library flags as [the bus probe](GAME-COMPATIBILITY.md), replacing the
source with `tools/mgba-capture.c`. Its CLI accepts ROM, BIOS, decimal input TSV,
frame count (1..7000), save output, device mask (0 or RTC=1), and optional initial
save. The wrapper uses an isolated temporary save to preserve the input file.

[reference_capture.py](../tools/reference_capture.py) requires Python 3 and
Pillow. It validates asset hashes, ordered frame IDs and payload bounds, streams
WAV/trace output and saves checkpoints every 300 frames. It rejects truncated
streams and mismatched ROM/BIOS/input/device/RTC/save identities. Exit status 1
means a completed comparison differs; process/format errors fail separately.
Use a new output directory on every invocation:

```bash
python3 tools/reference_capture.py \
  --binary /workspace/toolchains/mgba-reference/mgba-capture \
  --scenario /path/to/fresh-scenario.properties \
  --output target/reference-new --frames 3600 \
  --compare /path/to/berryboy-capture
```

For reload comparisons, pass both `--initial-save` and its
`--initial-save-sha256`. Use the same copied initial save for BerryBoy. Never
reuse a scenario's final save as its initial state implicitly.

Raw artifacts are under `target/long-compatibility-2026-10-22` and
`target/independent-reference-2026-10-22`; the local reference installation is
under `/workspace/toolchains/mgba-reference`. The reference adapter also uses
finite bounded pipe backpressure; no live guest buffers cross threads.

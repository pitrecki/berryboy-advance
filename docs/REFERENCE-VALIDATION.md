# Independent reference validation, 2026-10-23

## FIFO DMA timing reference counterexample

The four-cycle discrepancy below now has an original, asset-free reproducer.
It exposes a count-dependent timing defect in the unmodified mGBA 0.10.5
reference. **That reference is not an absolute FIFO DMA timing oracle.**
BerryBoy's production timing and the reference library remain unchanged.

[GBATEK's Sound DMA section](https://github.com/mgba-emu/gbatek/blob/64b5087aa45cd0187b8b239d77e54ee5eb2917d1/index.md#sound-dma-fifo-timing-mode-dma1-and-dma2-only)
specifies four 32-bit units per FIFO request, with the Word Count register
ignored. The paired [Java](../tools/FifoDmaTimingProbe.java) and
[mGBA](../tools/mgba-fifo-dma-probe.c) probes inject a FIFO request at cycle zero,
allow service after 0–3 cycles, and retire one IWRAM Thumb ADD afterwards.
They cover eight count values, IWRAM/EWRAM/ROM sources and one/two channels:
**192 observations per emulator**. They generate their cartridge data in memory
and load no BIOS or commercial ROM. The C probe calls the normal audio FIFO
request path and passively observes the normal DMA scheduler; the controlled
delay is injected directly, rather than claimed as a complete timer/CPU workload.

Changing only the ignored count alters mGBA's final cycle in **20/24 parameter
groups**. BerryBoy is count-invariant in **24/24 groups**. For IWRAM sources
and a two-cycle service delay, cycles from request to the end of the ADD are:

| DMA channels | Programmed count | BerryBoy | mGBA 0.10.5 |
| --- | --- | --- | --- |
| 1 | 0 | 13 | 11 |
| 1 | 4 | 13 | 13 |
| 1 + 2 | 0 | 23 | 19 |
| 1 + 2 | 4 | 23 | 21 |

Both mGBA channels still transfer exactly four words and advance their source
by 16 bytes. In `src/gba/dma.c`, `_dmaEvent` resets the scheduled start to the
current time when `nextCount == count`; `GBADMAService` uses the same equality
to choose first/nonsequential bus costs. FIFO requests set `nextCount=4`, but
retain the programmed `count` (zero becomes 16384). The supposedly ignored
register therefore affects overdue-event handling and ROM wait-state selection.
Counts 1–3 can also trigger the first-transfer branch partway through a burst.

The two-channel/count-zero case reproduces the earlier BIOS step exactly:
relative to request cycle 1882527, BerryBoy finishes at +23 and mGBA at +19.
The reference's two-cycle overdue-event credit depends on the ignored count;
its next channel also starts before the preceding completion event. For count
4, the remaining pair discrepancy is two cycles. This identifies the reference
artifacts behind the comparison, without proving the hardware's channel-handoff
latency or every BerryBoy request/IRQ boundary. Obtain an independent timing
source or original hardware measurements before changing production timing.

The new **192 core regressions** vary the same parameters over **two repeated
bursts**. They check every byte through separate left/right FIFO PCM, source
progress, empty FIFO after 16 samples, control readback, CPU exclusion and
resumption. Latency is compared against count=4, without imposing an unqualified
absolute cycle value. All pass on the existing production implementation.

Validation: **1287 host tests, 1193 exported-core tests and 1193 actual ECJ CLDC
core tests** pass without skips. Native SDK/RAPC compilation passes. No fresh
game replay was needed because production sources are unchanged; the latest
1272-frame replay remains the prior commit's **1111/1272** exact RGB frames,
first mismatch **930**, with unmatched PCM. That initialization offset is open.
Reproduction commands are in [SCENARIOS.md](SCENARIOS.md#original-fifo-dma-timing-probes).
Evidence: [fifo-dma-reference-2026-10-23.json](../benchmarks/fifo-dma-reference-2026-10-23.json).

## DMA Game Pak source addressing and next timing boundary

DMA previously applied fixed or decrementing source modes to Game Pak ROM.
The controller now forces source increment throughout `0x08000000–0x0dffffff`,
including the three wait-state windows, while preserving programmed control
readback. RAM transfers retain their selected source mode. The effective mode
also controls sequential source bus accesses; the destination mode is unchanged.

An [original standalone mGBA probe](../tools/mgba-dma-source-probe.c) builds a
512-byte pattern cartridge in memory, with no commercial ROM or BIOS. Its
24 cases cover the three ROM windows plus EWRAM, both transfer widths and
increment/decrement/fixed source modes. All three ROM modes copy successive
pattern values; EWRAM distinguishes all three modes. Matching Java regressions
check every transferred halfword, control readback, completion and CPU exclusion.
Before correction, **12/24 cases fail**; afterwards all pass. Compile and run the
probe using the Thumb probe command below, substituting `mgba-dma-source-probe`
for `mgba-thumb-mul-probe`. This verifies transfer contents against unmodified
mGBA 0.10.5; it does not independently qualify DMA latency.

The next local BIOS walk aligns **retired instructions**, servicing BerryBoy
DMA/HALT/IRQ steps until its instruction counter advances. PC/cycle samples
agree through zero-based row 796678. At row **796679**, the Thumb ADD at BIOS
`0x1a0a` produces matching registers and next PC `0x1a0c`, but BerryBoy ends at
cycle **1882550**, versus **1882546** in mGBA. Both start at cycle 1882529.
Device diagnostics locate two four-word FIFO DMA transfers from IWRAM before
the ADD. BerryBoy spends 20 cycles servicing them and one on the instruction;
the reference step takes 17 cycles including DMA processing. Its scheduled
transfer/completion events differ from BerryBoy's synchronous startup charges.
This isolates a four-cycle DMA scheduling discrepancy, without establishing
which ownership/event boundary should change. The ROM-address correction is
separate: these FIFO sources are in IWRAM. No startup constant was adjusted.

A fresh 1272-frame LeafGreen continuation preserves the previous entire RGB
and PCM hashes, all selected video snapshots and the player save. It still
matches **1111/1272** RGB frames, first differing at **930**. The initialization
offset and DMA event-order investigation remain open. No frame tolerance or
hardware/runtime pass is inferred.

Validation: **1095 host tests, 1001 exported-core tests and 1001 actual ECJ CLDC
core tests**, no failures or skips. The native `bb-sim` build emits a COD with
RAPC reporting no errors. Probe observations, trace identities and replay evidence:
[dma-source-addressing-2026-10-23.json](../benchmarks/dma-source-addressing-2026-10-23.json).
Local replay output: `target/dma-source-followup-2026-10-23/`.

## Thumb multiply timing correction

The first isolated cycle discrepancy in a real-BIOS instruction walk follows
the Thumb multiply at BIOS PC `0x1736`, with original Rd=597275 and Rs=224.
At zero-based step 590519, both emulators start at cycle 1404705 and produce
the same product, 133789600. BerryBoy previously ended at 1404707, whereas
unmodified mGBA 0.10.5 ends at 1404709. All preceding pre-step PC/cycle samples
agree. Reset-mode/banked-stack defaults differ initially, so this is not a claim
that every CPU register matches throughout reset.

Thumb MUL maps to ARM `MULS Rd,Rs,Rd`. Its early termination depends on the
original destination value, not the other source. BerryBoy had timed Rs,
charging one internal iteration instead of three for this case. The timing
decoder now reads original Rd before execution overwrites it. The product and
flag implementation is unchanged; ARM multiply operand selection is unchanged.

An [original asset-free mGBA probe](../tools/mgba-thumb-mul-probe.c) executes
16 IWRAM Thumb MUL cases without any BIOS or cartridge. It independently
confirms 1–4 internal iterations for positive and negative boundaries, opposite
operand sizes and the BIOS operand pair. The matching Java regressions also
check result, source preservation, PC, N/Z/C/V and an intervening device deadline.
Before the fix, 16/17 regressions fail. After it, all pass, and a fresh BIOS walk
matches the reference's first **600000 pre-step PC/cycle samples**. Longer walks
must account for different DMA/HALT/IRQ step semantics before aligning rows.

Compile the standalone probe against the RGB888 library built by
`tools/build-mgba-reference.sh` (no `COLOR_16_BIT`):

```bash
cc -std=c11 -D_GNU_SOURCE -O2 -Wall -Wextra -Werror \
  -DBUILD_STATIC -DM_CORE_GBA -DUSE_PTHREADS \
  -I/path/to/mgba-0.10.5/include -I/path/to/reference-rgb888/build/include \
  tools/mgba-thumb-mul-probe.c /path/to/reference-rgb888/build/libmgba.a \
  -lm -lpthread -o /tmp/mgba-thumb-mul-probe
/tmp/mgba-thumb-mul-probe
```

The 1272-frame LeafGreen continuation still has **1111 exact RGB frames**,
first mismatch at 930, 820 different VRAM bytes there and the same whole RGB
hash. The original and isolated save remain byte-identical. PCM changes with
the corrected cycle costs and remains unmatched; zero guest frames or software
audio buffers are lost. This fixes a measured instruction-timing defect, while
the roughly one-frame initialization offset remains open. No frame alignment,
pixel tolerance or LCD constant was changed.

Validation: **1071 host tests, 977 exported-core tests and 977 actual ECJ CLDC
core tests** pass without skips; the native `bb-sim` profile emits a COD with
RAPC reporting no errors. Evidence, original probe output, assets and hashes:
[thumb-mul-timing-2026-10-23.json](../benchmarks/thumb-mul-timing-2026-10-23.json).
Raw walks and capture files remain in `target/timing-followup-2026-10-23/`.

## RGB555 reference qualification

The independent mGBA 0.10.5 source was built in two separate directories: an
unmodified RGB888 control and an unmodified RGB555 build. The library and adapter
use the same pixel ABI in each build. Only the compiler color define changes;
no upstream effects, CPU, timing or game state code is patched. The adapter
expands the RGB555 renderer's finished pixels to RGB888 and declares precision
in its stream. It does not quantize the previous RGB888 output.

| Capture | RGB888 exact frames | RGB555 exact frames | First RGB555 mismatch |
| --- | --- | --- | --- |
| 12-frame real-BIOS cold start | 9/12 | **12/12** | None |
| 1272-frame copied-save continuation | 991/1272 | **1111/1272** | Frame **930** |

The startup result confirms the earlier alpha-precision diagnosis for this
capture. The continuation's menu frame 1029 still differs in **19632 pixels**.
Its raw video state matches in both modes, and all RGB555 differences follow
mGBA's upstream packed-channel `_darken` arithmetic. For backdrop `0x7e51`
and BLDY=7, BerryBoy retains native `(10,11,18)` and expands to `(82,90,148)`;
mGBA RGB555 produces `(10,10,17)`, expanded to `(82,82,140)`. The shifted green
and blue fields are divided before masking, producing different rounding.
The [GBATEK channel equation](https://mgba-emu.github.io/gbatek/#lcd-color-special-effects)
and the existing original regression operate on unshifted 5-bit intensities.
BerryBoy's arithmetic and the upstream reference remain unchanged. **The menu
pixel gate is still open.** The RGB555 build alone does not qualify brightness.

Room frame 1272 still matches all 38400 pixels in both modes. The original save,
isolated BerryBoy save and both reference backup snapshots retain the same
128 KiB SHA-256. The two mGBA modes have byte-identical PCM, CPU/cycle/frame
coordinates, checkpoint state and startup LCD traces. The fresh RGB888 build
also preserves the prior reference streams. These controls distinguish the
pixel format change from changes in emulated execution.

The remaining first RGB555 difference at frame 930 is more than color rounding:
BerryBoy's completed image is white, the reference's is darkened, and their
VRAM snapshots differ in 820 bytes. Bounded LCD traces over guest frames
929–932 contain 3650 rows each with no truncation. BLDY=7 is first observed
in mGBA at cycle **260920128** (VCOUNT=201), while BerryBoy first shows it at
**261200976** (VCOUNT=201). These are sampling boundaries, not exact write times.
The game reaches this initialization step about one frame later in BerryBoy.
CPU/bus/IRQ ordering must be isolated before changing guest timing. No frame
offset or pixel tolerance was applied.

PCM remains unmatched: startup has 6583 BerryBoy versus 6584 reference stereo
samples, and continuation 697851 versus 697852. Headless RGB checkpoint matches
do not establish complete gameplay, audio correctness or physical playback.

Validation: `mvn verify` passes **1054 tests** without skips; both reference
adapters compile with `-Wall -Wextra -Werror`. Asset-free stream checks preserve
RGB/PCM payloads for legacy RGB888 and both declared formats, and reject unknown,
inconsistent and truncated headers. Invalid build modes leave outputs untouched;
RGB565 compilation is rejected. Reproduce the builds and stream protocol using
[SCENARIOS.md](SCENARIOS.md#reference-pixel-precision).
Evidence and asset/build identities:
[rgb555-reference-2026-10-22.json](../benchmarks/rgb555-reference-2026-10-22.json).
Raw outputs remain in `target/native-color-reference-2026-10-22/final/`, outside Git.

## BIOS startup event diagnosis

This section retains the RGB888 baseline. The RGB555 follow-up above explains
why its first 12 RGB frames now match while PCM and broader timing remain open.

LeafGreen USA Rev-A was cold-booted with the real BIOS for 12 frames, empty
input, no initial save and the default stationary peripherals/RTC. Both
BerryBoy and mGBA 0.10.5 now support bounded LCD before/after snapshots.
Each recorded **10944 rows, zero dropped**. Enabling diagnostics preserves each
emulator's entire RGB stream, PCM stream, sample count and frame/CPU checkpoints
byte for byte. The independent comparator still reports 9/12 exact frames and
the first RGB mismatch at frame 10; no tolerance or timing constant changed.

At frame 10, **all 100478 raw video-state bytes match**, including registers,
palette, VRAM and OAM. Exactly **682 pixels** differ: BerryBoy `(255,222,255)`
versus the reference `(255,223,255)`; the other 37718 are identical white.
Visible-line traces in both emulators show `BLDCNT=0x3f5f` and
`BLDALPHA=0x0e02` (EVA=2, EVB=14). Mixing native magenta `0x7c1f` over white
`0x7fff` gives native green `floor(31*14/16)=27`, expanded to 222. The mGBA
RGB888 branch of `mColorMix5Bit` instead gives `floor(255*14/16)=223`.
These two arithmetic predictions explain every differing pixel. The final
snapshot has already advanced to `BLDALPHA=0x0d03`; using that value to explain
the completed image would be incorrect. An original asset-free regression
preserves native alpha rounding in both software and timed raster output.

This explains the checked title's first RGB divergence as a reference color
precision difference, rather than demonstrating a timing defect. It does not
qualify other titles or close the exact RGB reference gate. Native-precision
independent effect validation remains necessary, as for the menu below.

Timing questions remain measurable: HBlank begins at cycle 960 in BerryBoy and
1008 in mGBA; both advance the line at multiples of 1232, with matching line
advance deadlines throughout this capture. Initial DISPSTAT VCounter flags and
some CPU checkpoints differ. A 48-cycle HBlank phase difference alone is not
evidence to change the drawing constant. These callback snapshots do not yet
isolate CPU bus/IRQ/BIOS ordering, and PCM still differs (6583 versus 6584 stereo
samples over 12 frames). No guest-timing fix or hardware pass is claimed.

Validation: **1054 host tests, 960 exported-core tests, 960 actual ECJ CLDC-class
core tests**, all passed without skips. The full native `bb-sim` profile also
produces a COD with no RAPC errors. The reference adapter compiles with
`-std=c11 -Wall -Wextra -Werror -D_GNU_SOURCE` and the existing mGBA include/link
flags. Scenario settings and TSV semantics are documented in
[SCENARIOS.md](SCENARIOS.md#bounded-guest-event-diagnostics).
Asset identities, hashes, observed boundaries and arithmetic evidence:
[bios-startup-diagnosis-2026-10-22.json](../benchmarks/bios-startup-diagnosis-2026-10-22.json).
Raw output remains outside Git in `target/timing-startup-2026-10-22/`.

## LeafGreen menu color-precision diagnosis

This section records the earlier RGB888 comparison. The follow-up above also
measures unmodified RGB555 and its remaining packed-channel brightness rounding.

The recorded 1272-frame continuation was replayed with selected video checkpoints
at frames 1029 and 1272. Host validation passed **1040 tests**, with **957** passing
again against the isolated exported core. The mGBA adapter also compiles with
`-Wall -Wextra -Werror`. Capture of the extra state preserves the previous entire
RGB and PCM stream hashes in both emulators and leaves the player save unchanged.

At menu frame 1029, **all raw video I/O, palette, VRAM and OAM bytes match**.
The menu's darkening is configured by `BLDCNT=0x00ff`, `BLDY=7`, with effects
disabled inside WIN0 and enabled outside. All **19632 differing pixels** can be
reproduced by the loaded BG palette and the two implementations' color arithmetic:

- BerryBoy applies `I - ((I * 7) >> 4)` to each native 5-bit channel, then expands
  to RGB888. Backdrop `0x7e51`, native `(17,18,31)`, becomes `(10,11,18)`, or
  RGB888 **(82,90,148)**.
- The mGBA 0.10.5 RGB888 software build expands first. Its `_darken` in
  `src/gba/renderers/software-private.h` computes with packed 8-bit lanes, giving
  **(79,83,143)** for the same backdrop. Other palette pairs explain every
  remaining difference. This also exposes lane-dependent rounding in that path.

An original asset-free regression locks down native-channel darkening through
both `SoftwarePpu` and `RasterPpu`. The renderer's arithmetic has not been changed
for this diagnosis, and no tolerance, pixel replacement or alignment was added to
the comparator. **The exact-menu RGB gate still fails**: native-precision
independent references or hardware evidence are needed before qualifying effects.
Whole-stream PCM and other video mismatches remain open.

Room frame 1272 still matches all 38400 pixels. Its palette/VRAM/OAM also match,
but DISPSTAT/VCOUNT differ at the nominal boundary (BerryBoy `0x9628`/0, mGBA
`0x962a`/227). Both report cycle 357299712 with different PC/CPSR. This is evidence
for the next timing/capture-phase investigation, not a demonstrated PPU defect
or permission to ignore status bits. Snapshots describe final state only.

Metadata, color pairs and per-region hashes:
[leafgreen-menu-diagnosis-2026-10-22.json](../benchmarks/leafgreen-menu-diagnosis-2026-10-22.json).
Local output/configuration is under `target/leafgreen-menu-diagnosis/`; raw game
images/state/save data remain outside Git. Reproduce using the asset identities
and recorded continuation in the player-save section below, fresh output paths,
`checkpoints=1029,1272` in the runner, and `--checkpoints 1029,1272` in the
reference capture. Compare the paired states with `tools/compare_video_state.py`;
see [SCENARIOS.md](SCENARIOS.md#selected-video-checkpoints) for the format and scope.

## Verified player save and cross-emulator continuation

LeafGreen USA Rev-A has a recorded route from a fresh game to a manual player
save: choose a character/name, enter the upstairs bedroom, move right, open Save,
confirm it, and wait for **LEAF saved the game**. The 6950-frame route was replayed
automatically and produced the exact same 128 KiB Flash file and confirmation
image as the interactive run. All 14 save sections have generation counter 1.

Fresh BerryBoy and mGBA instances both accept an isolated copy of that file.
Both show **CONTINUE, PLAYER LEAF** and restore the character in the saved bedroom
position. The frame-1272 room checkpoint matches **all 38400 pixels**. The loaded
save remains unchanged and matches the mGBA backup snapshot byte for byte; the
original file is preserved. The menu checkpoint is readable in both but only
18768/38400 pixels match, so it is explicitly not an exact pixel pass. Both
automated BerryBoy runs have zero missing guest frames or software audio buffer
failures. This establishes a real player-save round trip beyond initialization.

Restart exposed a missing unmapped CPU-store case above BIOS, at `0x7c00`.
These stores now leave memory unchanged. ARM/Thumb regressions cover low/high
ranges and preserve diagnostics for unsupported devices/execution. Latest
validation: **1031 host tests and 956 exported-core tests passed**.

Evidence: [leafgreen-player-save-2026-10-22.json](../benchmarks/leafgreen-player-save-2026-10-22.json).
Original input recordings are committed as
[leafgreen-create-save.tsv](../benchmarks/inputs/leafgreen-create-save.tsv) and
[leafgreen-continue.tsv](../benchmarks/inputs/leafgreen-continue.tsv). ROM, BIOS,
save bytes, screenshots and WAV remain outside Git. Replay with the recorded
ROM/BIOS/input hashes, backup type 2, no warmup and fresh output paths in
`ScenarioRunner`. Creation requires an absent initial save and 6950 frames;
continuation requires a copy of the recorded save and 1272 frames. Exact local
properties/logs are under
`/workspace/roms/gba/validation/2026-10-22/leafgreen-player-validation`.

Whole-stream continuation still differs (991/1272 exact RGB frames; PCM differs).
The room checkpoint does not close renderer, audio or timing qualification gates.

## Longer-run result

All eleven 3600-frame runs were repeated after correcting the LCD reset state
and high-address open-bus handling. They still have zero missing guest frames
and zero software audio buffer failures. The first mismatch against independent
RGB captures moved from frame 1 to frame 10 in every game: the display now starts
in hardware forced blank. Full video/audio hashes still differ. Corrected
reference PCM is nonzero for every game, at explicit volume 256 with mute off.
Current evidence is in
[validated-game-runs-2026-10-22.json](../benchmarks/validated-game-runs-2026-10-22.json).

A signal-level check caught an initial reference-adapter defect: its default
volume was zero. Those old PCM hashes are superseded. The adapter now sets and
checks master volume, records mute/volume/nonzero-sample metadata, and all eleven
references were recaptured. Their entire RGB streams and save images remained
byte-identical, confirming this correction only changed reference audio.

Sonic Advance 3, Wario Land 4, Metroid Fusion and Zero Mission were each restarted
in a fresh machine with an isolated copy of their previous save. Initial loaded
SHA-256 values match the copied files; after 1200 frames, all four resulting
save files match a separately restarted mGBA byte for byte. Original files were
preserved. Three games updated their save contents during reboot; matching the
reference update matters more than requiring the file to remain unchanged.
These tests verify initialization-state persistence and reload, not a manually
saved player slot in every title.

Wario's reload and LeafGreen's empty-name confirmation exposed CPU data reads
at high unmapped addresses (`0xe3a02004` and `0xe55ec003`). mGBA performs these
reads too. The bus now supplies prefetch lanes for unmapped CPU data accesses and
ignores unmapped CPU stores. Unknown I/O, unsupported execution and DMA open-bus
latches retain explicit diagnostics. Original ARM/Thumb regressions cover the
read widths, sign extension and store isolation. LeafGreen's recorded 4000-frame
name route now finishes without exception (3713 exact RGB frames against mGBA);
the subsequent complete save/continue route is verified above.

Validation: **1028 host tests and 953 exported-core tests passed**. The scenario
report now includes initial save hash, final backup hash/type/size and whether
host persistence wrote the file.

## Baseline scope

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

## Independent comparison (baseline RGB counts)

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

## Save initialization (baseline)

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

## Exploratory audio comparison

[compare_audio.py](../tools/compare_audio.py) requires NumPy and checks levels,
sample counts and local normalized correlation over two-second windows at
1, 10, 20, 30, 40 and 50 seconds, searching within +/-500 ms. Silence is reported
explicitly, not as an exact match. A known delayed/gain-scaled random signal
recovers the expected 237-sample lag and correlation 1; silent windows are
rejected as insufficient signal. Positive lag means a later reference window
matches the BerryBoy window.

Corrected Golden Sun late windows have correlations around 0.99 with a
10-sample offset; GTA is similar. Zone of the Enders has a stable approximately
1107-sample offset after its intro. Sonic Advance 3 and late Wario windows have
much lower correlations, making them priorities for channel/sequence/timing
investigation. These observations neither establish audible equivalence nor
qualify A/V drift; exact PCM is still different, and mGBA filtering/gain differs.
Raw per-window metrics are in the current evidence record.

```bash
python3 tools/compare_audio.py /path/to/berryboy/audio.wav \
  /path/to/reference/audio.wav --output target/audio-analysis-new.json
```

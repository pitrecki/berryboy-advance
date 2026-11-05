# Current status and roadmap

Updated **2026-11-05**, for **0.7.0-SNAPSHOT**. This is the canonical current
validation and work queue. [CHANGELOG](../CHANGELOG.md) records version highlights;
linked JSON records and Git history preserve detailed measurements.

## Goal and current scope

Target full GBA graphics and sound at the native clock:
`16777216 / 280896` = **59.7275 refreshes/s**, **16.7427 ms/frame**, with no
emulator frame skips or audio underruns. Qualify Linux and the Bold 9900/BBOS 7.1
separately. Neither platform has passed sustained-playback qualification.

| Area | Implemented | Still to qualify or complete |
| --- | --- | --- |
| Timing | Shared clock/events, ARM/Thumb costs, bus waits, timers, DMA, IRQ, HALT and supplied-BIOS reset | DMA activation/lifecycle, broader pipeline/prefetch behavior, IRQ latency, LCD timing and STOP |
| Graphics | Raster software PPU, modes 0–5, text/affine BG and OBJ, priorities, windows, blend/brightness and mosaic | Color rounding/palette bit 15, fetch delays, sprite evaluation and combined scenes |
| Audio | Four PSG channels, Direct Sound A/B, timer/DMA FIFO feed and bounded PCM output | Reference divergences, sequencer/PWM edges, audible output and target playback backend |
| Cartridges | SRAM, Flash 64/128 KiB, EEPROM sizes, persistence, RTC/GPIO and deterministic sensor state | Busy/ADC timing, RTC IRQ, calibration and wider real-player save coverage |
| Desktop | Keypad, Swing output, clock pacing, scenario recording and bounded host workers | Native-window/audio integration and sustained presentation measurements |
| BlackBerry | Legacy export, actual CLDC compilation, vendor RAPC COD and diagnostic MIDlet | Simulator application execution, device audio/input and physical Bold testing |

Guest CPU, PPU and sound share one deterministic owner. Host encoding/I/O may
run in parallel on bounded owned snapshots. Further threading follows measured
bottlenecks and preserves event ordering.

## Current validation

- **1828 host tests**, **1722 exported-core tests**, and **1722 tests against the
  actual new ECJ/CLDC classes** pass, with no failures or skips. Counts include
  parameterized invocations. Export performs 475 syntax rewrites.
- Native Linux JDE 7.1 / ECJ 4.6.1 / RAPC verification passes and produces a
  **0.7.0** JAD/COD. Host: Linux x86_64, Temurin 21.0.8, Maven 3.9.11.
- IRQ source waits and prefetch/page-edge observations now match the pinned,
  unmodified NanoBoyAdvance reference. Earlier CPU/DMA matrices are retained.
  [Latest timing and game evidence](../benchmarks/irq-fetch-waits-2026-11-05.json).
- Eleven uploaded games are exercised with fresh backups for 3600 guest frames
  each. These are boot/intro smoke routes; full RGB/PCM agreement and eleven
  gameplay passes have not been established. The latest record above identifies
  output changes and independent exact-frame outcomes.
  The latest correction gains 17 exact Sonic frames without losing previously
  exact frames in any title; full-stream differences remain open.
- The saved-player LeafGreen continuation retains identical full RGB/PCM and the
  original save. Its first remaining RGB555 mismatch stays at frame 930.
- The [README GIF](../README.md) contains real game output in the production
  Swing component, painted headlessly. Its 10 fps silent montage provides no
  presentation deadline, audible playback or device performance evidence.

Local checks are distinct from remote CI. No physical Bold is available. The
Windows JDK 6 path and simulator runtime remain unqualified. SDK compilation
alone does not pass those gates.

## Next roadmap: correctness, gameplay, then sustained playback

| Order | Next work | Completion evidence |
| --- | --- | --- |
| 1 / P0: DMA activation and lifecycle | Isolate scheduled activation versus immediate readiness, then cancellation, disable/re-enable and duplicate requests. Preserve bounded yields and accepted IRQ decisions. | Independent counterexamples, focused regressions, exact prior CPU/DMA matrices on host and CLDC, and unchanged frame/audio continuity. Current production activation remains immediate. |
| 2 / P0: Wider CPU/LCD timing | Extend prefetch coverage to cartridge-bus DMA, stream invalidation and address edges; qualify pipeline latching/self-modifying code and IRQ propagation/mask latency. Trace the LeafGreen frame-930 offset and HBlank at cycle 960 versus the reference's 1008. | Matching event/retirement observations with justified expected timing. Keep in-flight snapshots distinct from retired state. Do not adjust a constant solely to improve screenshot counts. |
| 3 / P0: Graphics expected results | Resolve the 2244-case three-way disagreement on brightness rounding and palette bit 15 using independently justified outputs or hardware evidence. Then qualify combined raster/OBJ/window effects. | Explicit per-pixel expectations and regression scenes. The renderer remains unqualified until those expectations are established; no relaxed tolerances. |
| 4 / P1: Audio correctness | Start with late Sonic Advance 3 and Wario Land 4 differences. Compare timer/DMA FIFO, PSG and sample-clock events before diagnosing gain/filter/resampling. | Repeatable sample/event checkpoints, justified comparison rules, listening review and no software-buffer loss. Timing-dependent fixes follow the timing gates. |
| 5 / P1: Gameplay and saves | Extend LeafGreen to overworld/menu/save, Sonic or Wario to a level, Golden Sun to a battle and V-Rally to a race; then cover the remaining titles. | Named reference checkpoints, second save generations and cold cross-loads from isolated copies. Preserve strict binary and full-stream outcomes. |
| 6 / P2: Linux performance | Once selected routes pass correctness, profile CPU/PPU/presentation and optimize one demonstrated bottleneck at a time. Consider acceleration only with a supported backend and matching software output. | Three 10-minute runs after 30-second warmup, measured presentation/audio, bounded queues/memory and the gates below. |
| 7 / Target-dependent: BlackBerry runtime | Run the diagnostic MIDlet in the simulator; add and qualify target audio/input, then repeat correctness and sustained playback on a physical Bold. | Separate build, simulator and device reports; actual application results and physical measurements. |

Input recording and save-route development can proceed alongside timing
investigation. A route remains failing until its unexplained differences are
resolved. [REFERENCE-VALIDATION.md](REFERENCE-VALIDATION.md) preserves earlier
color, timing, audio and save diagnoses; its per-change counts are historical.

The second LeafGreen indoor save generation already restores the new player
position in four cold cross-loads, with exact room pixels. Its 667 differing
save bytes remain an open strict-binary result. Repeat that route against the
latest core before extending its qualification claim.

Deferred features: versioned save states/rewind, link play and optional accelerated
presentation. Add them after current correctness and playback needs justify them.
Cartridge work should include repeated saves, erase/bank edges, RTC rollover and
safe failure injection using disposable save copies.

## Five-game qualification suite

[benchmarks/suite.json](../benchmarks/suite.json) retains the original
**planned-not-run** qualification entries. Eleven uploaded games do not replace
this suite. Mario & Luigi and WarioWare: Twisted! assets are still missing;
Twisted! also needs a deterministic sensor recording.

| Game | Proposed route and coverage |
| --- | --- |
| Golden Sun | Layer overlap and battle effects, including transparent OBJ priority |
| V-Rally 3 | Fixed race with bitmap/sprite composition and backdrop blending |
| Mario & Luigi: Superstar Saga | Boot, transitions and gameplay audio |
| Wario Land 4 | Intro and fixed level route with timer/IRQ-sensitive audio |
| WarioWare: Twisted! | Recorded minigames with gyro input, graphics and sound |

These are coverage candidates motivated by documented emulator failure cases,
not a measured ranking of the slowest games. Profile the selected scenes before
calling them the hardest workload.

## Run protocol and acceptance

Use [SCENARIOS.md](SCENARIOS.md) for local mappings and runner commands. Each
record identifies source/build, hardware/OS/runtime, renderer, audio format and
buffer size, ROM/BIOS/input/initial-save hashes, RTC/sensor seed, frame/sample
coordinates and reference version. Use fresh output paths and disposable save
copies. Keep ROMs, BIOS, saves and raw generated captures outside Git; commit
original traces and reviewable evidence.

For each qualified scenario and target:

1. Check correctness against identified reference checkpoints and full streams.
   Crashes, hangs, unexplained pixels/audio, broken sensors or corrupt saves fail.
2. Record cold boot separately; warm up for 30 seconds and measure 10 minutes,
   three times. Keep full resolution and sound. Record power/temperature/throttling
   on the physical device.
3. Count guest VBlank IDs, rendered frames and actual presentations. Measure
   monotonic intervals, p50/p95/p99/max work, misses, skips and duplicates.
4. Count produced/consumed audio, underruns/overruns and A/V drift. Retain video
   and WAV output for visual/listening review; instruction throughput is not game FPS.

Pass only with **zero dropped/skipped guest frames, zero missed presentation
deadlines and zero audio underruns/overruns**, bounded memory/queues and A/V drift
within one native frame without accumulation. Preserve intentional repeated game
images. Display refresh-rate conversion repeats must be distinguished from lost
guest frames. Average FPS and headless work timings cannot pass this gate.

Every implementation change requires `mvn verify`; portable-core/export changes
also require `tools/test-with-console.sh`. Keep checks asset-free where possible.
Deliver verified changes as English commits under the configured user identity,
push and verify the remote revision. Do not mute sound, skip frames or normalize
unexplained failures into a pass.

## Evidence and references

- [Latest IRQ wait/prefetch correction and game regression](../benchmarks/irq-fetch-waits-2026-11-05.json)
- [Previous waiting-fetch correction](../benchmarks/cpu-fetch-resume-2026-11-05.json)
- [Historical game comparisons and diagnoses](REFERENCE-VALIDATION.md)
- [BlackBerry tooling and runtime limits](BLACKBERRY.md)
- [GBATEK hardware descriptions](https://mgba-emu.github.io/gbatek/)
- [NanoBoyAdvance hardware tests](https://github.com/nba-emu/hw-test)

Earlier roadmap snapshots, implementation-stage counts and synthetic profiling
are retained in Git history. They are not current qualification results.

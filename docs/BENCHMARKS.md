# Full GBA graphics and sustained playback milestone

## Acceptance target

The requested goal is stable approximately 60 FPS, complete sound, no emulator
frame skipping and no drops on five demanding games. The final device target
remains the Bold 9900 on BBOS 7.1; Linux/Java 21 results are recorded separately.
No result from this milestone has been measured yet.

Use the GBA's native frame clock: `16777216 / 280896`, approximately
**59.7275 refreshes per second**, with about **16.7427 ms** per frame. Maintain
original speed and audio pitch. A game can intentionally update its own scene
less frequently; those repeated images must be preserved rather than counted
as emulator frame drops.

## Implementation order

1. **Timing foundation:** instruction/bus cycle costs, event scheduling,
   scanline/HBlank/VBlank state, timers, DMA, IRQ and BIOS boot/services. These
   clocks must drive both sound and graphics; instruction batches cannot serve
   as a timing oracle.
2. **Complete software PPU:** tiled modes 0–2, affine backgrounds, complete
   bitmap modes 3–5, tile/palette addressing, OBJ and affine OBJ, priorities,
   windows/OBJ windows, blending, brightness, mosaic, wrapping and mid-scanline
   register effects. Build focused regression ROMs and compare captured pixels.
3. **Complete audio:** PSG 1–4, Direct Sound A/B FIFOs, timer/DMA feed, sample
   clock, resampling, bounded buffering and measured A/V synchronization. Add
   the BlackBerry playback backend and verify audible output on the device.
4. **Cartridge behavior:** SRAM/Flash/EEPROM, RTC, ROM mirroring, GPIO,
   gyro/tilt/solar sensors and documented game-specific edge cases. Separate
   hardware behavior from explicit, narrowly scoped compatibility overrides.
5. **Acceleration and profiling:** identify CPU, PPU and presentation costs;
   optimize allocation, decoding and tile/palette caching with invalidation.
   Add an optional accelerated presentation/compositing backend only where the
   actual Linux or BlackBerry SDK/runtime supports it. Retain the software PPU
   as a reference. Desktop GPU support does not imply Bold GPU availability.
6. **Performance qualification:** run the five-game suite below with all sound
   enabled, compare software and accelerated output, then repeat on the Bold.

## Five-game candidate suite

[suite.json](../benchmarks/suite.json) records the proposed tests and their
current `planned-not-run` status. Selection is based on documented emulator
failure cases across graphics, audio and special hardware. It is a demanding
coverage suite, not a proven universal ranking of the five slowest games.
Profile the recorded scenes before claiming they represent the hardest load.

| Game | Main regression target | Proposed capture |
| --- | --- | --- |
| Golden Sun | Transparent OBJ priority interactions and battle effects | Layer overlap, then battle |
| V-Rally 3 | Bitmap/sprite composition and backdrop blending | Fixed race route |
| Mario & Luigi: Superstar Saga | Audio regressions during transitions | Boot, intro and gameplay |
| Wario Land 4 | Timer/IRQ-sensitive audio | Intro and fixed level route |
| WarioWare: Twisted! | Gyro/peripheral integration with video and audio | Recorded minigames and sensor trace |

The manifest links to the original emulator issue reports. Historical bugs in
other emulators motivate these tests; their current implementations are not
being characterized as broken. Scene descriptions are proposals, not executed
test observations or supplied save-state locations.

## Reproducible run protocol

Supply five local ROMs, their region/revision and SHA-256, a BIOS hash where
required, initial save data, and deterministic input/sensor recordings. Keep
ROMs, BIOS files and personal saves outside Git. Local mappings can live under
`benchmarks/roms/` or in `benchmarks/*.local.json`, both ignored by Git.

For every game and target device:

- Record commit, build/compiler, hardware, OS/runtime, renderer, power state,
  audio device/format/buffer size, ROM/BIOS hashes and scenario hashes.
- Record cold boot separately, then warm up for 30 seconds and measure the
  fixed scenario for 10 minutes, three times. Record temperature and throttling
  on the physical device. No reduced resolution, muting or automatic frame skip.
- Count guest VBlank frame IDs, frames rendered and frames actually presented.
  Capture monotonic timestamps and report mean, p50/p95/p99/max work and
  presentation intervals, missed deadlines, skips and duplicate presentations.
- Count audio samples produced/consumed, underruns, overruns and A/V drift over
  the complete run. Keep video and WAV recordings for visual/listening review.
- Compare frame checkpoints and audio against reference captures. Pixel/audio
  mismatches, broken sensors, save corruption, crashes or hangs fail the run.

Every measured run must have **zero skipped/dropped guest frames, zero missed
presentation deadlines and zero audio underruns/overruns** to pass. A 60 Hz
display may repeat the last completed GBA frame during rate conversion; that
must be distinguished from missing a guest frame. Audio/video drift must remain
bounded within one native frame and show no cumulative trend. An average FPS
alone cannot pass the gate.

## Current blockers and evidence

The current PPU renders BG2 bitmap snapshots. There is no cycle scheduler,
full PPU, complete mixer, cartridge peripheral support or commercial-game
benchmark runner. The new desktop session is an interactive diagnostic.
No benchmark ROMs, save states or reference captures were supplied for this
update. Consequently all five entries remain unrun, and 60 FPS on either target
is an unverified goal.

Current headless demo screenshots are in [AUDIO-INPUT.md](AUDIO-INPUT.md). They
are not evidence for this five-game milestone.

## Hardware reference

- [GBATEK LCD timing and hardware descriptions](https://mgba-emu.github.io/gbatek/#gba-lcd-video-controller)
- [NanoBoyAdvance hardware tests](https://github.com/nba-emu/hw-test)

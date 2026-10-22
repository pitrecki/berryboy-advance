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

## Delivery milestones

Roadmap updated on 2026-10-22. M1 is in progress; later milestones are planned.
the completed baseline is the imported ARM/Thumb interpreter, bitmap diagnostic,
channel-two audio, keypad controls, legacy exporter and Linux verification.
Local validation passed 926 host tests and 864 exported-core tests, plus headless
video/audio demos and documentation capture. These results do not establish
commercial-game compatibility or BlackBerry device readiness.

| Milestone | Deliverables | Completion evidence |
| --- | --- | --- |
| M1: Shared timing | Guest cycle clock, deterministic scheduler, CPU/bus costs, LCD timing, timers, DMA, IRQ and BIOS integration | Focused timing ROMs and tests verify event boundaries, ordering, interrupt entry/return and repeatable guest state |
| M2: Software graphics (in progress) | Complete PPU features listed above, driven by scanline timing | Pixel comparisons for each feature and combined scenes, including register writes during display |
| M3: Synchronized audio (in progress) | All PSG channels, Direct Sound, timer/DMA refill and bounded output queues | Reference PCM comparisons, sample-count checks and measured A/V drift with no buffer failures |
| M4: Cartridge compatibility (in progress) | Persistent save types, RTC and required cartridge peripherals | Save/reload round trips, command-sequence tests and deterministic sensor playback |
| M5: Reproducible game runner (capture path implemented) | Local ROM/BIOS mapping, recorded input, frame/audio checkpoints and machine-readable reports | The same inputs reproduce guest checkpoints; missing assets fail explicitly rather than skip a game |
| M6: Platform validation and optimization | Profile-guided improvements, BlackBerry build/playback/input checks and optional supported acceleration | Output matches the software reference; Linux and Bold 9900 reports identify their own hardware and runtime |
| M7: Sustained playback qualification | Execute the complete five-game protocol on each target | All correctness, frame deadline, audio and drift gates below pass for every recorded run |

Develop M1 through M4 in dependency order. Build M5 as deterministic timing and
capture interfaces become available, before performance qualification. Validate
the BlackBerry export/build incrementally during earlier milestones; do not
defer platform compatibility until optimization. M7 depends on all earlier
milestones and on the external assets and device access listed below.

### Immediate queue: timing foundation

The next implementation commit should cover the first item only. Keep later
items as separate, reviewable changes with their own tests.

1. **Cycle clock and scheduler (implemented):** `CycleScheduler` provides a
   portable guest clock and event
   queue, with explicit ordering for events at the same cycle. Test exact
   deadlines, advancement across multiple events, reset, large cycle counts and
   deterministic replay. Keep instruction counts as a separate diagnostic;
   this initial infrastructure does not claim accurate instruction timing.
   Validation: 931 host tests and 869 exported-core tests passed, including
   exact deadlines, stable ties, cancellation/replacement, replay, reset,
   overflow and callback failure recovery (`CycleSchedulerTest`).
2. **CPU and bus timing (initial implementation):** account for ARM/Thumb execution, conditional paths,
   branch/pipeline effects, memory regions, sequential accesses and WAITCNT.
   Test representative cycle traces against documented ARM7TDMI/GBA behavior.
   `BusTimingTest` covers wait-state selection, memory widths, conditional
   execution, register shifts, branch refill and device deadlines during a
   load. Game Pak opcode prefetch now fills an eight-halfword buffer during free
   bus/internal cycles and flushes on branches/cartridge data accesses. Tests
   cover buffered fetches, capacity and the documented prefetch-disable bug.
   Contention and pipeline overlap need hardware/reference qualification before
   this step is complete.
3. **LCD events (implemented):** schedule 1232 cycles per scanline and 228 lines per frame
   (280896 cycles), with HBlank/VBlank, VCOUNT and DISPSTAT transitions and IRQ
   requests. Test boundaries and several consecutive frames without drift.
   `HardwareTimingTest` verifies HBlank at cycle 960, line transitions at 1232,
   VBlank entry at line 160, status clearing at line 227 and 100-frame stability.
4. **Timers and DMA:** implement prescalers, overflow/reload, timer cascading,
   DMA triggers, transfer widths, repeat behavior and CPU/bus interaction.
   Cover simultaneous events and transfer completion IRQs.
   Timer prescalers, reload, cascading, enable/disable and timer IRQ requests
   are implemented and tested. Four-channel DMA supports immediate/HBlank/VBlank
   requests, priority, repeat, halfword/word widths, address modes, destination
   reload and completion IRQs. DMA1/2 also support timer-driven sound FIFO refill.
   Video-capture timing and Game Pak
   DRQ remain unsupported; bus contention still needs reference qualification.
5. **Interrupts and BIOS:** implement IE/IF/IME behavior, CPU IRQ masking and
   exception return, HALT/wakeup and an explicit BIOS boot/service strategy.
   Keep the diagnostic cartridge entry distinct from verified BIOS boot.
   IRQ entry/return, interrupt masking, keypad requests, HALT wakeup and a
   supplied-BIOS reset path are implemented. Synthetic BIOS tests cover ARM IRQ
   entry and return to Thumb; real BIOS boot and STOP remain unverified.
6. **Integrated timing regression (implemented):** run a small original test ROM combining
   LCD events, timers, DMA and IRQ, recording cycle-stamped checkpoints.
   Require identical guest results across repeated host and exported-core runs
   before moving to the complete PPU.
   `TimingRomTest` runs an original ARM program that enables a timer and HBlank
   DMA, polls interrupt flags and writes a completion signature. Repeated
   cycle/PC/IF traces match; both host and isolated exported-core suites run it.
   Validation after DMA integration: 951 host and 889 exported-core tests pass.

### Prerequisites and delivery rules

- Early timing and hardware regression tests should use original project ROMs
  and require no commercial games or external BIOS files.
- Before M5 game validation, provide local game dumps, BIOS where required,
  region/revision and hashes, saves, reference captures and deterministic
  input/sensor traces. Keep these assets outside Git as described below.
- BlackBerry validation requires the SDK, compatible JDK/tooling, simulator
  access and a physical Bold 9900 for final audio, input and performance checks.
  Host export tests cannot satisfy the device gate.
- Every implementation change must pass `mvn verify`; changes to the portable
  core or exporter must also pass `tools/test-with-console.sh`. Add focused
  hardware tests for new behavior and record actual outcomes and limitations.
- Deliver tested changes as small English-language commits and push them to
  the configured repository. Use the configured author identity; preserve
  published history. Mark milestones complete only with linked commit and
  validation evidence, and keep planned, failed, blocked and passed runs distinct.

## Five-game candidate suite

[SCENARIOS.md](SCENARIOS.md) documents the implemented local capture runner,
asset hashes, input/sensor recordings, references and report limitations. The
original demo passes repeatability checks; the commercial-game suite is still
unrun. The user has ROMs but no physical target device. The supplied archive.org
collection is currently blocked by the environment network proxy (HTTP 403).
Required archive.org domain additions are saved in the environment draft;
saving the draft alone does not enable runtime downloads.

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

`SoftwarePpu` now composes snapshots for text/affine/bitmap backgrounds (modes
0–5), normal/affine OBJ, priorities, windows/OBJ windows, alpha/brightness and
mosaic. Seven focused pixel regressions supplement the existing bitmap tests.
`RasterPpu` now samples one pixel per four guest cycles and publishes completed
frames. Tests cover mid-scanline palette changes, affine reference writes and
completed-frame stability. LCD fetch-pipeline delays, mosaic edge cases and OBJ
hardware limits still require qualification; M2 remains in progress.
Guest-clocked sample output is implemented. PSG channels 1–4 support sweep,
banked wave RAM, noise, length/envelope and stereo mixing. Tests verify sweep
overflow, signed wave nibble order, 7-bit noise feedback, exact sample deadlines
and bounded-buffer failure counters. Direct Sound A/B now consume signed FIFO
samples on timer overflow, refill through DMA1/2 and mix with saturation.
Validation: 968 host and 906 exported-core tests pass. Device playback and
hardware sequencer/PWM edge cases remain open.
SRAM, Flash 64/128 KiB and EEPROM 512-byte/8 KiB command protocols are implemented
with portable snapshots and host file persistence. Tests cover unlock/ID,
program/bank/erase isolation, serial bit order/dummy bits, size mismatch safety
and save reload. Configurable RTC/GPIO, solar, gyro, tilt and rumble state are now
implemented with deterministic inputs. Serial regressions cover RTC leap-day and
year rollover, 12-hour PM, sensor latching, dummy bits, solar threshold and GPIO
direction/read enable. Busy/ADC timing, RTC IRQs and hardware calibration still
require implementation/qualification; M4 remains in progress.
A portable cycle scheduler and
initial CPU/bus timing are implemented. There is no full PPU, complete mixer,
cartridge peripheral support or commercial-game
benchmark runner. The new desktop session is an interactive diagnostic.
No benchmark ROMs, save states or reference captures were supplied for this
update. Consequently all five entries remain unrun, and 60 FPS on either target
is an unverified goal.

Current headless demo screenshots are in [AUDIO-INPUT.md](AUDIO-INPUT.md). They
are not evidence for this five-game milestone.

## Hardware reference

- [GBATEK LCD timing and hardware descriptions](https://mgba-emu.github.io/gbatek/#gba-lcd-video-controller)
- [NanoBoyAdvance hardware tests](https://github.com/nba-emu/hw-test)

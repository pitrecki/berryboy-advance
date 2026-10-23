# Full GBA graphics and sustained playback milestone

## Acceptance target

The requested goal is stable approximately 60 FPS, complete sound, no emulator
frame skipping and no drops on five demanding games. The final device target
remains the Bold 9900 on BBOS 7.1; Linux/Java 21 results are recorded separately.
The sustained-playback acceptance target remains unqualified. Short host
compatibility captures and independent comparisons are available; their scope
and the next delivery plan are recorded below.

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

Roadmap updated on **2026-10-23**, after RGB555 reference qualification and
the Thumb multiply timing and Game Pak DMA source corrections, plus the FIFO
DMA reference counterexample and independent DMA read/write phase correction. The shared clock, scheduler,
CPU/bus timing, software renderer, mixer, cartridge devices and capture runner
are implemented. Their accuracy is still being qualified; implementation does
not close M1–M4. Latest local validation passed **1377 host tests and 1283
exported-core tests**. Neither test count establishes BlackBerry readiness.

| Milestone | Deliverables | Completion evidence |
| --- | --- | --- |
| M1: Shared timing (accuracy qualification in progress) | Guest cycle clock, deterministic scheduler, CPU/bus costs, LCD timing, timers, DMA, IRQ and BIOS integration | Focused timing ROMs and tests verify event boundaries, ordering, interrupt entry/return and repeatable guest state |
| M2: Software graphics (in progress) | Complete PPU features listed above, driven by scanline timing | Pixel comparisons for each feature and combined scenes, including register writes during display |
| M3: Synchronized audio (in progress) | All PSG channels, Direct Sound, timer/DMA refill and bounded output queues | Reference PCM comparisons, sample-count checks and measured A/V drift with no buffer failures |
| M4: Cartridge compatibility (in progress) | Persistent save types, RTC and required cartridge peripherals | Save/reload round trips, command-sequence tests and deterministic sensor playback |
| M5: Reproducible game runner (implemented; gameplay routes incomplete) | Local ROM/BIOS mapping, recorded input, frame/audio checkpoints and machine-readable reports | The same inputs reproduce guest checkpoints; missing assets fail explicitly rather than skip a game |
| M6: Platform validation and optimization (host profiling in progress) | Profile-guided improvements, BlackBerry build/playback/input checks and optional supported acceleration | Output matches the software reference; Linux and Bold 9900 reports identify their own hardware and runtime |
| M7: Sustained playback qualification | Execute the complete five-game protocol on each target | All correctness, frame deadline, audio and drift gates below pass for every recorded run |

Use the following evidence-driven queue to qualify M1–M5 before M6 performance
optimization and M7 sustained playback. Continue checking the portable export
during core changes. SDK/device validation can progress independently when the
external prerequisites become available.

### Next roadmap: correctness, gameplay, then sustained playback

This queue supersedes the original timing implementation queue below. Each row
is a separate delivery stage; a diagnostic result can change the following fix
without changing its acceptance criteria. Do not mark an entire hardware feature
complete because a single game or checkpoint passes.

**Starting evidence:** eleven uploaded games each complete 3600 guest frames
with zero missing guest frames or software audio-buffer failures. Independent
RGB and PCM streams still differ; every run first differs in RGB at frame 10.
Four initialization-save reloads match mGBA. LeafGreen additionally has a real
manual player-save round trip, including an exact restored-room checkpoint.
These are boot/intro and limited save results, not eleven gameplay passes.
See [REFERENCE-VALIDATION.md](REFERENCE-VALIDATION.md) and its linked JSON records.
The [post-DMA regression](../benchmarks/dma-game-regression-2026-10-23.json)
repeats all eleven 3600-frame captures on the corrected core: 39600 frames,
zero software-buffer losses and isolated fresh backups. This preserves the
smoke scope; it does not close the independent correctness or gameplay gates.

| Stage / priority | Work | Exit evidence |
| --- | --- | --- |
| R1 / P0: LeafGreen menu (three-way model comparison delivered; pixel qualification open) | Menu state matches; NBA agrees with mGBA on its color, while the 2244-case matrix exposes broader disagreement in rounding and palette bit 15 | Establish independently justified expected rounding and palette-bit behavior before correcting the renderer or passing the menu gate; existing arithmetic tests preserve behavior without certifying hardware |
| R2 / P0: Timing and bus accuracy (MUL, ROM addressing, ownership and read/write phases corrected) | NBA matches all 96 ownership cases and 144 activated-transfer phase scenarios; mGBA count-dependent event timing is isolated; frame 930 initialization offset remains open | Qualify request activation/CPU continuation, equal-deadline IRQ priority and FIFO/timer workloads using independent evidence; land demonstrated defects with regressions and traces, preserving DMA deadlines, open-bus handling and saves |
| R3 / P1: Audio correctness | Start with late Sonic Advance 3 and Wario Land 4 differences; compare timer/DMA FIFO events, PSG state and sample clocks; distinguish guest-state errors from output gain, filter and resampling differences | Repeatable event/sample checkpoints, justified comparison tolerances and listening review; no unexplained divergence in the selected routes and zero software buffer failures |
| R4 / P1: Gameplay and player saves (second LeafGreen cross-load delivered) | A one-tile indoor route creates generation 2; four cold cross-loads restore the exact room in both emulators. New saves still differ in 667 bytes. Extend routes to levels, races, battles and other titles | Named reference checkpoints for all eleven titles; save-capable games restore latest progress from isolated copies, with explicit strict binary/full-stream outcomes |
| R5 / P2: Sustained Linux playback | After route correctness passes, profile bottlenecks, optimize one at a time, and run the warmup/measurement protocol below | Three 10-minute runs per qualified scenario after 30-second warmup, actual presentation/audio counters, bounded memory/queues and A/V drift; all acceptance gates below pass |
| R6 / SDK build passed; runtime qualification open | Native ECJ/CLDC build against JDE 7.1 and RAPC conversion pass; qualify simulator playback/input, then repeat on a physical Bold 9900 | Separate reproducible build, simulator and device reports; physical-device correctness and sustained-playback gates pass before a Bold compatibility claim |

[R1's three-way comparison](REFERENCE-VALIDATION.md#three-way-brightness-model-comparison)
supersedes the earlier attribution solely to reference precision. It exposes
disagreement in brightness rounding and palette-bit handling even though the
menu's raw video state matches. The RGB555 startup agreement remains a measured
result, not general hardware certification. Choose a color-model correction
using independently justified expected outputs; do not force a screenshot match.
The all-pixel menu gate remains open and no tolerance has changed. R2 must
establish event ordering before any R3 fix that depends on timer/DMA timing.
R4 input recording can proceed while individual defects are isolated, but a
route remains failing until its reference differences are explained and fixed.
R5 depends on correctness for the selected routes. The original five-game suite
also needs its two missing assets; eleven uploaded titles do not silently replace
that qualification suite. R6 now has SDK compilation and COD evidence; simulator
execution and physical-device access remain separate gates.

### Concrete next commits

1. **Isolate the LeafGreen Continue mismatch (state/arithmetic diagnosis delivered;
   hardware interpretation reopened).** The committed
   [continuation input](../benchmarks/inputs/leafgreen-continue.tsv) reproduces
   identical registers, palette, VRAM and OAM at frame 1029. All 19632 differing
   pixels are explained by native 5-bit versus mGBA RGB888 darkening. Original
   asset-free host/export regressions preserve native arithmetic. Full video,
   PCM and save hashes are unchanged; room frame 1272 stays exact.
2. **Qualify color-effect reference precision (RGB555 and three-way matrix delivered;
   brightness open).** Build both independent library and adapter with the same pixel ABI.
   The unmodified RGB555 reference matches 12/12 BIOS RGB frames and 1111/1272
   continuation frames. Menu frame 1029 still has all 19632 differences explained
   by upstream shifted-channel darkening. Obtain independent channel-correct
   brightness or hardware evidence before passing that pixel gate. The new
   2244-case comparison shows NBA agrees with mGBA on the menu color but differs
   broadly from both renderers, including palette bit 15. The previous unshifted
   five-bit explanation does not establish hardware correctness. Resolve those
   expected outputs before changing the current renderer regression contract.
3. **Locate the first timing divergence (diagnostic tooling delivered).** Bounded
   LCD before/after traces preserve RGB, PCM and CPU/frame checkpoints in both
   emulators. The checked LeafGreen BIOS frame 10 has identical raw video state;
   all 682 pixel differences follow native/RGB888 alpha rounding, using the
   visible-line coefficients rather than the next frame's final register state.
   See [the startup diagnosis](REFERENCE-VALIDATION.md#bios-startup-event-diagnosis).
   RGB555 now localizes the first remaining continuation difference to frame 930,
   including 820 VRAM bytes and BLDY=7 reached about one frame later in BerryBoy.
   Next isolate the CPU bus/IRQ/BIOS cause of this initialization offset and
   qualify HBlank at cycle 960 versus the reference's 1008. Do not change a
   constant solely to improve screenshot counts. Land each demonstrated defect
   with a regression and comparison report.
   The [Thumb MUL correction](REFERENCE-VALIDATION.md#thumb-multiply-timing-correction)
   removes the first isolated two-cycle discrepancy and extends PC/cycle agreement
   to 600000 pre-step samples. Its 17 original regressions and independent probe
   pass, while the 1111/1272 RGB result remains unchanged. A normalized local walk
   now isolates the next discrepancy at retired row 796679: paired FIFO DMA
   precedes an otherwise matching ADD, with BerryBoy four cycles later. Next
   qualify DMA request, ownership and completion ordering, including channel
   handoff; do not drop startup cycles solely to align this trace. The
   [FIFO count-invariance counterexample](REFERENCE-VALIDATION.md#fifo-dma-timing-reference-counterexample)
   now reproduces it without game assets: mGBA changes timing with an ignored
   count in 20/24 parameter groups, while BerryBoy is invariant. Its 192 new
   regressions preserve two repeated FIFO bursts. Obtain a second timing source
   or original hardware measurements before attributing this discrepancy to
   BerryBoy; mGBA 0.10.5 cannot be its absolute timing oracle. The new
   [independent NBA ownership probe](REFERENCE-VALIDATION.md#independent-dma-ownership-reference)
   provides that second source for continuous bus ownership. The
   [shared ownership correction](REFERENCE-VALIDATION.md#shared-dma-ownership-correction)
   now matches all 96 cases, including 48 previously overcharged pairs, and
   preserves word-level yields. Eight new regressions cover repeated groups,
   preemption and CPU resumption. The replay keeps RGB, video states and save
   unchanged; PCM changes and remains unmatched. Continue with request
   activation and write/IRQ phases; hardware qualification remains separate.
   The subsequent [read/write phase correction](REFERENCE-VALIDATION.md#dma-readwrite-phase-correction)
   now brackets transfers with one acquisition and one release cycle, fixing
   one-cycle-late reads/writes without changing ownership totals. All 144 phase
   scenarios and 28800 observations match NBA; 82 original regressions fail
   before and pass after. Full LeafGreen RGB/PCM, video states and save remain
   unchanged. Next qualify request activation/CPU continuation and equal-deadline
   IRQ priority, then repeat phase observations for actual FIFO/timer workloads.
   A separate
   [ROM DMA source correction](REFERENCE-VALIDATION.md#dma-game-pak-source-addressing-and-next-timing-boundary)
   matches 24 original reference cases and preserves the whole replay's RGB/PCM,
   selected video states and save. The FIFO sources in this trace are IWRAM.
4. **Isolate the first audio divergence.** Record channel, timer and FIFO events
   for Sonic and Wario; compare selected active windows with the corrected,
   unmuted reference. Define acceptable filter/resampling differences before
   evaluating a fix. Local correlation and shifted signals are diagnostics,
   not a substitute for correct guest timing or an audio-quality pass.
5. **Add the first gameplay routes and second save generation.** Start with a
   LeafGreen overworld/menu/save route, a fixed Sonic or Wario level route, and
   a Golden Sun battle or V-Rally race. Expand to the remaining titles in small
   commits. For save-capable routes, create a second player save, cold-restart
   both emulators, and verify the latest progress and expected backup changes.
   The [second LeafGreen route](REFERENCE-VALIDATION.md#second-leafgreen-player-save-and-cold-cross-load)
   now records one-tile indoor movement and an overwrite save. All 28 section
   checksums validate; generation 2 restores at the new position in four cold
   cross-loads, each exact in all room pixels. Strict save comparison retains
   667 differences, including distinct encryption keys and a one-frame play-time
   difference; no normalization makes that gate green. Next diagnose the
   remaining binary differences and add the first level/race/battle route.

Each implementation delivery records source revision, ROM/BIOS/input/initial-save
hashes, RTC/sensor seed, frame/sample coordinates, reference version and outcomes.
Use fresh output paths and copies of save files. Commit original input traces
and metadata; keep ROM, BIOS, save bytes and generated captures outside Git.
Run `mvn verify` and, for portable-core/export changes,
`tools/test-with-console.sh`; commit and push each verified update. Do not relax
pixel tolerances, skip frames or mute audio to turn an unexplained failure green.
Report regression results separately from exploratory measurements.

### Remaining gameplay coverage and later goals

- Add checkpoints that exercise affine/bitmap scenes, overlapping OBJ, windows,
  blending and palette changes. Inspect Zone of the Enders scene/event alignment
  before attributing its low exact-frame count to a rendering feature.
- Cover SRAM, Flash 64/128 KiB and both EEPROM sizes with actual player progress
  wherever the available cartridges use them; retain original protocol tests
  for hardware variants not represented by a local game. Add repeated saves,
  bank/erase boundaries and deterministic RTC rollover. Apply write-failure
  injection only to disposable copies and documented recovery behavior.
- Preserve deterministic guest ownership. Move host encoding, file I/O or
  presentation to bounded snapshot queues only when profiling justifies it;
  measure ordering, failure propagation and backpressure. Do not split live CPU,
  PPU and audio state across independent host threads.
- Consider versioned save states/rewind, link play and optional accelerated
  presentation after the correctness and playback gates. These are deferred
  features, not prerequisites for fixing current game behavior.

### Timing foundation: implementation history

The following records preserve the original implementation sequence and its
per-change validation counts. They are historical evidence, not the next queue
or a claim of full hardware accuracy.

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
   entry and return to Thumb; real-BIOS game starts have short capture evidence,
   while STOP remains unsupported.
   A user-supplied 16 KiB BIOS is now available outside Git. Reset execution
   exposed and fixed overly strict validation of temporary SPSR mode bits;
   invalid modes are rejected when restored into CPSR, not when stored.
   Full game compatibility and IRQ/SWI timing remain under investigation.
   Reserved I/O writes and the undocumented BIOS write at `0x04000410` are
   handled; internal WRAM control/mirrors and wait settings are modeled.
   The supplied BIOS Div SWI returned quotient 726/remainder 3 for 12345/17.
   `serial.SerialUnit` now supplies reset/disconnected SIO and normal internal-
   clock shifts; external-clock transfers wait for a peer, and multiplayer/UART
   transfers report the missing link backend. The supplied BIOS reset ran for
   180 frames and produced the GAME BOY animation, 98752 stereo samples, zero
   missing guest frames and zero software audio-buffer failures. Eight cold
   work deadlines were missed; this is not performance qualification. The
   original demo lacks a BIOS-accepted cartridge logo. Eleven uploaded commercial
   games subsequently completed 1200-frame real-BIOS smoke captures, after fixing
   DMA capture deadlines and BIOS-protected/open-bus reads. Metroid Fusion's
   former 428-frame exception is resolved. ARM bus spot checks agree with mGBA;
   full IRQ/SWI timing, reference correctness and longer gameplay remain open.
   Independent references now expose differences over eleven 3600-frame runs;
   four save/reload images match mGBA. LeafGreen also has a verified manual
   player save and cross-emulator restored-room pixel checkpoint.
   See REFERENCE-VALIDATION.md.
   Latest validation: 1031 host and 956 exported-core tests passed.
   BIOS data and captures remain outside Git.
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
original demo passes repeatability checks. Eleven uploaded games now have
[compatibility captures](GAME-COMPATIBILITY.md): after incremental DMA service
and BIOS/open-bus fixes, all eleven completed 3600-frame captures without counter
failures. Independent RGB/PCM comparisons still differ; four initialization saves
and their reload updates match mGBA byte for byte. See
[REFERENCE-VALIDATION.md](REFERENCE-VALIDATION.md). This does not satisfy the
five-game sustained qualification suite. The user has no physical target device.
The supplied archive.org
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
completed-frame stability. OBJ evaluation consumes the documented 1210/954-cycle
line budget, including offscreen objects; bitmap overflow stays transparent.
LCD fetch-pipeline delays, mosaic edge cases and sprite evaluation timing still
require qualification; M2 remains in progress.
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
The portable scheduler, initial CPU/bus timing, software PPU, mixer, cartridge
devices and headless game capture runner are implemented, with accuracy and
qualification gaps listed above. Eleven uploaded ROMs enabled initial game
captures; independent references now exist for all eleven uploaded games, with
remaining RGB/PCM differences. Game-specific gameplay routes remain incomplete.
The qualification suite also still lacks Mario & Luigi and WarioWare: Twisted!
assets. All five sustained qualification entries remain unrun, and native-rate
playback on either target is an unverified goal. The next compatibility queue
is the [next roadmap above](#next-roadmap-correctness-gameplay-then-sustained-playback);
[GAME-COMPATIBILITY.md](GAME-COMPATIBILITY.md) preserves earlier smoke evidence.

Current headless demo screenshots are in [AUDIO-INPUT.md](AUDIO-INPUT.md). They
are not evidence for this five-game milestone.

## Synthetic host profiling, 2026-10-22

This is an original-demo work-cost experiment, not a five-game or Bold FPS result.
Baseline: `7dec35b`, Linux x86_64 on Intel Xeon Platinum 8573C, four-CPU quota, JDK 21.0.8+9,
software raster output, no physical audio/display. ROM SHA-256:
`9d0b290b28f9bbd105ee48133f660bb8916fdfb3b018d9204d64aec73af25229`.
Scenario: direct-entry original demo, no input, deterministic default RTC;
300 guest frames warmup, 600 measured frames, three repetitions per variant,
`-Xms256m -Xmx256m`, no profiler during the comparison. Guest frame and sample
clocks remain native, with all sound synthesis enabled; the demo does not itself
enable a tone. This is not an effects-heavy graphics/audio workload.

JFR sampling first identified memory reads in CPU fetch and PPU register access.
The candidate adds aligned halfword fast paths and a display-register cache
invalidated on writes; regression tests preserve midline palette/scroll changes.

| Variant | Per-run p50 work (ms) | Per-run p95 work (ms) | Per-run work deadline misses |
| --- | --- | --- | --- |
| Baseline | 2.635, 2.666, 2.600 | 4.686, 5.957, 3.574 | 0, 0, 0 |
| Candidate | 2.308, 2.270, 2.285 | 2.884, 2.774, 2.645 | 0, 0, 0 |

All six captures have identical aggregate video/PCM hashes, zero missing guest
frames and zero software audio-buffer failures. The median of run medians fell
by about 13%; shared-cloud timing remains variable. An earlier shorter JFR run
of the candidate had two work deadline misses, which motivated the longer
warmup and repeated comparison. No physical deadline or audible output result
is inferred. Work timings cover emulation, excluding file encoding/presentation.
Reports/JFR files remain under ignored `target/profile-*`.

BlackBerry SDK compilation is now verified: ECJ 4.6.1 emits CLDC stack maps
against JDE 7.1 and the original RAPC produces a COD without errors. The native
Linux path does not need Windows JDK 6. All 957 core tests pass again using the
actual CLDC classes. See [BLACKBERRY.md](BLACKBERRY.md) and its evidence record
for reproduction and Wine/simulator limitations. No physical Bold is available.
GPU acceleration is not claimed without a supported target backend.

## Hardware references

- [GBATEK LCD timing and hardware descriptions](https://mgba-emu.github.io/gbatek/#gba-lcd-video-controller)
- [NanoBoyAdvance hardware tests](https://github.com/nba-emu/hw-test)

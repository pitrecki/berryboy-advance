# Changelog

## 0.9.1-SNAPSHOT — unreleased

- Add asset-free natural LCD/DMA/IRQ phase probes for BerryBoy, unmodified NBA
  and mGBA. Preserve eight shared terminal outcomes as portable regressions;
  keep disputed fine timing outside the production model.
- Support the full four-byte IME register with reserved lanes reading zero,
  fixing word loads used by interrupt libraries without changing IE/IF or state format.
- Model disconnected multiplayer-port initialization without synthesizing peer
  data or completion IRQs, allowing Doom II startup to continue. Preserve normal
  shifts, GPIO selection and the existing machine-state layout.
- Add recorded cold-boot routes for Tony Hawk 2, Doom II, F-Zero GP Legend and
  Mario & Luigi, plus a Doom first-level input route and strict reference results.

## 0.9.0 — 2026-11-06 (preview)

- Choose and persist any readable local ROM folder, plus last ROM/BIOS browser
  selections. Separate asynchronous file/folder browsing from the canvas.
- Add Show/Skip for BIOS startup presentation while preserving authentic BIOS
  initialization, with identical startup state/RGB/PCM on three supplied games.
- Add persistent 1×/2×/4×/8× playback in pause/settings. Mute accelerated output
  and restore sound at 1× according to the user's mute preference; display the
  selected factor and retain separate guest/draw FPS counters.
- Separate phone input, settings, library, audio, video, playback and UI into
  feature packages with matching tests. Keep timing experiments outside production.

## 0.8.2 — 2026-11-06 (preview)

- Add optional sticky L/R controls for shoulder-plus-action/direction combinations,
  with independent locks, repeat-key protection and an always-visible active badge.
  Clear input on pause, focus loss and game/state changes. Preserve old controls
  while migrating settings versions 1/2 to version 3; assist defaults to off.
- Preserve 96 independent CPU/DMA readiness observations as portable regressions.
  Record a rejected delayed-activation prototype and its Sonic frame-3555 regression;
  production activation and machine-state format remain unchanged.

- Reload repeated DMA lengths from the current count register at completion,
  preserving the active burst and the already latched next burst. Keep FIFO
  bursts fixed at four words and preserve the version-1 save-state layout.

## 0.8.1 — 2026-11-06 (preview)

- Add versioned phone release ZIPs with raw sibling CODs, JAD/ALX, native MIDlet
  JAR, installation instructions, source/tool provenance and SHA-256 checksums.
- Build and publish releases from version tags through GitHub Actions using a
  pinned Linux SDK bootstrap and host/export/actual-CLDC verification.
- Add an original BerryBoy icon to the phone launcher, menus and README.
- Use the entire canvas during play, with no app header/footer. Add aspect-fit
  and edge-to-edge stretch alongside native/integer modes; FPS is an optional overlay.
- Preserve version-1 control/audio/image preferences while writing version 2.
- Replace the single-game phone gallery with Metroid: Zero Mission, GTA Advance,
  The Lost Age, Wario Land 4 and V-Rally 3 captures. Simplify README captions.

## 0.8.0-SNAPSHOT — 2026-11-06

- Replace the phone diagnostic screen with a home/pause frontend, local ROM/BIOS
  browser, settings, control remapping and diagnostics.
- Add versioned complete machine save states, including pending CPU/DMA work,
  hardware event order, graphics and audio. Five phone slots use alternating
  sidecar generations and an atomic RMS pointer; original `.sav` files remain unchanged.
- Add named Raw/unencrypted CodeBreaker RAM cheats, volume/mute/mono controls,
  integer/native image scaling, presentation frame skip and GBA/draw FPS counters.
- Introduce a bounded MMAPI packet transport. Physical playback, gaps/latency,
  file permissions and device input remain unqualified.
- Add a reproducible gallery of the production phone UI and host save/load
  continuation checks with Sonic, Golden Sun and LeafGreen.

## 0.7.0-SNAPSHOT — 2026-11-05

- Resume CPU internal work, data accesses, branch/exception refills and opcode
  fetches across DMA yields while preserving interrupt acceptance and frame/audio budgets.
- Charge discarded IRQ fetches using source memory waits and ARM/Thumb width;
  preserve Game Pak prefetch during off-cartridge DMA and qualify page-edge timing.
- Add independent timing probes and portable regression fixtures. Repeat checks
  against exported and actual ECJ/CLDC classes, and rebuild the BlackBerry COD.
- Derive the JAD version from Maven and remove fixed application-JAR versions from CI.
- Replace the README still with a reproducible four-game GUI GIF and consolidate
  current validation and next steps in the roadmap.

This is a development version. Full game accuracy, physical playback performance
and BlackBerry runtime compatibility remain under validation; see
[the current roadmap](docs/BENCHMARKS.md).

## 0.6 development

- Add desktop keypad input, clocked PSG/Direct Sound output and an audio diagnostic.
- Separate feature packages, portable core/export tooling and bounded host capture workers.
- Establish real-BIOS game captures, player-save cross-loads and independent
  graphics/audio comparisons; build the shared core through JDE/ECJ/RAPC.

Git history preserves individual fixes and their detailed evidence.

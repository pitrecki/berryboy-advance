# Changelog

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

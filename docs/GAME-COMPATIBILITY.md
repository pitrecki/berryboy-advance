# Uploaded-game compatibility smoke, 2026-10-22

Eleven user-uploaded ZIPs supplied eleven unique USA-region ROMs (LeafGreen is
Rev-A). ZIP CRC verification and GBA header checksums passed; SHA-256 identities
were recorded. This verifies local asset identity, not independent dump provenance.
ROMs, BIOS, PNG/WAV captures and personal saves are excluded from Git.

Tested source: `1d9f541bde42b267937f692a40693b32a44af1c7`, clean working tree.
Host: Linux x86_64, Java 21.0.8+9. Full per-game hashes, counters, runtime metadata,
errors and replay evidence are in
[the machine-readable record](../benchmarks/uploaded-games-smoke-2026-10-22.json).

## Protocol and outcome

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
The root cause is not yet isolated; accepting the read blindly would hide it.

## Local reproduction

Assets and their manifest are under `/workspace/roms/gba`. Exact scenario
properties, inputs, logs and summaries are preserved under
`/workspace/roms/gba/validation/2026-10-22/`. Original captures remain in
`target/uploaded-games-smoke-2026-10-22` and
`target/uploaded-games-input-smoke-2026-10-22`.

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

1. Isolate the reproducible Metroid Fusion invalid-address read and add a small
   original regression before fixing the CPU/bus behavior responsible.
2. Investigate the two missing-frame counts in Wario Land 4, GTA Advance,
   Zone of the Enders and Metroid Zero Mission. Distinguish runner accounting,
   guest timing and actual missing raster output before changing code.
3. Create per-game input routes and checkpoints, especially for black final
   frames, then obtain independent video/audio references.
4. Exercise save/write/restart/load round trips for each detected backup type;
   validate RTC separately where the game uses it.
5. Only after correctness gates pass, execute long warmup/measurement runs and
   the SDK/device qualification protocol in [BENCHMARKS.md](BENCHMARKS.md).

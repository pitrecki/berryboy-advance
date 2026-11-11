# Current status and roadmap

Updated **2026-11-11**, for **0.9.1-SNAPSHOT**. Latest published phone preview:
[**0.9.0**](https://github.com/pitrecki/berryboy-advance/releases/tag/v0.9.0).

**Next deliverable: M1 — one complete Golden Sun application session.**
Use the supplied games and existing frontend. New CPU/DMA/VRAM investigations
need a demonstrated milestone blocker or an explicit user priority.

## Roadmap review

The project already has the phone frontend, local ROM/BIOS selection, five state
slots, cheats, remapping, shoulder assist, audio/display settings and speed modes.
The SDK builds a COD and the exported core passes its portability checks.

The main delivery gap is a connected player journey and sustained gameplay
coverage. Most of the fifteen game routes are boot/intro smoke tests. Named
Doom II first-level, F-Zero race, Mario & Luigi movement and LeafGreen save routes
provide useful gameplay evidence, but do not establish a completed three-game
playability pack, whole-application acceptance or measured Bold performance.

The previous queue put hardware-model disagreements ahead of those deliverables.
Those disagreements remain recorded below; they no longer automatically block
every frontend, gameplay, profiling or preview task. Test counts and new probe
matrices support a milestone; they do not complete it by themselves.

<a id="next-roadmap-correctness-gameplay-then-sustained-playback"></a>

## Delivery milestones

| Milestone | User-visible result | Finish line | Current status |
| --- | --- | --- | --- |
| **M1 — Golden Sun complete session** | Open the application and actually play, then save and resume. | One continuous production-frontend route: Home → external BIOS/ROM selection → authentic GBA boot → game menu → controlled movement, interaction and a room/map transition → pause → save/load one slot → continue playing. Retain input/checkpoints and an uninterrupted app recording with its capture environment identified. | **Next.** Boot/intro captures and core state replays exist; the entire player journey is not yet accepted. |
| **M2 — Three-game playability pack** | Demonstrate useful progression in three supplied games. | Golden Sun: complete a battle and return to the field. Sonic Advance 3: complete the first act. LeafGreen: obtain a starter, complete the first rival battle, save in-game and cold-load that progress. Each route has recorded input, named checkpoints and a repeatable endpoint. | **Open.** Existing shorter routes and LeafGreen saves are starting points. Keep all fifteen smoke routes as regression coverage. |
| **M3 — Phone frontend acceptance** | The implemented settings and save features work together during play. | Complete the interaction matrix below through application commands; preferences survive restart, cancellation/failures preserve the running game and committed saves, and input never remains stuck. Exercise state formats 1/2 loading and current-format saving. | Features implemented and individually tested; combined acceptance remains open. Host acceptance and BBOS execution are reported separately. |
| **M4 — Playback baseline and measured optimization** | Establish where time and audio continuity are lost in playable scenes. | Reproducible profiles for the three M2 scenes at 1×, full sound and frame skip off: guest work, allocations/GC, queue bounds and produced/consumed PCM. Measure presentation/audio where the environment supports them. Fix one demonstrated bottleneck if needed and compare the same workload/output before and after. | Headless capture timings exist; the three-scene playback baseline and sustained target qualification are open. |
| **M5 — Reviewed phone preview candidate** | A concrete, reviewable next phone build and release decision. | M1–M3 accepted, M4 baseline recorded, host/export/SDK/actual-CLDC checks pass, and release notes identify remaining accuracy/runtime limits. Present the candidate commit and build results. After the user's release approval, freeze the version, tag and publish the phone ZIP with COD siblings, JAD/ALX, instructions, provenance and checksums. | The build pipeline works and 0.9.0 is published. No new tag/package is authorized by this roadmap. |

M1 precedes the larger M2 pack. M3 acceptance can progress alongside M2; M4
uses their playable scenes. M5 is a **preview** decision, separate from complete
hardware-accuracy and physical-device qualification. The release version is
chosen with the user at that decision, not automatically for each milestone.

### Immediate M1 work

1. Reuse the supplied Golden Sun (`AGSE`) ROM, external BIOS and existing input
   recording/capture tools. Establish the first controllable scene from a cold
   application launch; do not replace startup with a preloaded machine state.
2. Record deliberate movement, interaction and a map/room transition, then drive
   pause/save/load through the frontend. Use an owned local save copy.
3. Verify the resumed scene and controls, plus an exact 120-frame RGB/PCM and
   full-state continuation. Produce one continuous app capture of that route.
4. Fix a core issue only when this route exposes a concrete crash, hang, broken
   action, blocking visual/audio fault or lost progress. Tie the regression to
   that observable failure, then return to M1.

### M3 application acceptance matrix

| Area | Required combined-flow check |
| --- | --- |
| Library and startup | Choose another readable ROM folder, restart the app, browse from that folder, select an external BIOS, reject an unreadable/wrong-size BIOS, cancel browsing safely, and exercise Show/Skip startup. |
| Slots and battery saves | Use all five slots, overwrite and reload, reject wrong-ROM/BIOS and damaged states safely, reopen the app and retain committed progress. Keep interrupted-write recovery and legacy-state loading covered. |
| Controls | Remap all ten GBA buttons; combine D-pad/A/B with independent L/R locks; release keys on focus loss, pause, load, restart and game changes. Persist mappings, never held keys. |
| Cheats | Add, enable, disable and delete a supported Raw/unencrypted CodeBreaker 3/8 entry; observe its game effect and safely report invalid/unsupported input. |
| Sound and speed | Change volume, mono and mute; exercise 1×/2×/4×/8×; acceleration mutes output and 1× restores the independent mute preference. Track actual MMAPI playback separately. |
| Display and diagnostics | Preserve the complete 240×160 image in aspect-fit mode; test stretch/native/integer modes, frame skip and FPS overlays. Display submission policy must not skip guest execution or audio. |

## Native BBOS execution: parallel target gate

The SDK/ECJ/RAPC build is verified. The installed 9900 simulator has opened a
window and accepted a controller `LoadCod` command; no confirmed BBOS boot or
BerryBoy application interaction has been observed. The touch instruction/blank
window outcome and host audio limitation are described in
[BLACKBERRY.md](BLACKBERRY.md#vendor-preverification-and-current-simulator-outcome).
No physical Bold is available.

Make one bounded environment attempt tied to **install → open app → browse ROM
→ play → pause/load**. If the same environment limitation prevents this, record
the exact blocker and continue M1–M4 on the available host/frontend harness.
Do not spend successive delivery batches changing Wine/QEMU settings without
new evidence. Native key input, file permissions, MMAPI behavior and device
performance remain explicit target checks; host doubles do not pass them.

## Current validation

- **5234 host tests**, **4973 exported-core tests** and **4973 tests against the
  actual new ECJ/CLDC classes** pass without failures or skips. Native JDE 7.1 /
  ECJ 4.6.1 / RAPC builds the 0.9.1 development COD. Host: Linux x86_64,
  Temurin 21.0.8, Maven 3.9.11.
- The latest [writable-memory checkpoint](../benchmarks/video-opcode-latching-2026-11-11.json)
  closes the in-progress opcode correction: 2240 shared reference observations,
  forty old-format phase snapshots, six real-game migrations and 56400 frames
  across fifteen routes. Complete RGB/PCM/Flash and guest/sample counts remain
  unchanged from the preceding checkpoint. These are regression results, not
  fifteen completed gameplay qualifications.
- Machine states now write **format 3** and load **formats 1/2/3**. Saved WRAM
  opcodes from format 2 survive migration. Earlier formats reconstruct missing
  opcodes from memory; they cannot recover instruction data they never stored.
  Earlier app versions reject new format-3 files. ROM/BIOS identity, CRC and
  failed-load isolation remain enforced.
- [Frontend/state checks](../benchmarks/phone-ui-2026-11-06.json),
  [folder selection](../benchmarks/rom-folder-2026-11-06.json),
  [BIOS selection](../benchmarks/bios-settings-2026-11-09.json),
  [playback speed](../benchmarks/playback-speed-2026-11-06.json) and
  [shoulder assist](../benchmarks/shoulder-assist-2026-11-06.json) retain detailed
  feature evidence. [Phone storage failures](../benchmarks/phone-storage-interruption-2026-11-09.json)
  and [atomic host saves](../benchmarks/atomic-save-publication-2026-11-09.json)
  cover injected IO failures and recovery.
- [Gameplay routes](../benchmarks/gameplay-state-routes-2026-11-06.json) record
  F-Zero racing, Mario & Luigi movement and Doom II state continuations.
  [LeafGreen outdoor saves](../benchmarks/leafgreen-overworld-save-2026-11-06.json),
  [counter wrap](../benchmarks/leafgreen-counter-wrap-2026-11-09.json) and
  [corrupt slots](../benchmarks/leafgreen-both-slots-2026-11-09.json) retain
  actual save/recovery results. Full reference RGB/PCM/save agreement remains open.

## Accuracy and research backlog

Promote a backlog item into active delivery when a milestone reproduces its
player-visible failure, user data is at risk, a regression appears, or the user
explicitly prioritizes it. Otherwise retain the counterexample and dependency
and continue the next milestone. Do not start another unrelated timing matrix
just because the previous one was committed.

| Item | Known result and dependency | Delivery trigger |
| --- | --- | --- |
| DMA/LCD activation and lifecycle | Natural LCD probes distinguish status, DMA readiness/writes and IF. NBA/mGBA disagree on fine timing; production activation remains immediate. Choosing a model needs independent hardware/specification evidence. [Evidence](../benchmarks/lcd-natural-phases-2026-11-06.json). | A named M1/M2 gameplay failure or independently justified expected timing. |
| IRQ/prefetch latency | The prefetch correction matches 196 shared observations; 92 remain disputed. The expanded 360-row host/CLDC stream remains unchanged. [Evidence](../benchmarks/prefetch-refill-2026-11-10.json). | A scenario blocker with a bounded counterexample, rather than another broad sweep. |
| Graphics accuracy | Brightness rounding/palette bit 15 still have 2244 three-way disagreements. Combined effects and display contention remain unqualified. [Earlier diagnosis](REFERENCE-VALIDATION.md). | A concrete scene fault; production expectations need independent justification. |
| Audio correctness | Sonic/Wario reference PCM differences and real MMAPI gaps/latency remain open. Separate timer/FIFO events from resampling/filtering and transport. | Audible failure in M2/M3, or a bottleneck demonstrated by M4. |
| LeafGreen RNG/save differences | Last traced Timer1 seed is 5726 versus 5780, with 630 matching changes before it and a caller-order divergence at change 1282. The latest full outdoor-save checkpoint has 37 unclassified NPC/Quest Log bytes; both cold loads work. Strict comparisons still fail. [Evidence](../benchmarks/prefetch-refill-2026-11-10.json). No new full RNG/save trace was run in the video-opcode step. | Lost/incorrect progress in the M2 route, or an independently supported timing diagnosis. Never force seeds or normalize away payload bytes. |
| Additional features | Rewind, link play, encrypted cheat formats, new sensors and GPU acceleration are deferred. WarioWare: Twisted! ROM/sensor recordings are unavailable. | Reconsider after M1–M3; existing supplied games are sufficient for the next milestones. |

## Evidence and acceptance rules

Functional playability, reference accuracy, build portability and physical
performance have separate results. A functional milestone may finish with
explicitly open accuracy issues; its evidence must retain the strict failing
RGB/PCM/save results. Crashes, hangs, broken required actions and lost/corrupt
player progress block the affected milestone and preview. Do not relax pixel,
audio or binary comparisons, mask differences or relabel them as accurate.

Every core change retains the existing regression fixtures, previously exact
reference checkpoints and guest/audio continuity. Record source/build, hardware,
ROM/BIOS/input/save hashes, route/checkpoint coordinates and reference version.
Use fresh output paths and disposable save copies. Keep ROMs, BIOS, SDKs, saves
and generated captures out of Git. [SCENARIOS.md](SCENARIOS.md) owns commands;
linked JSON and Git history retain earlier detailed measurements.

The long-term target remains `16777216 / 280896` = **59.7275 refreshes/s**,
**16.7427 ms/frame**, with full sound, frame skip off and no audio-buffer loss.
The original [qualification suite](../benchmarks/suite.json) remains planned:
30-second warmup, three 10-minute runs, actual presentation/audio measurements,
bounded memory/queues and A/V drift within one native frame without accumulation.
Headless work timings and instruction throughput cannot pass that physical gate.
Its missing fifth-game/sensor assets do not block M1–M4 or a clearly scoped preview.

Run `mvn verify` for code changes, `tools/test-with-console.sh` for portable-core
or export changes, and SDK/actual-CLDC checks for affected phone/core work.
Use feature packages and one deterministic guest owner; parallel host workers
receive bounded owned snapshots. Report progress as **milestone → visible result
→ remaining acceptance**, with test counts as supporting evidence.

Completed verified updates are committed/pushed as the configured user and the
remote revision is checked. Routine pushes create no tags or release ZIPs.
After M5 has a concrete candidate, ask the user for that release decision;
preserve published tags/assets. The published
[0.9.0 release record](../benchmarks/release-0.9.0-2026-11-06.json) and
[package instructions](BLACKBERRY.md#release-packages) define the delivery format.

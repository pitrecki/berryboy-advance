# Current status and roadmap

Updated **2026-11-20**, for **0.9.2-SNAPSHOT**. Latest published phone preview:
[**0.9.1**](https://github.com/pitrecki/berryboy-advance/releases/tag/v0.9.1).

**M1–M6 complete within their recorded host/preview scope. M7: F-Zero and Tony Hawk 2 accepted on host; Doom II next.**
Use the supplied games and existing frontend. New CPU/DMA/VRAM investigations
need a demonstrated milestone blocker or an explicit user priority.

## Roadmap review

The project already has the phone frontend, local ROM/BIOS selection, five state
slots, cheats, remapping, shoulder assist, audio/display settings and speed modes.
The SDK builds a COD and the exported core passes its portability checks.

The connected M1 session, all three M2 progression routes and the M3 combined
frontend matrix now pass on the host. The fifteen boot/intro smoke routes remain
regression coverage alongside named Doom II, F-Zero and Mario & Luigi gameplay
routes. Three active scenes have isolated paced playback profiles and the approved
0.9.1 phone preview is published. M6 now adds two 36000-frame sessions per game,
repeated slot/lifecycle operations, bounded sampled retained heap and LeafGreen
new-save cold Continue. The main remaining gaps are progression in the demanding
supplied titles and actual BBOS behavior. F-Zero has a completed five-lap Grand
Prix race; Tony Hawk 2 has a naturally completed timed run with a landed trick
and retained score. Doom II and Mario & Luigi already have boot or control
routes, so their next useful result is a completed gameplay objective with
preserved progress.

The previous queue put hardware-model disagreements ahead of those deliverables.
Those disagreements remain recorded below; they no longer automatically block
every frontend, gameplay, profiling or preview task. Test counts and new probe
matrices support a milestone; they do not complete it by themselves.

<a id="next-roadmap-correctness-gameplay-then-sustained-playback"></a>

## Next milestones

| Priority / milestone | User-visible result | Acceptance and evidence | Dependency / status |
| --- | --- | --- | --- |
| **1. M7 — Demanding-game progression pack** | Demonstrate actual progression beyond the existing intro/control checkpoints in supplied action and 3D-heavy games. | F-Zero: finish one race and show its result. Tony Hawk 2: complete a timed run with a recorded trick and score. Doom II: reach the first level's exit/result. Mario & Luigi: win the first battle, reach an in-game save and cold-load the progress. Each route retains input, named checkpoints, complete frames, capture-buffer accounting and exact state continuation on host/CLDC. Keep strict reference mismatches visible. | **F-Zero and Tony Hawk 2 accepted, 2026-11-20:** [Tradition Park win](../benchmarks/fzero-tradition-park-2026-11-20.json), [timed Hangar run / landed Boardslide / Statistics](../benchmarks/tony-hawk-timed-run-2026-11-20.json). **Next: Doom II first exit/result**, then Mario & Luigi. A visible blocker warrants one bounded diagnosis; it does not reopen a broad timing sweep. |
| **Parallel. M8 — Native BBOS session and device playback** | Install the package, open a supplied game and use the implemented controls, audio and saves on the target. | Install → open → choose local BIOS/ROM → boot → play → pause/save/load → close/reopen. Verify native input/focus, file permissions and MMAPI. Separately on a Bold 9900, run the sustained suite: 30-second warmup, three 10-minute runs, full sound, no frame skips, native refresh, bounded memory/queues and measured transport losses/latency/A/V drift. | **Blocked on a working BBOS target; physical performance also needs a Bold.** The existing simulator attempt did not confirm app execution. Retry environment work only with new evidence; this gate does not block M6/M7 host work. |

### Next executable slice

1. Extend Doom II's supplied `A9DE` first-level route to an actual exit and
   intermission/result. Reuse [its recorded startup controls](../benchmarks/inputs/doom-entry-2026-11-06.tsv)
   and [the existing state-continuation evidence](../benchmarks/gameplay-state-routes-2026-11-06.json).
   The fresh local seed is `target/m7-doom-2026-11-20/seed-state/state.bbs`
   at frame 2400; exploratory actions/captures stay under its `play` directory.
   Use ordinary movement, fire/use and the in-game map; level entry alone does
   not complete this objective.
2. Preserve named gameplay/result checkpoints, complete RGB/PCM and capture
   accounting; verify a 120-frame RGB/PCM/full-state continuation on host and
   isolated actual CLDC classes. A concrete blocker gets a bounded reproducer.
3. Continue with Mario & Luigi battle/save/cold Continue. Keep detailed results
   in linked JSON rather than a second status guide.

[Tony Hawk 2 M7 evidence](../benchmarks/tony-hawk-timed-run-2026-11-20.json)
records 9143 cold-started frames through The Hangar's two-minute career run.
The FS Boardslide is landed for **536 points**; after natural clock expiry,
Statistics confirms total **536**, **one trick landed** and a **536-point best
combo**. Failed provisional tricks are excluded. Both the landed checkpoint and
complete final state match independent cold replay byte-for-byte. All fifteen
named checkpoints and the 120-frame RGB/PCM/full-state host/CLDC continuation
pass without capture losses. The old strict reference-prefix mismatch remains
recorded; this accepts one timed run, separate from career-goal completion.

[F-Zero M7 evidence](../benchmarks/fzero-tradition-park-2026-11-20.json) records
14264 cold-started frames through **Grand Prix / Novice / Bronze / Mute City —
Tradition Park**, all five laps and **first place, 2:17.33** on the Results panel.
All eighteen named checkpoints pass their image comparisons. Complete future states
and 120-frame RGB/PCM match on host and actual CLDC classes; captured output has
no losses. The older Rick Wheeler #1 route is a Story mission, so its failed
chase attempts are not race-completion evidence. The new input explicitly
selects Grand Prix. The retained strict reference-prefix mismatch remains visible;
full-race independent accuracy and completion of the whole cup are not claimed.

[LeafGreen M6 evidence](../benchmarks/leafgreen-reliability-2026-11-20.json)
closes the three-game host pack. Each of its two 36000-frame runs preserves all
twenty complete checkpoints, RGB/PCM and a 120-frame actual-CLDC continuation.
Both perform a genuine new in-game save, advancing generation 5 to 6, then cold
boot their own committed battery through Continue to Bulbasaur L6 / 204 XP /
22 HP. No emulator state is loaded during cold boot; the older generation-3
legacy input stays unchanged. Both cold endpoints and a separate battery-only
replay match byte-for-byte, including a 120-frame full-state CLDC continuation.
Sampled post-warmup retained-heap spreads are 63512 and 1048368 bytes, within the
declared 8 MiB bound. This is host reliability evidence, separate from M8.

Sonic's two 36000-frame hub routes now match all twenty complete checkpoints,
RGB/PCM streams and the shared 120-frame CLDC continuation. Each performs 21
slot writes and 12 loads; pending Act 1 autosave bytes survive controller
recreation. Retained-heap spread is about 65 KB. The route stays in the live hub
and reaches no new game-save command; its cold game-level continuation is not
claimed. [Preparation evidence](../benchmarks/sonic-reliability-preparation-2026-11-20.json)
retains the cold Act 1-to-hub entry and the successful-write receipt regression.

The Golden Sun slice uses repeated recorded Up/Down field movement after its
accepted M2 first battle. Each of two independent UI runs completes 36000 frames
(~10 minutes of guest time), 20 rotating checkpoint writes plus one continuation
write, 10 checkpoint loads plus initial/recreation loads, five focus cycles and
one controller recreation. Complete committed states, RGB/PCM and the final
120-frame continuation agree with independent replay and actual CLDC classes.
Post-warmup retained heap stays within about 66 KB in each run, with exactly two
sidecar generations per slot. Host adapters and their temporary graphics buffers
are included; these measurements do not establish a device heap budget. This
field route reaches no new in-game save, so battery-progress cold reload is not
claimed for this slice. LeafGreen supplies M6's new-save cold-Continue coverage.

M7 proceeds one game at a time so a concrete failure is actionable before
expanding coverage. A failing slice produces a named reproducer and a focused correction;
finish that correction before extending the pack. Avoid unmeasured optimizations
and broad package refactors. New scenarios belong in their feature's host tooling;
production fixes stay with the feature that owns the behavior.

The next release checkpoint follows accepted M6/M7 results or a substantial
user-visible fix. Present the tested change and remaining M8 limits, then ask
for a new release decision. Approval for 0.9.1 does not authorize another tag.
Rewind, link play, encrypted cheats and new sensor work remain deferred.

## Completed delivery milestones

| Milestone | User-visible result | Finish line | Current status |
| --- | --- | --- | --- |
| **M1 — Golden Sun complete session** | Open the application and actually play, then save and resume. | One continuous production-frontend route: Home → external BIOS/ROM selection → authentic GBA boot → game menu → controlled movement, interaction and a room/map transition → pause → save/load one slot → continue playing. Retain input/checkpoints and an uninterrupted app recording with its capture environment identified. | **Host accepted, 2026-11-18.** Cold launch, controlled house entry/barrel interaction and GUI save/load pass; both 120-frame RGB/PCM/full-state continuations are exact. [Evidence](../benchmarks/golden-sun-session-2026-11-18.json). Native BBOS acceptance remains separate. |
| **M2 — Three-game playability pack** | Demonstrate useful progression in three supplied games. | Golden Sun: complete a battle and return to the field. Sonic Advance 3: complete the first act. LeafGreen: obtain a starter, complete the first rival battle, save in-game and cold-load that progress. Each route has recorded input, named checkpoints and a repeatable endpoint. | **Host accepted, 2026-11-19.** [Golden Sun Vermin victory and controlled field return](../benchmarks/golden-sun-first-battle-2026-11-19.json), [Sonic Route 99 Act 1 clear](../benchmarks/sonic-route99-act1-2026-11-19.json), and [LeafGreen starter/rival/save/reload](../benchmarks/leafgreen-starter-rival-2026-11-18.json) pass with recorded inputs and exact state continuations. Full-game compatibility and native target qualification remain separate. |
| **M3 — Phone frontend acceptance** | The implemented settings and save features work together during play. | Complete the interaction matrix below through application commands; preferences survive restart, cancellation/failures preserve the running game and committed saves, and input never remains stuck. Exercise state formats 1/2 loading and current-format saving. | **Host accepted, 2026-11-19.** [Complete matrix and playback/lifecycle checks](../benchmarks/phone-playback-acceptance-2026-11-19.json), [library/startup and slots](../benchmarks/phone-library-slots-acceptance-2026-11-19.json), [controls](../benchmarks/phone-controls-acceptance-2026-11-19.json) and [cheats](../benchmarks/phone-cheats-acceptance-2026-11-19.json) pass. All six combined UI journeys and existing regression suites pass together. Native BBOS qualification remains separate. |
| **M4 — Playback baseline and measured optimization** | Establish where time and audio continuity are lost in playable scenes. | Reproducible profiles for the three M2 scenes at 1×, full sound and frame skip off: guest work, allocations/GC, queue bounds and produced/consumed PCM. Measure presentation/audio where the environment supports them. Fix one demonstrated bottleneck if needed and compare the same workload/output before and after. | **Host baseline accepted, 2026-11-19.** [Three-scene results, raw repetitions and rejected experiments](../benchmarks/playback-baseline-2026-11-19.json); [reproduction commands](SCENARIOS.md#paced-playback-profiles). RGB/PCM/full states remain exact, queue maximum 1/2, no capture-buffer losses. Two read-path experiments establish no consistent useful gain and are reverted. Sustained target qualification remains open. |
| **M5 — Reviewed phone preview** | A reviewed, installable phone preview with explicit limits. | M1–M4 accepted in their stated scope; host/export/SDK/actual-CLDC checks pass; the approved numeric version is tagged and the phone ZIP contains raw COD siblings, JAD/ALX, instructions, provenance and checksums. | **Published, 2026-11-19.** [0.9.1 preview](https://github.com/pitrecki/berryboy-advance/releases/tag/v0.9.1), tag `v0.9.1`, commit `3ae037c`. [Release verification](../benchmarks/release-0.9.1-2026-11-19.json) retains the public checksum and manifest. [Candidate evidence](../benchmarks/phone-preview-candidate-2026-11-19.json) remains the pre-release checkpoint. Native runtime/performance acceptance is M8. |
| **M6 — Long-session durability** | Play and repeatedly save/resume without losing progress or accumulating stuck input/output work. | Two active 36000-frame routes per game at 1×, full sound and no skips; all five slots, at least 20 writes and 10 restores, focus/controller recreation, exact checkpoints and 120-frame RGB/PCM/full-state host/actual-CLDC continuations. Preserve a committed battery through cold reload where the route reaches a new in-game save. Record bounded output, sampled retained heap and capture counts. | **Host accepted, 2026-11-20.** [Golden Sun field/slots](../benchmarks/golden-sun-reliability-2026-11-20.json), [Sonic hub/autosave/slots](../benchmarks/sonic-reliability-2026-11-20.json) and [LeafGreen new-save/cold Continue plus the complete acceptance matrix](../benchmarks/leafgreen-reliability-2026-11-20.json). All six runs pass. Golden Sun/Sonic reach no new in-game save command in these windows; their new-save cold Continue is not claimed. Physical transport and phone speed remain M8. |

M1–M4 and M6 are host milestones, with separate isolated CLDC portability checks.
M5 is the approved preview publication, not a claim of full game accuracy or
physical phone qualification. Their detailed evidence stays linked above.

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

When new runtime evidence becomes available, make one bounded attempt tied to
**install → open app → browse ROM
→ play → pause/load**. If the same environment limitation prevents this, record
the exact blocker and continue M6/M7 on the available host/frontend harness.
Do not spend successive delivery batches changing Wine/QEMU settings without
new evidence. Native key input, file permissions, MMAPI behavior and device
performance remain explicit target checks; host doubles do not pass them.

## Current validation

- **5308 host tests**, **4973 exported-core tests** and **4973 tests against the
  actual new ECJ/CLDC classes** pass without failures or skips. Native JDE 7.1 /
  ECJ 4.6.1 / RAPC builds the 0.9.2 development COD. Host: Linux x86_64,
  Temurin 21.0.8, Maven 3.9.11.
- Confirmed restart/overwrite callbacks now publish acknowledgement before
  worker completion, preserving the final visible status. Both deterministic
  race cases pass alongside the production-session library tests. The Sonic
  preparation record also retains a fresh 1200-frame Golden Sun UI regression
  after this change and exact checks of committed battery data/IO receipts.
- [0.9.1 release verification](../benchmarks/release-0.9.1-2026-11-19.json)
  records the successful publication workflow and downloaded public ZIP. Its
  published SHA-256, all internal checksums, raw COD siblings, CLDC JAR and
  JAD/ALX agree; source-input and SDK/compiler hashes match the local clean build.
- [M5 portability evidence](../benchmarks/phone-preview-candidate-2026-11-19.json)
  preserves all three 900-frame scenes on actual CLDC core/mobile classes with
  isolated desktop-only helpers: RGB, 493763 stereo PCM frames and full future
  states match the host. Core/frontend/tooling sources are unchanged at v0.9.1.
  These are Linux JVM checks, separate from BBOS execution and phone speed.
- [Golden Sun M2 evidence](../benchmarks/golden-sun-first-battle-2026-11-19.json)
  records 24420 cold-started frames through a Vermin victory, 2 experience points,
  2 coins and controlled movement after returning to the field. Thirteen named
  extension checkpoints match the explored route. All 120 resumed RGB/PCM
  frames and the complete future state match, including isolated actual CLDC
  classes. No guest frames or captured PCM are lost.
- [Sonic M2 evidence](../benchmarks/sonic-route99-act1-2026-11-19.json) records
  24709 cold-started frames through the actual **ACT 01 CLEAR** result. All ten
  named checkpoints reproduce the explored route; all 120 resumed RGB/PCM frames
  and the complete future state match, including isolated actual CLDC classes.
  No guest frames or captured audio are lost. This accepts the first-act route,
  separate from independent pixel/audio accuracy and physical phone playback.
- [M3 controls evidence](../benchmarks/phone-controls-acceptance-2026-11-19.json)
  drives the production application controller through remapping, shoulder-lock
  combinations, slot operations and focus/controller lifecycle. A synthetic
  guest records KEYINPUT; inspection loads only committed owned state bytes.
  Mappings and saved slots survive controller recreation, while held keys do
  not. Confirmed restart and game-change release are now covered by the playback
  and lifecycle update below as well.
- [M3 cheat evidence](../benchmarks/phone-cheats-acceptance-2026-11-19.json)
  verifies Raw and both supported CodeBreaker types through the application
  form, with RAM effects inspected only from committed owned snapshots. Enabled
  and disabled profiles survive controller recreation independently of slots;
  invalid/cancelled drafts and deletion preserve the original clean save.
  The host choice adapter now exercises both format branches and the completion
  barrier waits for pending cheat-profile writes.
- [M3 library/slot evidence](../benchmarks/phone-library-slots-acceptance-2026-11-19.json)
  covers real picker commands, persisted ROM/BIOS paths, Show/Skip startup and
  whole-state preservation through cancellation/read failures. All five slots
  reload exactly after controller recreation; wrong-ROM/BIOS and damaged states
  are rejected, a partial overwrite can be retried safely, and formats 1/2 load
  through the UI and save as format 3. Remaining storage failure phases retain
  their existing individual coverage.
- [M3 playback/lifecycle evidence](../benchmarks/phone-playback-acceptance-2026-11-19.json)
  closes the host matrix. Guest-generated PCM verifies volume, mono, mute and
  acceleration/restoration; all 38400 source pixels survive native/integer/fit/
  stretch rendering. Presentation skip retains guest execution and every PCM
  packet, FPS updates, preferences survive controller reopen, and restart/game
  changes release input while preserving the previous game's committed slots.
  These are host policy/integration checks, not physical playback measurements.
- [LeafGreen M2 evidence](../benchmarks/leafgreen-starter-rival-2026-11-18.json)
  retains the complete 24410-frame cold route and a separate 2500-frame battery
  reload. The saved Bulbasaur is L6, 204 XP and 22/22 HP; party and lab progress
  survive restart, with the battery file unchanged during reload. The 120-frame
  RGB/PCM/full-state continuation is exact on the host and its complete final
  state matches isolated actual CLDC classes. Frame-boundary host inputs now
  support long sessions without placing every future button change in the guest
  clock; the existing cycle/sensor recording format retains its original behavior.
- [M1 session evidence](../benchmarks/golden-sun-session-2026-11-18.json) records
  a fresh GUI launch through frame 5973, the house transition/barrel interaction,
  and two exact 120-frame continuations with 65835 stereo PCM frames each.
  Loading a phone slot now preserves the emulated audio ring position instead
  of resetting it. The complete 45.6-second recording and replay are reproducible
  with `tools/capture-golden-sun-session.sh`; this is host frontend acceptance,
  separate from BBOS execution and independent hardware accuracy.
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
| DMA/LCD activation and lifecycle | Natural LCD probes distinguish status, DMA readiness/writes and IF. NBA/mGBA disagree on fine timing; production activation remains immediate. Choosing a model needs independent hardware/specification evidence. [Evidence](../benchmarks/lcd-natural-phases-2026-11-06.json). | A named M6/M7/M8 failure or independently justified expected timing. |
| IRQ/prefetch latency | The prefetch correction matches 196 shared observations; 92 remain disputed. The expanded 360-row host/CLDC stream remains unchanged. [Evidence](../benchmarks/prefetch-refill-2026-11-10.json). | A scenario blocker with a bounded counterexample, rather than another broad sweep. |
| Graphics accuracy | Brightness rounding/palette bit 15 still have 2244 three-way disagreements. Combined effects and display contention remain unqualified. [Earlier diagnosis](REFERENCE-VALIDATION.md). | A concrete scene fault; production expectations need independent justification. |
| Audio correctness | Sonic/Wario reference PCM differences and real MMAPI gaps/latency remain open. Separate timer/FIFO events from resampling/filtering and transport. | Audible failure in an M6/M7 route or an M8 transport measurement. |
| LeafGreen RNG/save differences | Last traced Timer1 seed is 5726 versus 5780, with 630 matching changes before it and a caller-order divergence at change 1282. The latest full outdoor-save checkpoint has 37 unclassified NPC/Quest Log bytes; both cold loads work. Strict comparisons still fail. [Evidence](../benchmarks/prefetch-refill-2026-11-10.json). No new full RNG/save trace was run in the video-opcode step. | Lost/incorrect progress in M6/M7, or an independently supported timing diagnosis. Never force seeds or normalize away payload bytes. |
| Additional features | Rewind, link play, encrypted cheat formats, new sensors and GPU acceleration are deferred. WarioWare: Twisted! ROM/sensor recordings are unavailable. | Reconsider after M6/M7 or an explicit user priority; current supplied games cover the active roadmap. |

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
Its missing fifth-game/sensor assets do not block M6/M7 host work or a clearly scoped preview.

Run `mvn verify` for code changes, `tools/test-with-console.sh` for portable-core
or export changes, and SDK/actual-CLDC checks for affected phone/core work.
Use feature packages and one deterministic guest owner; parallel host workers
receive bounded owned snapshots. Report progress as **milestone → visible result
→ remaining acceptance**, with test counts as supporting evidence.

Completed verified updates are committed/pushed as the configured user and the
remote revision is checked. Routine pushes create no tags or release ZIPs.
The user approved and received 0.9.1. Later releases need a separate decision;
preserve published tags/assets. The published
[0.9.1 release record](../benchmarks/release-0.9.1-2026-11-19.json) and
[package instructions](BLACKBERRY.md#release-packages) define the delivery format.

# Independent reference validation

## Discarded IRQ fetch wait-state counterexample, 2026-11-05

The next [original probe](../tools/nba-cpu-irq-fetch-probe.cc) measures accepted
IRQ entry through the first synthetic BIOS handler NOP, with code in IWRAM,
EWRAM or Game Pak. ARM/Thumb each run without DMA, with one-word IWRAM DMA3
ready before the discarded fetch, or with readiness at cycle 1 during it.
All code observations start at region+0x20, away from Game Pak page boundaries.

**6/18 rows match**: all IWRAM controls. The other 12 rows differ only in
cycles; PC/CPSR, LR, r0 and DMA completion agree throughout. The triplets below
mean **no DMA / DMA ready at 0 / DMA ready at 1**:

| Instruction set / code | NBA cycles | BerryBoy cycles |
| --- | --- | --- |
| ARM / IWRAM | 4 / 8 / 8 | 4 / 8 / 8 |
| Thumb / IWRAM | 4 / 8 / 8 | 4 / 8 / 8 |
| ARM / EWRAM | 9 / 13 / 13 | 4 / 8 / 8 |
| Thumb / EWRAM | 6 / 10 / 10 | 4 / 8 / 8 |
| ARM / Game Pak | 9 / 15 / 13 | 4 / 8 / 8 |
| Thumb / Game Pak | 6 / 12 / 10 | 4 / 8 / 8 |

BerryBoy still advances a fixed one-cycle discarded IRQ fetch. NBA charges
the source's word/halfword wait: EWRAM takes 6/3 cycles, and a sequential Game
Pak fetch also takes 6/3 at default waits. DMA before the Game Pak fetch makes
it nonsequential (8/5), adding another two cycles. DMA arriving during that
fetch waits until the subsequent refill access, preserving sequential timing.
The remaining three cycles are the two BIOS refill accesses and handler NOP.

The reference asserts its real pipeline priming and final handler state. Java
executes one preceding ADD to establish a real sequential bus stream, resets
its r0 effect and excludes that retirement; both clocks then align to the
asserted reference warmup. No private reference hooks or source changes are
used. Raw DMA readiness is injected through the original registered event;
activation latency is not qualified. Both adapters compile with warnings
treated as errors and exit successfully. The same 18 observations occur on
retained actual CLDC classes and on the isolated pre-correction CPU, confirming
that this cost gap predates the waiting-fetch fix.

This checkpoint changes tools/docs only. Maven/console reruns pass **1593 host /
1487 exported tests**; native build, full CLDC suite and game evidence are
reused from the preceding correction. Next charge the discarded fetch through
bus timing with the original instruction width, sequential stream and DMA
invalidation. Preserve accepted IRQ/bank order and all existing matrices;
extend prefetch-enabled and page-boundary cases before a general pipeline
accuracy claim. No production wait-state fix is made here.
Evidence: [cpu-irq-fetch-2026-11-05.json](../benchmarks/cpu-irq-fetch-2026-11-05.json).
Reproduction: [SCENARIOS.md](SCENARIOS.md#discarded-irq-fetch-wait-state-probes).

## Resumable opcode fetch and IRQ acceptance, 2026-11-05

The CPU now retains the pending fetch address/mode and the IRQ acceptance
decision before waiting for DMA. A later IRQ cannot overtake that waiting
instruction; deasserting an already accepted IRQ cannot cancel its entry.
After one ownership interval, a waiting fetch receives one access before a
new interval is arbitrated, sharing the existing data/refill grant mechanism.
No per-fetch objects are allocated and each public step still yields after
one DMA unit. IRQ banks its link/status after the discarded fetch completes.
`fetchInFlight()` identifies this pre-decode phase separately from
`instructionInFlight()`. HALT starts no fetch before wakeup; a fetch diagnostic
clears pending state and CPU memory context without replaying completed DMA.

The original **16-row handoff matrix now matches 16/16**, versus 10/16 before.
An [additional original probe](../tools/nba-cpu-fetch-irq-probe.cc) matches
**120/120 observations**, versus 48/120 on the preceding CPU. It covers ARM/
Thumb, three one-word DMA source regions, IRQ assertion/deassertion, five
edge deadlines and two retirements. Code stays in IWRAM. The reference uses
the original registered DMA-activation/IRQ-line callbacks; Java normalizes
readiness and enabled IF at the same deadlines. Neither side certifies
peripheral propagation latency or request activation behavior.

All **83 new core cases pass**. The prior CPU fails 47 and passes 36 with only
an inert false `fetchInFlight()` query added to isolated old classes so the
tests can load. Four 65536-word EWRAM-to-EWRAM transfers retain frame/PCM
budgets while the pending decision survives IRQ assertion or deassertion.
Code remains outside that DMA destination, so these cases do not freeze an
unqualified self-modifying opcode-latch rule. DMA-only repeated-transfer
fixtures now call `DmaController.runPending()` directly: their literal cycle,
byte/FIFO and EEPROM expectations are unchanged. CPU arbitration is qualified
by the new probes instead of assuming every `Cpu.step()` is a DMA word.

Validation passes **1593 host / 1487 exported / 1487 actual new ECJ CLDC-class
tests**, plus a fresh native JDE/ECJ/RAPC COD build. All six matrices (16 fetch,
120 IRQ edges, 36 refill, 144 forms, 48 data, 96 earlier normalized CPU rows)
match on both host and actual new CLDC classes. The production request matrix
remains unchanged at 24/96 matches. The discarded IRQ fetch retains its
existing one-cycle cost; wider memory waits/prefetch, full opcode latching,
IRQ-line latency, activation/lifecycle and hardware remain separate gates.
No comparison tolerance has changed.

All eleven fresh-backup scenarios complete **39600 guest frames / 21725550
stereo samples**, with zero missing frames or software audio-buffer failures.
Whole RGB and backup hashes remain unchanged in every title; four
initialization saves are written. PCM changes only in V-Rally and Sonic.
Reused mGBA RGB888 exact-frame sets have no gains/losses and full streams
remain unmatched. The separate 1272-frame LeafGreen continuation preserves
full RGB/PCM, final cycles/sample counts, all three raw video states and its
original player save. RGB555 remains 1111/1272 exact frames, first difference
930. These are boot/intro and limited continuation results, not gameplay or
sustained/physical playback qualification.

Evidence: [cpu-fetch-resume-2026-11-05.json](../benchmarks/cpu-fetch-resume-2026-11-05.json).
Reproduction: [SCENARIOS.md](SCENARIOS.md#opcode-fetch-handoff-and-irq-probes).

## Opcode-fetch handoff and IRQ acceptance counterexample, 2026-11-05

An [asset-free probe](../tools/nba-cpu-fetch-handoff-probe.cc) now isolates the
next boundary on the corrected refill core. A ready one-word IWRAM DMA3 owns
cycles 0–4 while the original NBA CPU waits for its opcode bus access. A second
one-word DMA1 becomes ready either during release at 4 or at 5, after the
waiting access starts. Optional IRQ-line assertion uses that same deadline.
ARM/Thumb ADD streams provide two retirement observations per scenario.
Raw readiness and IRQ-line updates use the original reference scheduler's
registered handlers; peripheral activation/IRQ propagation latency is excluded.

**10/16 rows match**, representing **4/8 complete scenarios**. All eight rows
with readiness 5 match. The four release-boundary scenarios differ in six rows:

- Without IRQ, NBA grants the waiting fetch after the first interval: ADD
  retires at **5**, with DMA1 still pending. BerryBoy rechecks DMA at the start
  of every step, drains DMA1 first and retires at **9**. Both reach their second
  ADD retirement at 10, so later agreement does not explain the earlier gap.
- With IRQ asserted during release, NBA executes the already-waiting ADD
  before accepting IRQ. BerryBoy accepts IRQ before that ADD; its first
  retirement is the handler NOP at **12**, rather than ADD at **5**. At the
  equal second-retirement cycle **13**, r0 remains **0 rather than 1**, PC is
  **0x20 rather than 0x1c**, and the saved return link differs. NBA's link is
  **0x03000008 in ARM / 0x03000006 in Thumb**; BerryBoy records **0x03000004**.

The new diagnostic does not alter production code. Its 16 observations are
identical on the retained actual CLDC classes. Both adapters compile with
warnings treated as errors and exit successfully; reference assertions verify
the waiting ADD and next retirement. Observation mismatches remain open, not
passing correctness cases. Maven/console reruns pass **1510 host / 1404
exported tests**. Native build, full CLDC suite and game evidence are reused
from the refill correction, not rerun here.

Before delayed activation, retain the waiting fetch context and the interrupt
acceptance decision across DMA yields. Grant the waiting access after an
ownership interval before arbitrating another interval. Add regressions for
asserted/deasserted IRQ during the wait, release/end-access boundaries and
long-DMA budgets, preserving the existing matrices. Full opcode latching,
self-modifying code and broader IRQ priority remain separate qualification
work. Evidence: [cpu-fetch-handoff-2026-11-05.json](../benchmarks/cpu-fetch-handoff-2026-11-05.json).
Reproduction: [SCENARIOS.md](SCENARIOS.md#opcode-fetch-handoff-and-irq-probes).

## Resumable CPU pipeline refills during DMA, 2026-11-05

The CPU now arbitrates DMA before each of the two refill accesses and retains
the refill address, width and progress across yields. A DMA interruption makes
the following Game Pak access nonsequential. A branch retires only after the
final access; IRQ entry shares that path without counting as an instruction.
The guest keeps one owner and each public step still services at most one DMA
unit. A unit that completes internal timing begins the refill, then yields
before another unit can run. No per-refill objects are allocated.

The earlier **36-row same-mode BX matrix now matches 36/36**, versus 12/36 on
the preceding committed CPU. Another [original probe](../tools/nba-cpu-refill-forms-probe.cc)
matches **144/144 observations**, versus 93/144 before. It covers B/BL, both
state-changing BX directions, ARM LDR/LDM and Thumb POP to PC, Thumb B,
ARM/Thumb SWI and accepted IRQ, with three DMA source regions and no-DMA/raw
readiness at 1/2/3. It compares cycles, PC/CPSR, LR, r2, SP and completed DMA.
The reference core is the same pinned, unmodified NBA revision. The new forms
probe injects readiness through its registered activation event; this is not
an activation-delay or request-lifecycle qualification.

NBA's public `Run()` enters IRQ and executes the first handler instruction in
one call, so both forms adapters stop after that synthetic handler NOP. They
do not use private hooks or alter the reference source. Portable unit tests
separately verify that a suspended IRQ refill neither executes the handler nor
rebanks its return state. `instructionInFlight()` remains false during IRQ
entry, while `BusTiming.refilling()` exposes its pending timing. Ordinary
branch instructions retain `instructionInFlight()` until retirement.

All **52 new core cases pass**. The old CPU fails 38 and passes 14 with only a
constant-false `refilling()` compatibility query added to the isolated old bus;
its emulation behavior is unchanged. Regressions cover the original timing
matrix, state-changing ROM targets, SWI banked link, SPSR-restored Thumb refill
width, release handoff, final-access readiness and internal-DMA completion
without servicing two units per step. Three 65536-word cases retain frame/PCM
budgets during BL and IRQ entry, including code overwrite without replay.

Validation passes **1510 host / 1404 exported / 1404 actual new ECJ CLDC-class
tests** and a fresh native JDE/ECJ/RAPC COD build. All four independent matrices
(36 refill, 144 forms, 48 data, 96 earlier normalized CPU rows) also match when
the adapters run on the new actual CLDC classes. The production request matrix
is unchanged at 24/96 matches. Actual activation/lifecycle, opcode-fetch
handoff, broader prefetch/full pipeline behavior, peripheral IRQ-line latency
and hardware qualification remain open. No tolerance has changed.

All eleven fresh-backup games complete **39600 guest frames / 21725550 stereo
samples**, with zero missing frames or software audio-buffer failures. Whole
RGB and backup hashes are unchanged in every title; four initialization saves
are written. PCM changes in Lost Age, V-Rally, Emerald and LeafGreen. Reused
mGBA RGB888 exact-frame sets remain unchanged, with no gains/losses and no
whole-stream accuracy pass. The 1272-frame LeafGreen continuation preserves
full RGB, its three raw video states and the original player save. It ends
three cycles later with changed PCM; RGB555 remains 1111/1272 exact frames,
first difference 930. These are boot/intro and limited continuation checks,
not gameplay or sustained/physical playback qualification.

Evidence: [cpu-refill-resume-2026-11-05.json](../benchmarks/cpu-refill-resume-2026-11-05.json).
Reproduction: [SCENARIOS.md](SCENARIOS.md#cpu-branch-refill-probes).

## CPU branch-refill DMA counterexample, 2026-11-05

The next asset-free probe runs same-mode `BX r2` on the corrected data-access
core and the pinned, unmodified NBA CPU. Its 36 observations cover ARM/Thumb,
IWRAM/Game Pak targets, three two-word DMA3 source regions and no-DMA controls
or raw readiness at cycle 1/2. The reference asserts pipeline priming; both
adapters verify branch state and exit successfully. Both inspect completion
of the second DMA destination word at retirement without advancing the clock.

All **12 no-DMA controls match**. All **24 ready cases differ**: NBA completes
DMA before the branch retires; BerryBoy retires with the transfer incomplete.
PC agrees in all 36 rows. For an IWRAM DMA source, the cycle triplets below
mean **no DMA / readiness 1 / readiness 2**:

| Instruction set / target | NBA | BerryBoy |
| --- | --- | --- |
| ARM / IWRAM | 3 / 9 / 9 | 3 / 3 / 3 |
| Thumb / IWRAM | 3 / 9 / 9 | 3 / 3 / 3 |
| ARM / Game Pak | 15 / 21 / 23 | 15 / 15 / 15 |
| Thumb / Game Pak | 9 / 15 / 17 | 9 / 9 / 9 |

`BusTiming.refill` currently advances both accesses atomically. Readiness at
1 must stall before the first refill access; at 2 it can interrupt the two
accesses. For a Game Pak target, the resumed second access must also lose its
sequential status, accounting for the additional two cycles. NBA uses its real
immediate-request activation with optional request age; BerryBoy injects raw
readiness. These measurements do not qualify actual production activation.

The next correction needs resumable refill progress, arbitration before each
access and one-unit DMA yields, with retirement and IRQ boundaries preserved.
Extend the checks to B/BL, state-changing BX, loaded PC, SWI and IRQ refills,
plus long-DMA frame/audio budgets. Retain the exact 48 data and 96 earlier
normalized observations before implementing delayed request activation and
its cancellation/re-enable/deduplication rules. Timing-only refill comparisons
do not establish general opcode latching or full pipeline correctness.

This checkpoint changes diagnostic tools/docs only. Both adapters compile
with warnings treated as errors; production/test sources remain identical to
the preceding verified commit. Maven and console checks again pass 1458 host /
1352 exported tests. The preceding 1352 actual CLDC-class passes, native build
and game captures are reused. No production correction, new game capture or
hardware claim is made here. Evidence: [cpu-refill-diagnosis-2026-11-05.json](../benchmarks/cpu-refill-diagnosis-2026-11-05.json).
Reproduction: [SCENARIOS.md](SCENARIOS.md#cpu-branch-refill-probes).

## Resumable CPU data accesses during DMA, 2026-11-05

ARM/Thumb decoders now prepare a fixed, reusable data-operation sequence rather
than performing memory operations atomically. CPU arbitration runs before each
access, and a suspended instruction retains its cursor, addresses, store values
and final writeback/status/PC actions. This covers single loads/stores, LDM/STM,
PUSH/POP and SWP. Completed accesses run once; SWP keeps its read/write bus lock.
CPU data timing and BIOS/open-bus context come from the original fetch and are
restored only while executing that access, never while servicing DMA. The
guest still has one owner and no per-instruction allocation is introduced.

The new [independent data probe](../tools/nba-dma-data-probe.cc) executes the
unmodified NBA CPU with normal two-word DMA3, two readiness times, three source
regions, two instruction sets and IWRAM/Game Pak data. The 48-row comparison
improves from **12/48 before to 48/48**. NBA uses its real two-cycle request
activation, with an optional one-cycle request age; BerryBoy injects matching
raw readiness. This isolates data arbitration, not production request timing.

When DMA becomes ready at the end of the opcode fetch, LDR reads the overwritten
IWRAM value. When readiness occurs during LDM's first access, that read stays
latched while its second access follows DMA. Extending the probe to ROM data
exposed another defect: retaining the CPU's sequential burst after DMA saved
two cycles. The intermediate implementation matched **42/48**. DMA now
invalidates both opcode and data sequencing, restoring an eight-cycle
nonsequential Game Pak read; all six counterexamples pass. This command is
separate from ordinary fetch invalidation, so uninterrupted ROM bursts keep
their sequential timing.

Thirty-nine new core cases pass; the preceding committed CPU fails **32**, with
seven already-passing cases. Coverage includes the independent load timings,
writeback and banked SPSR/PC restoration, interrupt-acknowledge side effects,
SWP locks, stack operations and a new request during DMA release. A 65536-word
transfer yields at a frame budget, produces/drains PCM and resumes the already
decoded LDR after DMA overwrites its code. The data read occurs only after DMA;
this complements the prior internal-cycle test that preserves data read before
DMA. IRQ and next-instruction handling remain behind retirement.

Validation passes **1458 host / 1352 exported / 1352 actual new ECJ CLDC-class
tests**, plus a fresh JDE/ECJ/RAPC COD build. The earlier readiness-normalized
96-row matrix remains exact. The production request matrix remains byte-for-byte
unchanged at 24/96 matches: activation is still immediate. Branch/IRQ refill
arbitration, scheduled activation/lifecycle, broader prefetch/bus workloads and
hardware qualification remain open. No comparison tolerance has changed.

All eleven fresh-backup scenarios complete **39600 guest frames / 21725550
stereo samples**, with zero missing guest frames or software audio-buffer
failures. RGB changes in Lost Age/Sonic; PCM changes in V-Rally, Sonic, Emerald
and LeafGreen. All backup hashes remain unchanged; four initialization saves
are written. Against reused mGBA RGB888 captures, every exact-frame set remains
unchanged, with no gains/losses; full streams still differ. The separate
1272-frame LeafGreen continuation preserves full RGB, all three raw video
states and the original player save. It ends two cycles later with changed
PCM; RGB555 still matches 1111/1272 frames, first difference 930. These are
headless boot/intro and limited continuation checks, not gameplay or sustained
physical playback passes. Prior CPU-window results describe earlier cores.

Evidence: [dma-data-resume-2026-11-05.json](../benchmarks/dma-data-resume-2026-11-05.json).
Reproduction: [SCENARIOS.md](SCENARIOS.md#cpu-data-arbitration-probes).

## Resumable CPU internal cycles during DMA, 2026-11-05

CPU retirement can now wait across DMA units during internal execution cycles.
The decoded instruction executes once; its remaining internal-cycle budget
overlaps DMA ownership, and retirement resumes when that interval ends. An
unfinished instruction cannot enter IRQ or execute the next instruction. Short
transfers leave any uncovered internal cycles to complete normally. A new DMA
request during release belongs to another interval, rather than extending the
completed instruction's wait. The guest retains one deterministic owner and no
per-instruction allocation is introduced.

`Cpu.step()` still services at most one DMA unit. The 65536-word regression
stops at a frame budget with the instruction pending, drains its 548 PCM frames,
and later retires the original latched load even after DMA overwrites its code
and data. No instruction replay or repeated memory side effects are used.
Between steps, `instructionInFlight()` distinguishes pending timing from
retirement; PC/registers may already reflect executed work. `instructions()`
counts retirements. Host frame/step snapshots are not necessarily snapshots of
a completed instruction. Step-count budgets retain that distinction.

The readiness-normalized CPU matrix now matches **96/96 NBA observations**,
versus **48/96 before**: ARM/Thumb ADD/LDR, three FIFO source regions and one/two
channels. The reference request at cycle 0 activates at 2; BerryBoy injects
readiness at 2 to isolate this arbitration stage. **Actual request timing is
unchanged**: its default 24/96 result is byte-identical to the previous matrix.
Scheduled activation and yielding before fetch/data accesses, including
multi-access instructions and branch refill, remain future qualification work.

Seventeen new core cases pass: 12 normalized loads plus long-DMA/audio budgets,
uncovered multiply cycles, IRQ deferral, final-internal-cycle readiness and a
new interval during release. The previous CPU fails **16**, retaining the one
already-correct final-cycle case. The old test classpath adds only a constant
false `instructionInFlight()` compatibility query, with no emulation change.
Validation passes **1419 host / 1313 exported / 1313 actual new ECJ CLDC-class
tests**. Current production sources also compile with JDE/ECJ and vendor RAPC;
a new COD is emitted. No simulator/device runtime claim follows.

All eleven fresh-backup games complete **39600 guest frames / 21725550 stereo
samples**, with zero missing frames or software audio-buffer failures. Full RGB
changes in Lost Age/Sonic; PCM changes in those two plus V-Rally, Emerald and
LeafGreen. All backup hashes remain unchanged; four initialization files are
written. Against the reused independent mGBA RGB888 captures, every title keeps
the same exact-frame set: no gained/lost exact frames. Whole-stream comparisons
remain unmatched; color-model/filter and timing gaps are still unqualified.

The separate 1272-frame LeafGreen player continuation preserves its original
save, full RGB and all three raw video states. PCM changes, while the reference
RGB555 result remains **1111/1272 exact frames**, first difference **930**.
These are headless boot/intro/limited continuation results, not gameplay or
sustained/physical playback qualification. Prior equal-cycle CPU-window results
describe the old core and need reassessment after this change.
Evidence: [dma-internal-resume-2026-11-05.json](../benchmarks/dma-internal-resume-2026-11-05.json).

## FIFO activation and CPU load ordering, 2026-11-05

The original [NBA CPU probe](../tools/nba-dma-activation-probe.cc) now executes
real ARM/Thumb instructions against its unmodified DMA, bus and scheduler.
Twenty-four asset-free scenarios cover repeated ADD/LDR instructions in IWRAM,
three FIFO source regions and one/two channels, with four retirements each.
ARM pipeline priming and the real BX/Thumb refill occur before observation;
both adapters assert the warmup and instruction progression. NBA additionally
checks that every FIFO source advances 16 bytes before the first LDR retires.

The **96-row matrix exposes two coupled discrepancies**. For one IWRAM FIFO:

| Program | NBA retirement cycles | Production BerryBoy | Delay-only experiment |
| --- | --- | --- | --- |
| ADD r0,#1 | 1, 2, 13, 14 | 11, 12, 13, 14 | 1, 2, 13, 14 |
| LDR r0,[r2] | 12, 15, 18, 21 | 13, 16, 19, 22 | 3, 16, 19, 22 |

NBA permits the two one-cycle ADDs during FIFO request activation. Production
BerryBoy services the FIFO immediately. Merely delaying readiness by two cycles
matches **48/48 ADD observations** but still matches **0/48 LDR observations**:
it retires the first load before the FIFO transfer is serviced. NBA arbitrates
bus accesses and internal CPU cycles, including overlap of internal work with
DMA. Production matches only **24/96 individual rows**, and none of the 24
complete four-retirement scenarios. r0 and PC progression agree; these are
timing/ordering observations, not full-state or hardware certification.

Twelve new portable ordering cases exercise both instruction sets, all three
sources and both channel counts. At the first LDR retirement they inspect the
complete 16-byte FIFO stream per channel. They pass in production and **all
twelve fail** against the ignored delay-only experiment, preserving a useful
ordering property without freezing production's unqualified activation latency.
The experiment is recorded with its exact patch/hash but is not shipped.

**No production activation fix is delivered here.** The next implementation
must combine scheduled activation with CPU fetch/data/internal-cycle arbitration
and resumable instruction handling. Preserve word-level DMA yields and frame/
audio deadlines; draining a long DMA inside an atomic CPU instruction would
break the existing capture-budget guarantees. Qualify disable/re-enable and
repeated requests alongside the CPU cases. Further instruction forms, actual
timer workloads and hardware remain separate gates.

Validation: **1402 host tests, 1296 exported-core tests and 1296 retained actual
ECJ CLDC-class tests** pass without failures/skips. Both adapters compile with
warnings treated as errors. The deliberately rejected experiment's twelve
failures are separate from those passing suites. No new SDK/COD build, game
capture, tolerance change or activation gate closure is claimed.
Evidence: [dma-activation-2026-11-05.json](../benchmarks/dma-activation-2026-11-05.json).
Reproduction: [SCENARIOS.md](SCENARIOS.md#fifo-activation-and-cpu-probes).

## Lost Age first retirement divergence and DMA release, 2026-11-05

The earlier frame-735 window started after the executions already differed.
New retirement digests cover the execution from reset, without diagnostic
clock events. **12587 complete blocks of 4096 retirements** have identical
SHA-256 records, including cycles, PC/CPSR, r0-r14, IF and HALT. Expanding the
first differing block adds 2527 identical states: the first differing completed
instruction is **51558880**, after **51558879 matching retirement states**.

That instruction ends at **206402097 before / 206402099 after**. Only time
differs; all 4096 detailed records retain identical register/flag/IRQ/HALT values
at the same retirement index. This localizes the first timing change, not the
first difference in every memory or peripheral state, nor later loop divergence.

A passive, read-only DMA adapter identifies the boundary. Both executions last
retire instruction 51558879 at cycle 206401446, then service DMA3. At timer
callback **206402040**, the old model still has DMA3 pending with one unit left
and owns the bus. The callback makes DMA1/2 FIFO transfers ready; they join
continuous ownership. In the corrected model, DMA3's last write completed at
206402039 and the callback occurs during release, with ownership cleared.
The FIFO pair starts another interval. Its two-cycle overhead moves the final
DMA step from 206402096 to 206402098, then CPU execution resumes. The observer
adds no deadlines; all 140 old / 139 corrected completed CPU-step observations
match the controls without the observer.

An original, asset-free [NBA release probe](../tools/nba-dma-release-probe.cc)
confirms the boundary cost independently: one EWRAM halfword to IWRAM takes
**6 ownership cycles**, then the IWRAM FIFO pair takes **18**, total **24**.
The new portable regression gets 24 from the corrected model; the old model
gets 22 and fails. Both readiness timestamps are normalized to cycle 8. NBA
requests at 6 activate after two cycles; BerryBoy readiness is injected at 8.
This does **not** qualify BerryBoy request activation or CPU continuation during
activation, and is not a whole-game hardware oracle.

No production timing is changed in this delivery: the existing correction is
supported by this minimal independent case. Later register/loop differences,
the other three titles, request activation and the saved-player LeafGreen
frame-930 gate remain open. Do not normalize later retirement indices to make
them match. Validation: **1390 host, 1284 exported-core and 1284 retained actual
ECJ CLDC-class tests** pass. Five new host-tool cases and one new core case;
no new native SDK/COD build or BBOS runtime claim.
Evidence: [lost-age-dma-release-2026-11-05.json](../benchmarks/lost-age-dma-release-2026-11-05.json).
Reproduction: [SCENARIOS.md](SCENARIOS.md#retirement-prefix-and-release-boundary-probes).

## Equal-cycle CPU checkpoint diagnosis, 2026-11-05

All 134 changed checkpoint frames now have bounded CPU observations from both
DMA models: **53734 exact-cycle samples per variant**, in 401-cycle windows.
Only DMA and its inner classes are replaced on the older classpath. This is a
before/after diagnosis, not another independent hardware reference. All games
start with fresh backups and the verified baseline inputs; no save is loaded
or written by the probe.

**92 windows have identical PC, CPSR, r0-r14, HALT and IF at every sampled cycle.**
The remaining **42 windows** differ in four titles:

| Game | Matching CPU windows | Differing CPU windows |
| --- | ---: | ---: |
| Golden Sun - The Lost Age | 35 | 13 |
| V-Rally 3 | 0 | 11 |
| Zone of the Enders | 3 | 5 |
| Sonic Advance 3 | 2 | 13 |

All selected windows in the other seven titles match. This does not qualify
unobserved intervals, memory/peripheral equality or full-game correctness.

The first Lost Age checkpoint, frame 322, is an observation-endpoint shift:
all **401** CPU/IF samples match, and no instruction retires near the boundary.
The old seven-cycle DMA units end at `90448505/90448512/90448519`; corrected
units end at `90448504/90448511/90448518`. The frame target is `90448512`, so
whole-unit execution stops six cycles later with the corrected model. Both
record 11733062 retired instructions at their respective endpoints.

The other differences cannot all be attributed to endpoint selection. In each
of the four titles, CPU registers differ even at some shared completed-step
cycles. The Lost Age window around frame 735 already differs at its start;
at cycle `206458365`, completed-step PC and r4 differ. This is the first observed
shared-step difference in these selected windows, not the first global cause.
Trace backwards into the preceding DMA/request/IRQ and CPU execution before
choosing another correction. A differing old execution is not proof that the
corrected model is wrong. Blind retired-instruction-index matching is also
unsafe when busy-loop iteration counts differ; unmatched indices are retained.

Sampling adds scheduler deadlines and subdivides HALT waits. For **all four
differing titles and both DMA variants**, separately compiled controls omit
sample-event installation: their entire observed retirement-row lists match
the instrumented lists exactly. Shared-step CPU differences persist in those
controls. HALT step counts themselves are not invariant. Seven asset-free host
tests cover exact/disjoint windows, invalid bounds, active CPU control equality,
raw IF observation without clock advancement and the HALT limitation.

Validation: **1384 host tests and 1283 exported-core tests**, no failures or
skips. Production core is unchanged; the previous native CLDC/RAPC evidence
remains current and was not rerun for these host tools. R2, independent game
accuracy, the saved-player LeafGreen frame-930 discrepancy and hardware gates
remain open. Evidence and all window coordinates:
[cpu-checkpoint-diagnosis-2026-11-05.json](../benchmarks/cpu-checkpoint-diagnosis-2026-11-05.json).
Reproduction: [SCENARIOS.md](SCENARIOS.md#cpu-checkpoint-comparison-at-equal-cycles).

## Eleven-game regression after DMA phase correction

The clean production revision `9f45db89759c400698f23ab13e48e6e9e8fab923` was
tested with all eleven uploaded titles, fresh backups, the original BIOS and
the now [committed Start/A recording](../benchmarks/inputs/eleven-game-start-a.tsv).
Each completes **3600 frames**: **39600 guest frames / 21725550 stereo samples**
in total, without missing guest frames or software audio-buffer failures.
Four games write initialization backups. Full RGB, PCM, backup hashes, final
guest cycles and sample counts remain identical to the preceding regression.

The frame traces are **not identical**: **134 rows** differ in cycle/PC/CPSR
checkpoint fields. Per-game counts, first differences, cycle-delta bounds and
both trace hashes are recorded. `ScenarioRunner` samples after a complete CPU
instruction or DMA unit, which can overshoot the nominal frame boundary; the
phase correction moves those unit boundaries. This does not prove equivalent
CPU state at equal guest cycles or explain every differing PC/CPSR value.
Compare equal-cycle state before classifying these trace changes as harmless;
do not normalize them away or claim exact trace stability.

These are boot/intro/attract runs, not full gameplay or independent correctness
passes. Earlier reference differences and the LeafGreen frame-930 gate remain
open. No additional production change or tolerance was introduced in this
regression delivery. The **1377 host / 1283 exported / 1283 actual CLDC** checks
and native SDK build from the phase correction remain current.
The evidence records the documentation-only working-tree changes during the
batch and the cached batch-start dirty flag in raw reports.
Evidence: [dma-phase-game-regression-2026-10-23.json](../benchmarks/dma-phase-game-regression-2026-10-23.json).
Reproduction: [SCENARIOS.md](SCENARIOS.md#eleven-game-bootintro-regression-input).

## DMA read/write phase correction

The original [NBA phase probe](../tools/nba-dma-phase-probe.cc) and
[BerryBoy adapter](../tools/DmaPhaseProbe.java) expose a defect hidden by the
previous matching ownership totals. BerryBoy charged both overhead cycles
before transferring data; the independent core brackets transfers with one
acquisition cycle and one release cycle. BerryBoy therefore read and wrote
one cycle late while returning the same total ownership time.

The correction splits those two cycles, preserving continuous ownership across
channels and word-level yields. Priorities are checked after acquisition
callbacks. A request arriving during release starts another ownership interval.
For one IWRAM halfword, relative to ownership start, write completion moves from
cycle **4 to 3**, while ownership still ends at **4**. A callback at cycle 4 now
sees the transferred value. Source changes during the following destination
access no longer incorrectly replace the already latched value.

All **144 scenarios / 28800 observations** now match the unmodified, pinned NBA
core byte for byte, versus **0 matching scenarios / 360 differing observations**
before correction. The matrix covers three source regions, two destination
regions, both widths, one/two channels, three lengths and service at cycles 2/3.
It samples first/last destination values, enable bits and visible IF after queued
callbacks and before the current bus operation returns. **82 new core tests fail
before and pass after**; the previous FIFO ownership CSV remains byte-identical.

This qualifies the measured, already activated RAM-transfer phases against an
independent model. It excludes request activation/CPU continuation, equal-deadline
IRQ priority and subcycle IF publication. FIFO/timer workloads and actual
hardware remain separate gates. No global IRQ delay or request delay is changed.

A fresh **1272-frame LeafGreen continuation** retains the full RGB/PCM hashes,
all three raw video snapshots, guest cycles, sample count and original save.
It still matches **1111/1272 RGB frames**, first difference **930**; full reference
RGB and PCM remain unmatched. There are no missing guest frames or software
audio-buffer failures. The initialization discrepancy remains open.

Validation: **1377 host tests, 1283 exported-core tests and 1283 actual ECJ CLDC
core tests** pass without failures or skips. Native SDK compilation and RAPC
conversion emit a COD; simulator/device execution remains unverified.
Reproduction: [SCENARIOS.md](SCENARIOS.md#dma-readwrite-phase-probe).
Evidence: [dma-transfer-phases-2026-10-23.json](../benchmarks/dma-transfer-phases-2026-10-23.json).

## Second LeafGreen player save and cold cross-load

The [second-save input](../benchmarks/inputs/leafgreen-second-save.tsv) extends
the existing Continue route: move right by one tile, open SAVE, confirm the
overwrite and return to the room. Both BerryBoy and unmodified mGBA 0.10.5
RGB555 complete 2750 frames from isolated copies of the original player save.
The original remains byte-identical.

Both create generation **2**, preserving the previous generation. The
[read-only inspector](../tools/inspect_leafgreen_save.py) validates all **28
section checksums**, all 14 section identities in each generation, and reports
the saved position changing from **(7,6) to (8,6)** on map group 4/map 1.
Both decoded money values remain 3000. The initial image contains 14 valid
sections and generation 1. The inspector is specific to this LeafGreen layout,
not a general save-recovery implementation.

The new files differ in **667 bytes**. Their encryption keys differ, and play
time differs by one game-counter frame: `0:00:27:45` versus `0:00:27:46`.
These observations do not independently explain every differing byte, so the
strict whole-file comparison remains **unmatched**. The exact 2750-frame
comparison also remains unmatched: **2356 exact RGB frames**, first difference
930, and different PCM. No byte/pixel tolerance or frame shift is introduced.

Each generated save is then loaded into fresh instances of **both** emulators:
four cold cross-loads in total, each 1272 frames. All four restored-room
captures match the post-save room in **all 38400 pixels** and differ from the
original saved position. Every reload retains its input save hash. This
qualifies latest-position restoration and save interoperability for this narrow
route, while full binary/timing equality and broader gameplay remain open.

Validation: **1295 host tests pass**; no production or exported-core changes.
The existing **1201 exported/actual CLDC** checks remain the latest core
validation. Route, save identities, inspections and strict comparisons:
[leafgreen-second-save-2026-10-23.json](../benchmarks/leafgreen-second-save-2026-10-23.json).
Reproduction: [SCENARIOS.md](SCENARIOS.md#leafgreen-second-save-route).

## Three-way brightness model comparison

The earlier attribution of the Continue-menu difference solely to reference
rounding was too strong. A second independent renderer, unmodified
NanoBoyAdvance, produces the **same menu color as mGBA RGB555**, differing from
BerryBoy. The historical pairwise arithmetic explanations below remain
observations of those implementations, not proof of BerryBoy's hardware accuracy.

Three original probes now capture **2244 settled uniform backdrop frames per
implementation**: 32 gray levels plus the menu color, both brightness effects,
coefficients 0–16 and palette bit 15 clear/set. Each case discards one frame and
inspects the second. All full frames are uniform, and BerryBoy's software line
matches its timed raster output. The 264 coefficient-endpoint controls agree
across all three models. No external ROM or BIOS is used. NBA uses its original
PPU/scheduler composition; mGBA executes an original IWRAM branch loop.

| Compared implementations | Matching cases | Differing cases |
| --- | --- | --- |
| BerryBoy / NBA | 1062 | 1182 |
| BerryBoy / mGBA RGB555 | 1382 | 862 |
| NBA / mGBA RGB555 | 756 | 1488 |

For backdrop `0x7e51`, darkening coefficient 7 and palette bit 15 clear:
BerryBoy emits `0x525a94` (82,90,148); both references emit `0x52528c`
(82,82,140). Agreement on this one case does not reconcile the broader matrix.
NBA's model uses nearest-integer brightness arithmetic and a sixth internal
green bit; its v1.7 changelog explicitly records the green bit and rounding
change. Its output depends on palette bit 15 in **247/1122 paired cases**, while
BerryBoy and mGBA are invariant. This is a concrete additional model difference
that a five-bit-only explanation does not resolve.

**R1 remains open.** Obtain independently justified expected outputs for rounding
and palette-bit behavior before choosing a renderer correction. The current
arithmetic is preserved, and its tests are explicitly regression contracts,
not hardware certification. No tolerance, frame offset or gameplay pass changed.
The previous independent DMA probe's complete CSV remains byte-identical after
extracting the shared headless device fixture for these two concrete uses.

Validation: **1295 host tests pass**; both C++ probes, the RGB555 C adapter and
Java brightness adapter compile with warnings treated as errors. Production
sources and the last game replay are unchanged from the shared-DMA correction.
Exported/actual CLDC validation remains that correction's **1201 passing tests**.
Reproduction: [SCENARIOS.md](SCENARIOS.md#three-way-brightness-probes).
Evidence: [brightness-models-2026-10-23.json](../benchmarks/brightness-models-2026-10-23.json).

## Shared DMA ownership correction

Pending DMA channels now share a continuous bus-ownership interval. The two
overhead cycles are charged once, including priority preemption between words,
and charged again after ownership ends. Each channel retains its own first
access/nonsequential state. Long transfers still yield after every word. This
corrects the independently demonstrated total; the previous startup placement
is retained, so request activation and individual write/IRQ phases remain open.

Eight new core regressions cover both channel counts and all three source
regions, repeated groups separated by an idle gap, higher-priority preemption
and CPU resumption between requests. **Four fail before correction; all eight
pass afterwards.** The original 192 FIFO count-invariance cases still pass.
The standalone BerryBoy probe now matches **96/96 NBA ownership observations**,
including all 48 paired-channel cases that previously cost two extra cycles.

The normalized BIOS walk still first differs from mGBA at row 796679, with
identical registers/PC, but BerryBoy now ends at **1882548**, versus reference
**1882546** (previously 1882550). The remaining two cycles correspond to the
reference's ignored-count-dependent overdue-event behavior identified below;
they are not grounds to remove further ownership overhead.

A fresh 1272-frame LeafGreen replay preserves the entire RGB hash, all three
video snapshots and the original player save. It remains **1111/1272 exact RGB
frames**, first mismatch **930**. PCM changes with the corrected clock, remains
unmatched and has 697851 stereo samples; no guest frames or software audio
buffers are lost. The initialization offset is still open. Host work timings
are not a native-playback qualification.

Validation: **1295 host tests, 1201 exported-core tests and 1201 actual ECJ CLDC
core tests**, no failures or skips; native SDK/RAPC compilation passes.
Evidence: [dma-shared-ownership-2026-10-23.json](../benchmarks/dma-shared-ownership-2026-10-23.json).

## Independent DMA ownership reference

An original [NanoBoyAdvance probe](../tools/nba-dma-probe.cc) now measures
FIFO bus ownership against a second, independent implementation, pinned to
`55b5cf0ae3d929582ac5bfd486558173502b8354`. The headless CMake composition
builds its unmodified core and bundled fmt/atom dependencies, without its
desktop frontend or changes to CPU, DMA, bus or scheduler sources.

Across **96 original cases**, its FIFO count is invariant in all **12 parameter
groups**. The cases use three source regions, one/two channels, eight counts
and service at cycles 2/3. Its original two-cycle request activation is allowed
to complete before measuring ownership; that activation delay is not included
in the table. No BIOS or commercial assets are loaded. The probe confirms
16-byte source progress, repeat count and release of all runnable DMA channels.

| Source | NBA one channel | BerryBoy one channel | NBA two channels | BerryBoy two channels |
| --- | --- | --- | --- | --- |
| IWRAM | 10 | 10 | 18 | 20 |
| EWRAM | 30 | 30 | 58 | 60 |
| Game Pak ROM | 32 | 32 | 62 | 64 |

These are guest ownership cycles, not FPS. All 48 single-channel observations
match BerryBoy. All 48 paired observations expose two extra BerryBoy cycles.
mGBA with programmed count=4 agrees with NBA on all 12 control cases, while
its other count values retain the earlier reference defect. NBA brackets the
whole continuous DMA run with one cycle before and one after, rather than
charging overhead again when the next pending channel takes over.

The correction above shares ownership overhead across pending channels,
preserving channel priority, per-channel first/nonsequential access and yielding
between words. Request activation, individual write/IRQ phases and real hardware
qualification remain separate. This standalone device composition does not
qualify an entire CPU/timer workload or the LeafGreen frame-930 offset.
Reproduction: [SCENARIOS.md](SCENARIOS.md#independent-dma-ownership-probe).
Evidence: [nba-dma-ownership-2026-10-23.json](../benchmarks/nba-dma-ownership-2026-10-23.json).

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

## Natural LCD, DMA and IRQ phase observations

[Eight original cases per model](../benchmarks/lcd-natural-phases-2026-11-06.json)
observe real scanline starts at lines 0, 159, 160 and 227, with forced blank off/on.
CPU execution is excluded. NBA sets HBlank at cycle 1007, publishes HBlank IF at
1009 and exposes its CPU IRQ input at 1011. mGBA sets HBlank and IF at 1008; its
pending IRQ event clears at 1021 with CPU acceptance masked. These are different
observation contracts, not interchangeable IRQ-entry measurements. DMA write
visibility also differs between their event/bus models. A constant shift cannot
qualify all of those phases.

The matching terminal register/data rows at cycle 1248 are retained in
`src/test/resources/berryboy/video/lcd-natural-terminal.csv`. Eight portable
regressions use only that agreement; disputed fine edges remain diagnostic.
Both references compare observed and unobserved terminal fields; the BerryBoy
adapter additionally compares complete machine state. No commercial assets are used.

Build the new NBA target with the existing pinned source configuration:

```bash
cmake -S tools/nba-reference -B target/lcd-reference \
  -DNBA_SOURCE_DIR=/path/to/clean/pinned/NanoBoyAdvance -DCMAKE_BUILD_TYPE=Release
cmake --build target/lcd-reference --target nba-lcd-phase-probe --parallel 4
target/lcd-reference/nba-lcd-phase-probe > target/nba-lcd-phases.csv
```

Compile `tools/mgba-lcd-phase-probe.c` with the RGB888 standalone command above,
substituting that source/binary name. Both C/C++ adapters print CSV to stdout and
fail on broken sampling/completion/control invariants. For the current model:

```bash
mvn compile
mkdir -p target/lcd-tools
javac --release 21 -cp target/classes -d target/lcd-tools tools/LcdPhaseProbe.java
java -cp target/classes:target/lcd-tools berryboy.desktop.LcdPhaseProbe > target/berryboy-lcd-phases.csv
```

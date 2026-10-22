# Core architecture and scope

## Responsibilities

| Class | Responsibility |
| --- | --- |
| `Cpu` | Fetches instructions, chooses ARM/Thumb and adds failure diagnostics |
| `CpuState` | Stores registers and flags, switches banks and handles CPSR/SPSR |
| `ArmInterpreter` | Decodes ARM encodings and dispatches instruction families |
| `ThumbInterpreter` | Decodes and executes Thumb instruction families |
| `ArithmeticLogicUnit` | Calculates results and NZCV flags |
| `BarrelShifter` | Calculates shifts, rotations and carry-out |
| `InstructionInterpreter` | Shares unaligned-read, BX and SWI rules |
| `Keypad` | Stores the ten pressed-button bits and exposes active-low input |
| `AudioUnit`, `PulseChannel` | Decode sound registers, synthesize channel 2 and mix stereo samples |

The decoders share one `CpuState` and one `Memory` bus. Their ALUs and shifters are
created during construction, with no per-instruction allocation on successful
execution. `Machine` and the public `Cpu` API provide the main integration points.
Operand names describe their roles, such as `destination`, `baseRegister` and
`registerList`; hardware abbreviations such as CPSR, SWI and ALU remain intact.

## CPU

The ARMv4T interpreter advances by instruction, without cycle timing. Internally,
`r15` holds the next instruction address; operand reads add the pipeline offset.
ARM reads use PC+8, or PC+12 for register shifts and STR. Thumb reads use PC+4.

Supported instruction families:

- ARM: all 16 ALU operations, NZCV flags, conditions, the barrel shifter, B/BL/BX,
  byte/word LDR/STR, halfword and signed transfers, LDM/STM, SWP, MUL/MLA, long
  multiplication, MRS/MSR, register banks and basic SWI entry.
- Thumb: ARMv4T formats 1–19, including ALU operations, high registers, loads and
  stores, SP/PC-relative access, PUSH/POP, multiple transfers, branches and two-part BL.
- SWI requires a supplied BIOS through `Memory.setBios`; otherwise it reports an
  error. `MOVS pc,lr` restores SPSR on return. BIOS HLE is not implemented.

Instruction-family coverage does not imply full conformance. Empty register lists
and selected unpredictable operand combinations stop execution. MUL preserves C,
which ARMv4 defines as unpredictable. IRQ scheduling, aborts, undefined exceptions,
cycles, wait states and self-modifying pipeline behavior are not implemented.
ARMv5 and Thumb-2 are outside the target architecture.

## Memory bus

The bus provides 256 KiB EWRAM, 32 KiB IWRAM, 1 KiB palette RAM, 96 KiB VRAM,
1 KiB OAM and up to 32 MiB ROM. It implements RAM mirroring, the VRAM mirror at
`0x18000..0x1ffff` and three ROM windows. Unaligned LDR rotates the word;
LDRH/LDRSH follow the ARM7 behavior described in GBATEK. STR/STRH and block
transfers enforce alignment.

Byte writes to palette RAM and BG VRAM duplicate the byte across the halfword.
OAM and OBJ VRAM ignore byte stores. `KEYINPUT` is active-low and read-only.
DISPCNT, BG2CNT and the BG2 affine registers drive the renderer.

Unimplemented regions and I/O stop execution. Save hardware, EEPROM, flash, RTC,
BIOS protection, prefetch/open-bus latches and cartridge bus details are absent.
ROM reads beyond the cartridge size return an address-derived pattern.

## Rendering

`BitmapRenderer` produces a BG2 snapshot in bitmap modes 3, 4 and 5. It handles
frame selection, palette lookup, backdrop color, forced blank and basic affine
transforms through BG2PA/B/C/D/X/Y. One RGB888 buffer is reused between renders.

CPU/scanline synchronization, VBlank, tiles, OBJ, blending, windows, mosaic and
bitmap wrapping are not implemented. Enabled unsupported effects report errors.
The demo writes the transform registers explicitly; the renderer does not supply
hidden defaults.

## Audio and input

`Memory` owns the keypad and audio unit. `Machine.audio` exposes the same audio
unit for platform adapters. Guest loads observe keypad updates through read-only
`KEYINPUT`; keypad IRQs are not implemented.

Channel 2 produces interleaved signed PCM16 at 32768 Hz, with duty selection,
length, envelope and stereo routing. Synthesis uses integers and caller-owned
buffers, without desktop APIs. Output sample counts advance audio time. CPU
instructions do not yet advance an emulated clock, so audio register writes are
not synchronized to CPU cycles. Channel 1, wave/noise channels, Direct Sound,
timer/DMA refill, PWM/BIAS effects and hardware sequencer edge cases remain open.

The next step is a shared timing scheduler before extending graphics and audio.
See [BENCHMARKS.md](BENCHMARKS.md) for the implementation order and measurement gate.

## Java and performance

The development project targets Java 21. The instruction execution path uses
primitives and arrays. Local `var` changes source syntax only; the exporter
resolves concrete types before generating the BlackBerry sources.

There is no ARM guest JIT or dynamic recompiler. ARM/Thumb instructions are
interpreted on the host JVM or interpreter. Desktop demo timing does not predict
frame rate or performance on a Bold device.

## Desktop layer

`LaunchCommand` parses arguments into `Demo`, `Header`, `Run` or `AudioDemo` records. A sealed
interface closes the command hierarchy, allowing `Main` to handle every variant
with Java 21 record patterns in a switch, without preview features.

The parser performs no file I/O or emulation. `Main` separates program execution,
PNG export, result presentation and Swing startup. `ExecutionResult` describes
the completed diagnostic run and its machine. The whole desktop package is
excluded from BlackBerry export.

`DesktopSession` owns that machine on one worker thread. `KeyboardInput` receives
Swing actions and publishes an atomic button mask; focus loss clears held keys.
The worker applies input, runs an instruction batch, produces PCM and passes a
frame snapshot to the event dispatch thread. Presentation applies backpressure
instead of accumulating frames in an unbounded queue. Swing can still coalesce
repaints; this is not a verified no-drop video pipeline or a GBA frame scheduler.

`PcmAudio` owns Java Sound playback and WAV encoding. A missing audio device is
reported in the window and leaves the diagnostic session running silently.
`AudioDemo` configures the emulated registers to produce a reproducible tone.
`DesktopPanel` is shared by the real window and the headless documentation capture.

## Tests and diagnostics

JUnit Jupiter runs the tests; AssertJ supplies every host assertion. Both are
`test`-scope dependencies, excluded from the application JAR and generated core.
Surefire discovers `*Test` classes, and parameterized tests report each operand
and flag combination separately. `assertSoftly` groups related state checks.

`LegacyCoreTest` loads the generated core through a `URLClassLoader` with a
platform parent, preventing host core classes from replacing the generated
ones. The console script also repeats all tests tagged `core`, with only generated
production classes on its classpath. Host build and desktop tests are excluded
from that discovery pass, so the host core cannot conceal a missing exported class.

`RuntimeChecks` is portable application diagnostics for MIDP, independent of
JUnit and AssertJ. The old BlackBerry runtime cannot execute Jupiter.
`DemoRomTest` also runs those diagnostics on the host JVM.

## Source reading order

1. `Machine`: assembles the cartridge, memory, CPU and renderer.
2. `DemoRom`: contains actual guest instructions with assembly comments.
3. `Cpu.step`: selects the decoder and supplies failure diagnostics.
4. `ArmInterpreter.execute` and `ThumbInterpreter.execute`: select instruction families.
5. `ArithmeticLogicUnit` and `BarrelShifter`: calculate results and flags.
6. `LegacyExport`: coordinates AST reading, type resolution and source output.
   `LegacySyntaxScanner` collects edits before type analysis; `LegacyApiScanner`
   checks resolved methods after analysis. Records describe sources and edits.
7. `BlackBerryMidlet`: runs the shared classes in MIDP.

## References

- [ARM7TDMI Technical Reference Manual](https://documentation-service.arm.com/static/5f4786a179ff4c392c0ff819?token=)
- [GBATEK — preserved GBA documentation](https://rust-console.github.io/gbatek-gbaonly/)
- [Tonc — bitmap modes](https://gbadev.net/tonc/bitmaps.html)
- [Maven Compiler — release](https://maven.apache.org/plugins/maven-compiler-plugin/examples/set-compiler-release.html)

The code was written for this experiment and contains no third-party BIOS or commercial ROM.

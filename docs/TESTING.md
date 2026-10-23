# Testing with JUnit 5 and AssertJ

Requirements: Linux, JDK 21 and Maven 3.9.x. Tests use small ARM/Thumb programs
and the project's original `DemoRom`; no external BIOS or ROM is required.

## Standard workflow

```bash
mvn test
mvn verify
```

`mvn test` compiles the project and runs Jupiter through Surefire. `mvn verify`
also builds the application JAR. No custom test runner or forced test skipping
is involved. Text and XML reports are written to `target/surefire-reports`.

```bash
mvn -Dtest=ArmShiftTest test
mvn -Dtest=CpuStatusTest#preservesSystemAndFiqRegisterBanks test
mvn -Dgroups=core test
mvn -Dgroups=build test
mvn -Dgroups=desktop test
```

`./test.sh` forwards arguments to `mvn verify`, for example `./test.sh -Dgroups=core`.

## Scenario coverage

| Class | Coverage |
| --- | --- |
| `ArmArithmeticTest` | 512 operand sets with results/NZCV, immediate decoding and logic |
| `ArmConditionTest` | 16 conditions × 16 NZCV combinations |
| `ArmShiftTest` | 17 register-shift edge cases, RRX and PC reads |
| `ArmTransferTest` | Unaligned reads, writeback, stack, SWP and base-register overlap |
| `ArmMultiplyTest` | MUL/MLA and signed/unsigned long multiplication |
| `CpuStatusTest` | CPSR, FIQ/System banks, SWI and return to Thumb |
| `CpuFaultTest` | Missing BIOS/SPSR, unsupported BLX and PC/opcode diagnostics |
| `CpuExecutionTest` | Mixed ARM/Thumb program and branching after underflow |
| `ThumbInstructionTest` | Arithmetic, stack, BL, high registers and transfers |
| `MemoryTest` | Endianness, mirroring, byte stores and KEYINPUT |
| `KeypadTest` | All ten active-low buttons, guest polling, chords and releases |
| `AudioUnitTest` | Channel 2 frequency, duty, envelope, length, stereo and register behavior |
| `CartridgeTest` | Header fields and checksum |
| `BitmapRendererTest` | Frame selection, palette, mode 5 boundaries and forced blank |
| `DemoRomTest` | Complete demo and portable runtime checks |
| `build/LegacyExportTest` | Local `var`, returned switches, rejected APIs and host-class isolation |
| `build/LegacyExportOutputTest` | Stale files, validation failures, ownership, overlaps and symlinks |
| `build/LegacyCoreTest` | Whole-core export, compilation and isolated execution |
| `desktop/LaunchCommandTest` | Typed CLI commands and invalid argument rejection |
| `desktop/KeyboardInputTest` | Actual Swing key actions, simultaneous keys, repeat and release |
| `desktop/PcmAudioTest` | WAV format, sample bytes and deterministic tone, without a sound device |

`EmulatorFixtures` only prepares ROMs and hardware state. `CompilerFixture`
invokes the real javac compiler for generated sources. Each test gets fresh
machine state; exporter tests isolate their files with `@TempDir`.

Arithmetic sampling uses the fixed seed `0xbba11`. CMP/CMN cases also verify
that the destination register remains unchanged. Counts refer to JUnit test
invocations, including parameterized cases, rather than assertion counts.

## Assertion conventions

Keep JUnit 5 annotations, lifecycle methods, tags and parameter providers. Use
AssertJ for assertions, with the actual value first and descriptions where they
explain a register or flag. Use native string, array and exception assertions
instead of reducing a failure to a boolean.

```java
assertThat(cpu.register(0)).as("result register").isEqualTo(42);
assertThat(pixels).hasSize(38400);
assertThatThrownBy(cpu::step)
        .isInstanceOf(EmulationException.class)
        .hasMessageContaining("SWI needs a supplied BIOS");

assertSoftly(softly -> {
    softly.assertThat(cpu.zero()).as("Z flag").isTrue();
    softly.assertThat(cpu.carry()).as("C flag").isFalse();
});
```

`assertSoftly` collects related failures and checks them when the consumer exits.
AssertJ's soft assertions use Byte Buddy; Maven resolves that transitive
dependency. JUnit and AssertJ are not runtime dependencies of the emulator.

## Repeating the suite on the exported core

```bash
tools/test-with-console.sh
```

The script uses Maven to compile the Java 21 project and build the complete test
classpath, including JUnit Platform Console, AssertJ and Byte Buddy. It accepts
Maven options, for example `tools/test-with-console.sh -o` when all dependencies
and plugins are already cached. No manually downloaded standalone JAR is needed.

It runs the full host suite, exports the core, compiles generated sources with
`--release 8`, then repeats tests tagged `core` with the generated core as the
only production classes on the classpath. `target/classes` is absent; build and
desktop test packages are excluded from discovery. Reports are written to `target/junit-console/reports/host` and
`target/junit-console/reports/legacy`.

This checks semantics on the host JVM. It does not replace compilation against
the CLDC SDK, RAPC conversion or execution in the actual BlackBerry runtime.

Both javac fixtures and the exporter clear the implicit application classpath
and source path. Tests verify that a missing source cannot resolve to a compiled
host class with the same package name. Output regressions also verify that an
invalid source leaves the previous generated sources intact, handwritten files
are protected and stale generated sources disappear after a successful export.

## Audio, input and documentation captures

Host tests exercise Swing actions on the event dispatch thread and inspect PCM
and WAV samples directly. They require neither a graphical display nor a sound
device. They do not establish successful physical playback or native keyboard
delivery. See [AUDIO-INPUT.md](AUDIO-INPUT.md) for interactive checks and capture
commands. The original demo ROM and framebuffer remain regression references.

The [five-game benchmark plan](BENCHMARKS.md) is a separate future acceptance
gate. Unit test counts and instruction throughput must not be reported as game
compatibility, sustained FPS or absence of audio underruns.

## BlackBerry Simulator

```bash
mvn -Pbb-sim verify
```

The profile runs host tests, exports the core and invokes the local SDK through
Wine. The MIDlet's `RuntimeChecks` are separate portable device diagnostics.
Neither JUnit nor AssertJ is packaged into the BlackBerry application.
See [BLACKBERRY.md](BLACKBERRY.md) for setup.

## Tool documentation

- [JUnit 5.13.4 User Guide](https://docs.junit.org/5.13.4/user-guide/index.html)
- [AssertJ Core documentation](https://assertj.github.io/doc/)
- [Maven Surefire: JUnit Platform](https://maven.apache.org/surefire/maven-surefire-plugin/examples/junit-platform.html)

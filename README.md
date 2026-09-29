# BerryBoy Advance — 0.5

An experimental GBA emulator for classic BlackBerry devices, targeting the Bold
9900 and BBOS 7.1. Development runs on **Linux, Java 21 and Maven**. The core
executes ARM/Thumb instructions and renders the project's original demo program.
Commercial games are not supported yet.

## Changes in 0.5

All host test assertions now use **AssertJ Core 3.27.7**. JUnit Jupiter 5.13.4
still discovers and runs the tests through Maven Surefire. Fluent assertions
cover values, exceptions and diagnostic messages; `assertSoftly` reports related
register and flag failures together. AssertJ and JUnit remain test dependencies.

All Markdown documentation is in English. `AGENTS.md` records the development
conventions and the requested commit-and-push workflow for future updates.

The Java 21 CLI uses a sealed command interface, records and record patterns.
The core separates CPU state, ARM/Thumb decoders, arithmetic and shifting.
Open **BerryBoy-Source.html** to browse the actual sources on a phone: it includes
class shortcuts, method navigation, search, text sizing and offline access.

## Getting started on Linux

Install JDK 21 and Maven 3.9.x. Maven must run on JDK 21; check `mvn -v`.

```bash
mvn test                       # JUnit 5 tests with AssertJ assertions
mvn verify                     # Tests and application JAR
mvn compile exec:java          # Demo with a desktop window
```

Run without a window:

```bash
mvn compile exec:java -Dexec.args="demo --headless"
```

The outputs are `target/demo.png` and `target/demo.gba`. The demo executes
200,000 instructions: ARM configures the bitmap display, BX enters Thumb, and a
loop writes 38,400 pixels through the memory bus. Execution ends in a loop at
`0x080000FA`. The original demo ROM has a development header without the Nintendo
logo. It tests direct cartridge entry, rather than booting through the original BIOS.

```bash
mvn compile exec:java -Dexec.args="header /path/to/file.gba"
mvn compile exec:java -Dexec.args="run /path/to/homebrew.gba 200000 --headless"
```

`run` is a diagnostic tool. Unsupported hardware or opcodes stop execution.

## Tests

```bash
mvn -Dtest=ArmArithmeticTest test
mvn -Dgroups=core test
mvn -Dgroups=build test
mvn -Dgroups=desktop test
```

Surefire writes reports to `target/surefire-reports`. The standard suite includes
`LegacyCoreTest`: it exports the whole core, compiles the generated sources and
runs eight portable checks in an isolated classloader. No BlackBerry SDK is
needed for these host tests.

`./test.sh` delegates to `mvn verify`. For the full suite followed by all `core`
tests against the exported classes, run:

```bash
tools/test-with-console.sh
```

Maven resolves the console launcher, AssertJ and their transitive dependencies.
See [docs/TESTING.md](docs/TESTING.md) for coverage and test conventions.

## Java 21 sources and the old BlackBerry runtime

**BBOS 7.1 does not mean Java 7.1 or Java 1.7.** Its runtime cannot execute Java 21
bytecode. The project keeps one shared core and generates compatible sources:

1. Maven and JDK 21 compile and test the project on the host.
2. `LegacyExport` reads the javac AST and resolves types.
3. Local `var` declarations become explicit types. Simple, directly returned
   switch expressions become classic switches with a return in each branch.
4. The legacy compiler compiles the generated sources against BlackBerry SDK APIs.
5. RAPC creates a `.cod` application for the BlackBerry runtime.

```bash
mvn compile exec:java@legacy-export
```

This is a limited source exporter, not a general Java 21 compiler for BlackBerry.
Lambdas, records, generics, pattern matching, collections and modern JDK APIs are
not automatically portable to the shared core. The exporter rejects many such
constructs; compilation against `net_rim_api.jar` remains the final API check.
Desktop and build packages are excluded from export and can use modern Java APIs.

## BlackBerry Simulator from Linux

```bash
export BB_JDE_HOME='/path/to/wine/drive_c/BlackBerry JDE 7.1.0'
export BB_JDK6_HOME='/path/to/wine/drive_c/Java/jdk1.6.0_45'
export BB_SIMULATOR_HOME='/path/to/wine/drive_c/BlackBerry Simulators/9900'
mvn -Pbb-sim verify
```

The profile uses existing Windows SDK, JDK 6 and simulator installations through
Wine. It does not download them. Once the `.cod` is loaded, open **BerryBoy Advance**
in the simulator. The MIDlet runs eight checks inside the device runtime and
shows `PASS` or `FAILED`, elapsed time and the demo image.

See [docs/BLACKBERRY.md](docs/BLACKBERRY.md) for setup and limitations. Simulator
startup alone is not a passing integration test; inspect the MIDlet's result.

## Source map

| File or package | Responsibility |
| --- | --- |
| `Cpu` | Instruction loop, decoder selection and diagnostics |
| `CpuState` | Registers, CPSR/SPSR, conditions, register banks and SWI entry |
| `ArmInterpreter`, `ThumbInterpreter` | Instruction decoding and execution |
| `ArithmeticLogicUnit`, `BarrelShifter` | Arithmetic, logic, NZCV flags and shifts |
| `InstructionInterpreter` | Shared decoder operations |
| `Memory` | RAM, ROM, mirroring, bus access and selected registers |
| `BitmapRenderer` | BG2 snapshots in bitmap modes 3, 4 and 5 |
| `DemoRom`, `RuntimeChecks` | Original demo and portable runtime diagnostics |
| `desktop/LaunchCommand`, `desktop/Main` | Typed CLI commands, execution, Swing and PNG export |
| `build/LegacyExport`, `LegacySyntaxScanner`, `LegacyApiScanner` | Source export, syntax checks and API checks |
| `platform/midp/berryboy/BlackBerryMidlet.java` | MIDP adapter for the simulator or device |
| `src/test/java/berryboy/` | JUnit 5 tests, AssertJ assertions and fixtures |
| `tools/blackberry.sh` | Linux → Wine → legacy compiler → RAPC → simulator |
| `tools/test-with-console.sh` | Jupiter tests on host and exported core classes |

Production classes are under `src/main/java/berryboy/` unless a full path is shown.

## Verification

- `mvn verify`: **860 passing tests** on Linux, JDK 21 and Maven 3.9.9.
- **834 core tests** repeated against the exported classes through Jupiter.
- Test counts include individual parameterized invocations, not assertion counts.
- The exporter performs 237 syntax rewrites. Generated sources compile with
  `--release 8` and run on the host JVM.
- The CLI uses Java 21 without preview features. JDK 17 is not a supported build target.
- JUnit and AssertJ are excluded from the application JAR and BlackBerry export.
- **Legacy SDK/RAPC compilation, Wine, the simulator and a physical Bold remain unverified.**
- These are project regression tests, not ARM certification or external test-ROM results.

The detailed validation record is [docs/TEST-RESULTS.txt](docs/TEST-RESULTS.txt).

## Repository workflow

Commit and push each verified project update to the configured repository. Use
English commit messages and Markdown documentation. Review ongoing updates in
Git history; HTML viewers and ZIP archives are generated only when requested.
See [AGENTS.md](AGENTS.md).
A missing remote or unavailable write access must be reported explicitly.

The downloadable source archive also includes `BerryBoy-Advance.bundle`, which
preserves the local Git history. From the extracted archive directory, restore it
into a separate checkout with:

```bash
git clone BerryBoy-Advance.bundle berryboy-advance-git
```

A Git bundle is a local history backup; it does not publish changes to a remote.

## Next steps

1. Run the eight portable checks in the actual BB runtime through SDK/RAPC and the simulator.
2. Run external ARM7TDMI test ROMs and fix any discrepancies.
3. Add scheduling, interrupts, timers and DMA.
4. Extend the PPU with tiled backgrounds and sprites, then BIOS/HLE, save hardware and audio.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the current emulation boundaries.

# Linux, Java 21 and BlackBerry OS 7.1

## Three separate runtimes

- JVM 21 on Linux runs Maven, tests, the desktop viewer and the exporter.
- Windows JDK 6 and BlackBerry JDE 7.1 run through Wine to produce code for the
  legacy runtime. They do not need to be the system's default Java installation.
- The simulator or phone executes `.cod` applications, not Java 21 class files.

RIM shipped a Windows simulator for classic BBOS. The `bb-sim` profile integrates
those tools through Wine. It is not a native Linux simulator port; compatibility
with a particular simulator release still needs verification. A BB10 simulator
cannot replace the BBOS 7.1 simulator for the Bold 9900.

## Local prerequisites

1. Maven 3.9.x running on JDK 21; check `mvn -v`.
2. Wine with 32-bit application support and a working graphical environment.
3. A legally obtained BlackBerry JDE 7.1 installation containing `bin/rapc.exe`
   and `lib/net_rim_api.jar`.
4. Windows JDK 6 in the same Wine environment, with `bin/javac.exe` and `bin/jar.exe`.
5. A Bold 9900 simulator containing `9900.bat` and `fledgecontroller.exe`.

All `BB_*` variables are **Linux paths** to installation directories. If you set
`WINEPREFIX`, use the same prefix during installation and builds. The script
converts paths with `winepath` without changing the global `JAVA_HOME`.

```bash
export BB_JDE_HOME="$HOME/.wine-bb/drive_c/BlackBerry JDE 7.1.0"
export BB_JDK6_HOME="$HOME/.wine-bb/drive_c/Java/jdk1.6.0_45"
export BB_SIMULATOR_HOME="$HOME/.wine-bb/drive_c/BlackBerry Simulators/9900"
export WINEPREFIX="$HOME/.wine-bb"
mvn -Pbb-sim verify
```

You can also pass paths through `-Dbb.sdk=...`, `-Dbb.jdk6=...` and
`-Dbb.simulator=...`. `-Dbb.model=9900` sets the launcher and session name.
If the launcher uses another session name, adjust the script or launcher locally.

Build without starting the simulator:

```bash
mvn -Pbb-sim verify -Dbb.launch=false
```

## Profile behavior

1. Compile and test the modern project on JDK 21.
2. Export the shared core to `target/blackberry/generated/berryboy`.
3. Run `javac -source 1.4 -target 1.4` through Wine, with BlackBerry APIs on the bootclasspath.
4. Add the `BlackBerryMidlet` adapter, package a JAR and convert it through RAPC.
5. Start the original `9900.bat` and send `LoadCod(...)` to the simulator controller.
6. Open BerryBoy Advance in the simulator. The MIDlet runs eight local checks
   and the demo. The `Run checks` command repeats them.

Application outputs are under `target/blackberry/output/`; `simulator.log` and
`controller.log` are directly under `target/blackberry/`.
The script removes only its own generated classes and output
directory. It does not close an existing session, reset simulator flash or
install anything on a physical phone. Close an old 9900 session before starting.

**Status: shell syntax has been checked; Wine/JDE/simulator execution is unverified.**
A particular SDK may require RAPC path adjustments, preverification or different
COD submodule loading. Controller exit status alone does not establish success;
the MIDlet must display the result.

The MIDP canvas now continues the demo in a worker loop and forwards directional
game actions plus Z/X, A/S, Enter and Backspace to the shared keypad. Hiding or
pausing it releases held buttons. The screen exposes `KEYINPUT` for diagnosis.
These mappings and the worker lifecycle still need testing on the actual Bold
keyboard and simulator. The loop's sleep is diagnostic pacing, not GBA timing.

The portable channel 2 synthesizer is exported with the core, but the MIDlet has
no device audio playback adapter yet. Java Sound belongs only to the desktop
package. MMAPI/BlackBerry audio format, buffering and device performance need
separate implementation and verification; a desktop WAV does not validate them.

## Source export boundaries

| Construct | Shared core support |
| --- | --- |
| Primitives, arrays, classes, methods and ordinary loops | Supported |
| Local `var` | Supported; javac resolves the type |
| `return switch (int) { case ... -> expression; }` | Lowered to a classic switch |
| Multiple constants in one case label | Lowered to separate case labels |
| Lambdas and method references | Rejected |
| Records, generics and pattern matching | Rejected |
| Try-with-resources and enhanced for loops | Rejected |
| Modern APIs: `String.repeat`, collections, streams and NIO | Not portable; checked by the API gate and SDK compiler |
| Arbitrary Java 21 code | Unsupported; this is not a JVM 21 implementation |

The frontend, build tools and host tests are excluded from export and can use
modern Java without the phone's restrictions. Real Bold performance has not been measured.

## References

- [BlackBerry JDE official samples](https://github.com/blackberry/JDE-Samples)
- [RIM Simulator User Guide, preserved copy](https://device.report/m/43910e81f5369f8768176fc5b78a752fef304c90d0e777e0150ce082949a7c82)
- [RIM MIDlet Developer Guide, preserved copy](https://www.scribd.com/doc/60656300/Blackberry-MIDlet-Developer-Guide)
- [CLDC 1.1 java.lang](https://docs.oracle.com/javame/config/cldc/ref-impl/cldc1.1/jsr139/java/lang/package-summary.html)
- [JavacTask API](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.compiler/com/sun/source/util/JavacTask.html)
- [Wine](https://www.winehq.org/about)

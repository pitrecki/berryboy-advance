# Linux, Java 21 and BlackBerry OS 7.1

## Three separate runtimes

- JVM 21 on Linux runs Maven, tests, the desktop viewer and the exporter.
- JDE 7.1 provides the vendor CLDC/MIDP API and RAPC. The verified Linux build
  runs ECJ and `rapc.jar` on JVM 21. An optional Windows JDK 6 route uses Wine.
- The simulator or phone executes `.cod` applications, not Java 21 class files.

RIM shipped a Windows simulator for classic BBOS. The `bb-sim` profile integrates
those tools through Wine. It is not a native Linux simulator port; compatibility
with a particular simulator release still needs verification. A BB10 simulator
cannot replace the BBOS 7.1 simulator for the Bold 9900.

## Native Linux build (verified with JDE 7.1)

The Linux build needs JDK 21, Maven 3.9.x, an extracted JDE 7.1 installation and
ECJ **4.6.1** (`org.eclipse.jdt.core.compiler:ecj:4.6.1`). Keep both vendor tools
and the compiler JAR outside Git; verify the Maven Central artifact checksum.
The build script does not download SDKs or dependencies automatically.

```bash
export BB_JDE_HOME=/path/to/BlackBerry-JDE-7.1.0
export BB_ECJ_JAR=/path/to/ecj-4.6.1.jar
mvn -Pbb-sim verify -Dbb.compiler=ecj -Dbb.ecj="$BB_ECJ_JAR" -Dbb.launch=false
```

This path does not require Wine or a Windows JDK. ECJ compiles the generated
core and MIDlet with `-source 1.3 -target cldc1.1`, against only
`lib/net_rim_api.jar` on the bootclasspath. Its CLDC target emits `StackMap`
attributes. The original `bin/rapc.jar` then resolves the real vendor imports
and produces `target/blackberry/output/BerryBoyAdvance.cod`.

A plain Java 1.4 classfile target is insufficient: the first SDK run failed with
**Missing stack map** in `BlackBerryMidlet.commandAction`. With CLDC maps, RAPC
reports **No errors** and emits a roughly 46 KiB COD. The full Maven profile
passes 1295 host tests; 1201 core tests also pass using the actual ECJ-compiled
CLDC classes with no host production classes on the runtime classpath. The
separate exported-core console run still passes all 1201 tests. These JVM tests
and the SDK compiler do not establish execution on a BlackBerry.

Evidence and exact tool/output hashes:
[blackberry-sdk-build-2026-10-22.json](../benchmarks/blackberry-sdk-build-2026-10-22.json).
The [shared DMA follow-up](../benchmarks/dma-shared-ownership-2026-10-23.json)
records the current test counts and repeated SDK build after the ownership fix.
The compiled application still contains the original demo and eight portable
checks; it is not a commercial-ROM loading frontend for the phone.

## Vendor preverification and current simulator outcome

The actual Windows `preverify.exe` was also exercised through Wine 10.0, QEMU
10.0.13 and the official Wine preloader. It processed all 45 plain Java 1.4
classes, changing 38 to add verification data. Packaging those outputs and
running RAPC succeeds; another 957 core tests pass against those preverified
classes on the host JVM. The Windows JDK 6 `javac` step remains unrun.

The cloud host rejects native i386 Linux executables. The QEMU/preloader path
works for small Windows tools; private Wine/Liberation fonts and a completed
Wine initialization also allow the standalone 9900 OS 7.1.0.318 simulator window
to start. The vendor controller connects to session 9900 and accepts a LoadCod
command. This does not prove the MIDlet loaded or ran: the initial touch-screen
instruction dialog remains visible and no BBOS application/check result has
been observed. Xvfb initially has no window manager; GUI setup is still under
investigation. Early crashes before fonts were installed are superseded by this
window-startup result, not by a runtime pass. A follow-up with Openbox and the
vendor's `/automate /automate-timeout=1 /automate-no-dialogs` options still
produces no confirmed BBOS boot or application result; it can leave a blank
main window. A controller connection alone does not establish GUI responsiveness.

Do not treat Wine/QEMU timing as Bold performance. The simulator's native audio
backend also has no sound device on this host. Physical input/audio/performance
qualification still requires the actual phone.

## Windows compiler and simulator prerequisites

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
3. Select `-Dbb.compiler=ecj` for the native CLDC compiler, or use the default
   Windows `javac -source 1.4 -target 1.4` route and vendor `preverify.exe`. Both
   compile against the BlackBerry API bootclasspath and require CLDC stack maps.
4. Include the `BlackBerryMidlet` adapter, package the preverified classes, and
   convert the JAR through the selected vendor RAPC entry point.
5. Start the original `9900.bat` and send `LoadCod(...)` to the simulator controller.
6. Open BerryBoy Advance in the simulator. The MIDlet runs eight local checks
   and the demo. The `Run checks` command repeats them.

Application outputs are under `target/blackberry/output/`; `simulator.log` and
`controller.log` are directly under `target/blackberry/`.
The script removes only its own generated classes, preverified classes and
output directory. It does not close an existing session, reset simulator flash or
install anything on a physical phone. Close an old 9900 session before starting.

**Build status:** native Linux ECJ/CLDC compilation and vendor RAPC conversion
are verified. The Windows JDK 6 compiler route and simulator application execution
remain separate checks. Wine runtime experiments and their current outcome are
recorded in the evidence file above. Controller exit status alone does not
establish success; the MIDlet must display its on-runtime checks result.

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

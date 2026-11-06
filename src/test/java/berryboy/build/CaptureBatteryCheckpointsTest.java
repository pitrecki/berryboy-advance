package berryboy.build;

import static org.assertj.core.api.Assertions.assertThat;

import berryboy.EmulatorFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CaptureBatteryCheckpointsTest {
    private static final Path ROOT = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
    @TempDir static Path classes;
    @TempDir Path temporary;
    private final Properties config = new Properties();
    private Path properties;
    private Path output;

    @BeforeAll
    static void compileTool() {
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","21",
                "-cp",ROOT.resolve("target/classes").toString(),"-d",classes.toString(),
                ROOT.resolve("tools/CaptureBatteryCheckpoints.java").toString())).isZero();
    }

    @BeforeEach
    void originalProgramAndDisposableSave() throws Exception {
        // Reset program: unlock Flash, program 0x2a at offset 0x123, then branch to itself.
        var bios = new byte[16384];
        int[] program = {0xe59f0038,0xe3a010aa,0xe5c01000,0xe59f0030,0xe3a01055,0xe5c01000,
                0xe59f0020,0xe3a010a0,0xe5c01000,0xe59f001c,0xe3a0102a,0xe5c01000,0xeafffffe};
        for (var i = 0; i < program.length; i++) EmulatorFixtures.writeWord(bios,i * 4,program[i]);
        EmulatorFixtures.writeWord(bios,0x40,0x0e005555); EmulatorFixtures.writeWord(bios,0x44,0x0e002aaa);
        EmulatorFixtures.writeWord(bios,0x48,0x0e000123);
        asset("bios",bios); asset("rom",new byte[512]); asset("input",new byte[0]);
        var seed = new byte[0x20000]; Arrays.fill(seed,(byte) 255);
        var save = temporary.resolve("original.sav"); Files.write(save,seed);
        config.setProperty("save",save.toString()); config.setProperty("initial_save_sha256",hash(seed));
        config.setProperty("commit","synthetic-test"); config.setProperty("source_dirty","false");
        properties = temporary.resolve("scenario.properties"); output = temporary.resolve("capture");
    }

    @Test
    void capturesChangedFlashWithoutWritingTheSourceOrClearingDirtyState() throws Exception {
        var before = Files.readAllBytes(Path.of(config.getProperty("save")));
        var result = run("1","2");
        assertThat(result.exit).as(result.output).isZero();
        assertThat(result.output).contains("\"frame\":1", "\"backup_dirty\":true", "\"original_save_unchanged\":true");
        assertThat(Files.readAllBytes(Path.of(config.getProperty("save")))).isEqualTo(before);
        assertThat(Files.readAllBytes(output.resolve("initial.sav"))).isEqualTo(before);
        var observed = before.clone(); observed[0x123] = 0x2a;
        assertThat(Files.readAllBytes(output.resolve("frame-1.sav"))).isEqualTo(observed);
        assertThat(output.resolve("frame-1.png")).exists();
        assertThat(output.resolve("frame-2.sav")).doesNotExist();
    }

    @Test
    void mismatchedInitialSaveFailsBeforeCreatingOutput() throws Exception {
        config.setProperty("initial_save_sha256","0".repeat(64)); var result = run("1","2");
        assertThat(result.exit).isNotZero(); assertThat(result.output).contains("Initial save SHA-256 mismatch");
        assertThat(output).doesNotExist();
    }

    @Test
    void invalidInputFailsBeforeRunningOrCreatingOutput() throws Exception {
        asset("input","0 1024 2048 2048 0 0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var result = run("1","2");
        assertThat(result.exit).isNotZero(); assertThat(result.output).contains("Invalid input event");
        assertThat(output).doesNotExist();
    }

    @ParameterizedTest
    @CsvSource({"0,2", "3,2", "1,1025", "6999,7001"})
    void rejectsUnboundedOrInvalidObservationWindows(String first, String last) throws Exception {
        var result = run(first,last);
        assertThat(result.exit).isNotZero(); assertThat(result.output).contains("Invalid checkpoint window");
        assertThat(output).doesNotExist();
    }

    @ParameterizedTest
    @CsvSource({"devices,1", "warmup_frames,1", "rtc,2026-11-06T00:00:00"})
    void rejectsUnsupportedScenarioSettings(String key, String value) throws Exception {
        config.setProperty(key,value); var result = run("1","2");
        assertThat(result.exit).isNotZero(); assertThat(result.output).contains("Requires default RTC");
        assertThat(output).doesNotExist();
    }

    private void asset(String key, byte[] bytes) throws Exception {
        var file = temporary.resolve(key + ".bin"); Files.write(file,bytes);
        config.setProperty(key,file.toString()); config.setProperty(key + "_sha256",hash(bytes));
    }

    private Result run(String first, String last) throws Exception {
        try (var writer = Files.newBufferedWriter(properties)) { config.store(writer,"Original synthetic assets"); }
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),
                "-Djava.awt.headless=true","-cp",classes + ":" + ROOT.resolve("target/classes"),
                "berryboy.desktop.CaptureBatteryCheckpoints",properties.toString(),first,last,output.toString()));
        var log = temporary.resolve("run.log");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(20,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Capture timed out"); }
        return new Result(process.exitValue(),Files.readString(log));
    }

    private record Result(int exit, String output) { }

    private static String hash(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

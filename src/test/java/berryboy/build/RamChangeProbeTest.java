package berryboy.build;

import static org.assertj.core.api.Assertions.assertThat;

import berryboy.EmulatorFixtures;
import berryboy.Machine;
import berryboy.MachineState;
import berryboy.cartridge.CartridgeBackup;
import berryboy.video.LcdTiming;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
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

class RamChangeProbeTest {
    private static final Path ROOT = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
    @TempDir static Path classes;
    @TempDir Path temporary;
    private final Properties config = new Properties();
    private byte[] rom, bios, seed;
    private Path output;

    @BeforeAll
    static void compileTool() {
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","21",
                "-cp",ROOT.resolve("target/classes").toString(),"-d",classes.toString(),ROOT.resolve("tools/RamChangeProbe.java").toString())).isZero();
    }

    @BeforeEach
    void syntheticProgramAndDisposableSeed() throws Exception {
        rom = new byte[512]; bios = new byte[16384];
        int[] program = {0xe59f1018,0xe3a00003,0xe5810000,0xe3a00007,0xe5810000,0xe5810000,0xeafffffe};
        for (var i = 0; i < program.length; i++) EmulatorFixtures.writeWord(bios,i * 4,program[i]);
        EmulatorFixtures.writeWord(bios,0x20,0x03005000);
        asset("rom",rom); asset("bios",bios); asset("input",new byte[0]);
        seed = new byte[32768]; Arrays.fill(seed,(byte) 17); var save = temporary.resolve("seed.sav"); Files.write(save,seed);
        config.setProperty("save",save.toString()); config.setProperty("initial_save_sha256",hash(seed));
        config.setProperty("frames","1"); config.setProperty("trace_ram_address","0x03005000");
        config.setProperty("backup","0"); config.setProperty("commit","synthetic"); config.setProperty("source_dirty","false");
        output = temporary.resolve("capture");
    }

    @Test
    void reportsOnlyChangedValuesAndPreservesTheUnobservedCompleteMachineState() throws Exception {
        var result = run(); assertThat(result.exit).as(result.output).isZero();
        var rows = Files.readAllLines(output.resolve("ram.csv"));
        assertThat(rows).hasSize(3);
        assertThat(Arrays.copyOfRange(rows.get(1).split(","),6,8)).containsExactly("0","3");
        assertThat(Arrays.copyOfRange(rows.get(2).split(","),6,8)).containsExactly("3","7");
        var control = new Machine(rom,bios); control.memory.setBackup(new CartridgeBackup(CartridgeBackup.SRAM));
        control.memory.backup().restore(seed); control.memory.clockedAudio().setCapture(true);
        control.runCycles(LcdTiming.FRAME_CYCLES);
        control.memory.clockedAudio().drain(new short[2048],control.memory.clockedAudio().availableFrames());
        assertThat(result.output).contains(hash(MachineState.save(control)),"\"rows\":2", "\"original_save_unchanged\":true");
        assertThat(Files.readAllBytes(output.resolve("final-backup.sav"))).isEqualTo(seed);
        assertThat(Files.readAllBytes(Path.of(config.getProperty("save")))).isEqualTo(seed);
    }

    @ParameterizedTest
    @CsvSource({"frames,0", "frames,7001", "trace_ram_address,0x03005001", "trace_ram_address,0x04000104",
            "trace_ram_address,0x02040000", "trace_ram_address,0x03008000", "trace_ram_capacity,0", "trace_ram_capacity,65537"})
    void rejectsUnsafeBoundsBeforeCreatingOutput(String key, String value) throws Exception {
        config.setProperty(key,value); var result = run();
        assertThat(result.exit).isNotZero(); assertThat(result.output).contains("Invalid RAM trace bounds");
        assertThat(output).doesNotExist();
    }

    @Test
    void rejectsAnUnexpectedInitialSaveWithoutReplacingIt() throws Exception {
        config.setProperty("initial_save_sha256","0".repeat(64)); var result = run();
        assertThat(result.exit).isNotZero(); assertThat(result.output).contains("Initial save SHA-256 mismatch");
        assertThat(output).doesNotExist(); assertThat(Files.readAllBytes(Path.of(config.getProperty("save")))).isEqualTo(seed);
    }

    @Test
    void exceedingTheChangeCapacityCannotPublishAPassingReport() throws Exception {
        config.setProperty("trace_ram_capacity","1"); var result = run();
        assertThat(result.exit).isNotZero(); assertThat(result.output).contains("RAM change trace capacity exceeded");
        assertThat(output.resolve("report.json")).doesNotExist();
        assertThat(Files.readAllBytes(Path.of(config.getProperty("save")))).isEqualTo(seed);
    }

    @Test
    void futureInputsDoNotPretendToProduceASerializableFinalState() throws Exception {
        asset("input",(2L * LcdTiming.FRAME_CYCLES + " 1 2048 2048 0 0\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var result = run(); assertThat(result.exit).as(result.output).isZero();
        assertThat(result.output).contains("\"final_state_sha256\":null");
    }

    private void asset(String key, byte[] bytes) throws Exception {
        var file = temporary.resolve(key + ".bin"); Files.write(file,bytes);
        config.setProperty(key,file.toString()); config.setProperty(key + "_sha256",hash(bytes));
    }
    private Result run() throws Exception {
        var file = temporary.resolve("scenario.properties"); try (var writer = Files.newBufferedWriter(file)) { config.store(writer,"Synthetic inputs"); }
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),
                "-cp",classes + ":" + ROOT.resolve("target/classes"),"berryboy.desktop.RamChangeProbe",file.toString(),output.toString()));
        var log = temporary.resolve("run.log"); var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(20,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Probe timed out"); }
        return new Result(process.exitValue(),Files.readString(log));
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private record Result(int exit, String output) { }
}

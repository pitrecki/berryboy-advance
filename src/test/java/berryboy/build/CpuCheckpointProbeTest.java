package berryboy.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("build")
class CpuCheckpointProbeTest {
    private static final Path ROOT = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
    @TempDir Path temporary;
    private Path properties;
    private Path windows;
    private Path classes;
    private int runs;

    @BeforeEach
    void prepareOriginalAssetFreeProgram() throws Exception {
        var settings = new Properties();
        for (var key : List.of("rom", "bios", "input")) {
            var bytes = new byte[key.equals("bios") ? 16384 : key.equals("rom") ? 512 : 0];
            var file = temporary.resolve(key + ".bin");
            Files.write(file, bytes);
            settings.setProperty(key, file.toString());
            settings.setProperty(key + "_sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        settings.setProperty("fresh_backup", "true");
        properties = temporary.resolve("fresh.properties");
        try (var out = Files.newBufferedWriter(properties)) { settings.store(out, "Original zero-filled diagnostic assets"); }
        windows = Files.writeString(temporary.resolve("windows.tsv"), "10 20\n30 40\n");
        classes = compile("observed", Files.readString(ROOT.resolve("tools/CpuCheckpointProbe.java")));
    }

    @Test
    void samplesExactCyclesAcrossDisjointWindowsWhileCpuIsExecuting() throws Exception {
        var result = run(classes);
        var clock = result.output().lines().filter(line -> line.startsWith("clock,")).toList();
        assertSoftly(softly -> {
            softly.assertThat(result.exit()).as(result.output()).isZero();
            softly.assertThat(clock).hasSize(22);
            softly.assertThat(clock.getFirst()).startsWith("clock,10,");
            softly.assertThat(clock.getLast()).startsWith("clock,40,");
            softly.assertThat(clock).noneMatch(line -> line.startsWith("clock,25,"));
            softly.assertThat(result.output()).doesNotContain("Recursive guest-clock advancement");
        });
    }

    @Test
    void installingClockSamplesDoesNotChangeCompletedStepStatesWhileCpuExecutes() throws Exception {
        var source = Files.readString(ROOT.resolve("tools/CpuCheckpointProbe.java"));
        var scheduling = "machine.clock.schedule(sample, windows.getFirst()[0]);";
        assertThat(source).containsOnlyOnce(scheduling);
        var control = compile("control", source.replace(scheduling, "// Control: no sampling callbacks installed."));
        var observed = run(classes);
        var unobserved = run(control);
        assertSoftly(softly -> {
            softly.assertThat(observed.exit()).as(observed.output()).isZero();
            softly.assertThat(unobserved.exit()).as(unobserved.output()).isZero();
            softly.assertThat(observed.output().lines().filter(line -> !line.startsWith("clock,")).toList())
                    .containsExactlyElementsOf(unobserved.output().lines().toList());
        });
    }

    @Test
    void clockSamplesSubdivideHaltWaitsWithoutRetiringInstructions() throws Exception {
        var bios = new byte[16384];
        ByteBuffer.wrap(bios).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0xe3a00301).putInt(0xe3a01000).putInt(0xe5c01301); // MOV r0,#IO; MOV r1,#0; STRB r1,[r0,#HALTCNT].
        Files.write(temporary.resolve("bios.bin"), bios);
        var settings = new Properties();
        try (var in = Files.newBufferedReader(properties)) { settings.load(in); }
        settings.setProperty("bios_sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bios)));
        try (var out = Files.newBufferedWriter(properties)) { settings.store(out, "Original HALT program"); }
        var source = Files.readString(ROOT.resolve("tools/CpuCheckpointProbe.java"));
        var control = compile("halt-control", source.replace("machine.clock.schedule(sample, windows.getFirst()[0]);", ""));
        var observed = run(classes);
        var unobserved = run(control);
        assertSoftly(softly -> {
            softly.assertThat(observed.exit()).as(observed.output()).isZero();
            softly.assertThat(unobserved.exit()).as(unobserved.output()).isZero();
            softly.assertThat(observed.output()).contains("step,").doesNotContain("retire,");
            softly.assertThat(unobserved.output()).doesNotContain("retire,");
            softly.assertThat(unobserved.output().lines().filter(line -> line.startsWith("step,")).count())
                    .isLessThan(observed.output().lines().filter(line -> line.startsWith("step,")).count());
        });
        var samples = observed.output().lines().filter(line -> line.startsWith("clock,")).toList();
        assertThat(samples).hasSize(22).allSatisfy(line -> {
            var values = line.split(",");
            assertSoftly(softly -> {
                softly.assertThat(values[2]).isEqualTo("3");
                softly.assertThat(values[3]).isEqualTo("12");
                softly.assertThat(values[5]).isEqualTo("true");
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"20 10", "10 20\n20 30", "-1 20", "0 10001"})
    void rejectsInvalidOrOverlappingWindows(String invalid) throws Exception {
        Files.writeString(windows, invalid);
        var result = run(classes);
        assertSoftly(softly -> {
            softly.assertThat(result.exit()).isNotZero();
            softly.assertThat(result.output()).contains("Invalid/overlapping windows or sample budget exceeded");
        });
    }

    @Test
    void retirementDigestMatchesTheExplicitTraceWithoutClockSamples() throws Exception {
        var trace = execute(classes, "CpuRetirementProbe", "trace", properties.toString(), "1", "32");
        var digest = execute(classes, "CpuRetirementProbe", "digest", properties.toString(), "32");
        assertSoftly(softly -> {
            softly.assertThat(trace.exit()).as(trace.output()).isZero();
            softly.assertThat(digest.exit()).as(digest.output()).isZero();
        });
        var expected = MessageDigest.getInstance("SHA-256");
        var rows = trace.output().lines().skip(1).toList();
        assertThat(rows).hasSize(32);
        for (var line : rows) {
            var values = line.split(",");
            var state = ByteBuffer.allocate(81).order(ByteOrder.LITTLE_ENDIAN);
            state.putLong(Long.parseLong(values[1]));
            for (var index = 2; index <= 19; index++) state.putInt((int) Long.parseLong(values[index]));
            state.put((byte) (Boolean.parseBoolean(values[20]) ? 1 : 0));
            expected.update(state.array());
        }
        assertThat(digest.output().lines().skip(1).toList())
                .containsExactly("1,32,32," + HexFormat.of().formatHex(expected.digest()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"trace 0 1", "trace 1 4097", "digest 0", "digest 1011225601"})
    void retirementProbeRejectsUnsafeBounds(String setting) throws Exception {
        var parts = setting.split(" ");
        var arguments = new ArrayList<>(List.of(parts[0], properties.toString()));
        arguments.addAll(List.of(parts).subList(1, parts.length));
        var result = execute(classes, "CpuRetirementProbe", arguments.toArray(String[]::new));
        assertSoftly(softly -> {
            softly.assertThat(result.exit()).isNotZero();
            softly.assertThat(result.output()).contains("Invalid cycle/instruction bound");
        });
    }

    private Path compile(String name, String source) throws Exception {
        var directory = Files.createDirectory(temporary.resolve(name));
        var file = Files.writeString(directory.resolve("CpuCheckpointProbe.java"), source);
        var retirement = Files.writeString(directory.resolve("CpuRetirementProbe.java"), Files.readString(ROOT.resolve("tools/CpuRetirementProbe.java")));
        var result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-cp", ROOT.resolve("target/classes").toString(),
                "-d", directory.toString(), file.toString(), retirement.toString());
        assertThat(result).as("Compile diagnostic adapter").isZero();
        return directory;
    }

    private Result run(Path compiled) throws Exception {
        return execute(compiled, "CpuCheckpointProbe", properties.toString(), windows.toString());
    }

    private Result execute(Path compiled, String tool, String... arguments) throws Exception {
        var output = temporary.resolve("run-" + runs++ + ".log");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", compiled + ":" + ROOT.resolve("target/classes"), tool));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Diagnostic adapter timed out");
        }
        return new Result(process.exitValue(), Files.readString(output));
    }

    private record Result(int exit, String output) {}
}

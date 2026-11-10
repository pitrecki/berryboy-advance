package berryboy.build;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("build")
class IrqRefillComparisonTest {
    private static final String HEADER = "mode,code,prefetch,idle,stage,cycle,pc,cpsr,lr,r0\n";
    @TempDir Path directory;

    @Test
    void alignsIdenticalRetirementsWithoutClaimingAbsoluteEntryLatency() throws Exception {
        var result = run(trace(10, 0x08000020, 0), trace(3, 0x08000020, 0), trace(500, 0x08000020, 0), true);
        assertThat(result.exit).isZero();
        assertThat(result.output).contains("\"status\": \"exact-match\"", "\"shared_reference_observations\": 4",
                "\"full_phase_qualified\": true", "\"host_shared_mismatch_count\": 0");
        assertThat(Files.readString(fixture())).contains("2,12,0x08000020,0xa000005f,0x00000000,0");
    }

    @Test
    void reportsHostDifferencesAgainstSharedExpectations() throws Exception {
        var result = run(trace(10, 0x08000020, 0), trace(3, 0x08000020, 0), trace(500, 0x08000020, 2), true);
        assertThat(result.exit).isEqualTo(1);
        assertThat(result.output).contains("\"shared_subset_exact\": false", "\"host_shared_mismatch_count\": 4",
                "\"model_disagreement_count\": 0", "\"cycles_since_handler\": 14");
        // The host cannot choose its own oracle values.
        assertThat(Files.readString(fixture())).contains("2,12,").doesNotContain("2,14,");
    }

    @Test
    void retainsDisputedCasesWithoutPublishingThemAsSharedExpectations() throws Exception {
        var first = trace(10, 0x08000020, 0); var second = body(trace(30, 0x08020000, 0));
        var result = run(first + second, first + body(trace(30, 0x08020000, 1)), first + second, true);
        assertThat(result.exit).isEqualTo(1);
        assertThat(result.output).contains("\"shared_subset_exact\": true", "\"full_phase_qualified\": false",
                "\"shared_reference_observations\": 4", "\"model_disagreement_count\": 4", "\"host_shared_mismatch_count\": 0");
        assertThat(Files.readAllLines(fixture())).hasSize(5);
        assertThat(Files.readString(fixture())).contains("0x08000020").doesNotContain("0x08020000");
    }

    @Test
    void comparesArchitectureFieldsAsWellAsCycles() throws Exception {
        var a = trace(10, 0x08000020, 0);
        var b = a.replace("2,22,0x08000020,0xa000005f", "2,22,0x08000020,0x0000005f");
        var result = run(a, b, a, true);
        assertThat(result.exit).isEqualTo(1);
        assertThat(result.output).contains("\"shared_reference_observations\": 3", "\"model_disagreement_count\": 1");
        assertThat(Files.readString(fixture())).doesNotContain("2,12,");
    }

    @Test
    void noSharedValuesCannotProduceAVacuousPass() throws Exception {
        var result = run(trace(10, 0x08000020, 0), trace(3, 0x08000020, 1), trace(500, 0x08000020, 0), false);
        assertThat(result.exit).isEqualTo(1);
        assertThat(result.output).contains("\"shared_reference_observations\": 0", "\"shared_subset_exact\": false",
                "\"full_phase_qualified\": false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "missing", "duplicate", "clock", "negative", "keys", "anchor", "number", "register"})
    void rejectsInvalidOrUnalignableTracesBeforeWritingAFixture(String kind) throws Exception {
        var a = trace(10, 0x08000020, 0); var b = a; var h = a;
        switch (kind) {
            case "header" -> a = a.replace("cycle,", "elapsed,");
            case "missing" -> a = a.substring(0, a.lastIndexOf("arm,"));
            case "duplicate" -> a += a.substring(a.lastIndexOf("arm,"));
            case "clock" -> a = a.replace("2,22,", "2,9,");
            case "negative" -> a = a.replace("2,22,", "2,-1,");
            case "keys" -> h = trace(10, 0x08000040, 0);
            case "anchor" -> h = h.replace("0xa00000d2", "0x000000d2");
            case "number" -> a = a.replace("2,22,", "2,no-number,");
            case "register" -> a = a.replace(",0\n", ",-1\n");
            default -> throw new IllegalArgumentException(kind);
        }
        var result = run(a, b, h, true);
        assertThat(result.exit).isEqualTo(2);
        assertThat(result.output).contains("error:").doesNotContain("\"shared_subset_exact\"");
        assertThat(fixture()).doesNotExist();
    }

    @Test
    void refusesAnExistingFixture() throws Exception {
        Files.writeString(fixture(), "Existing expectation\n");
        var a = trace(10, 0x08000020, 0); var result = run(a, a, a, true);
        assertThat(result.exit).isEqualTo(2);
        assertThat(Files.readString(fixture())).isEqualTo("Existing expectation\n");
    }

    private Path fixture() { return directory.resolve("shared.csv"); }
    private Result run(String nba, String mgba, String host, boolean writeFixture) throws Exception {
        var inputs = new String[]{nba, mgba, host};
        var root = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        var command = new java.util.ArrayList<String>(); command.add("python3"); command.add(root.resolve("tools/compare_irq_refill.py").toString());
        for (var i = 0; i < inputs.length; i++) {
            var path = directory.resolve("trace" + i + ".csv"); Files.writeString(path, inputs[i]); command.add(path.toString());
        }
        if (writeFixture) { command.add("--shared-fixture"); command.add(fixture().toString()); }
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); var exit = process.waitFor();
        for (var i = 0; i < inputs.length; i++) assertThat(Files.readString(directory.resolve("trace" + i + ".csv"))).isEqualTo(inputs[i]);
        return new Result(exit, output);
    }
    private static String body(String trace) { return trace.substring(HEADER.length()); }
    private static String trace(int origin, int code, int extra) {
        var out = new StringBuilder(HEADER);
        for (var stage = 1; stage <= 5; stage++) {
            var cycle = origin + (stage == 1 ? 0 : 12 + (stage - 2) * 6 + extra);
            var pc = stage == 1 ? 0x1c : code + Math.max(0, stage - 2) * 4;
            out.append("arm,0x%08x,0,0,%d,%d,0x%08x,0x%08x,0x%08x,%d%n".formatted(code, stage, cycle, pc,
                    stage == 1 ? 0xa00000d2 : 0xa000005f, stage == 1 ? code + 4 : 0, Math.max(0, stage - 2)));
        }
        return out.toString();
    }
    private record Result(int exit, String output) { }
}

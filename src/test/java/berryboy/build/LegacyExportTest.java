package berryboy.build;

import berryboy.cpu.Cpu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("build")
@DisplayName("Legacy source export")
class LegacyExportTest {
    @TempDir
    Path temporary;

    private Path sources;
    private Path generated;

    @BeforeEach
    void createSourceDirectories() throws IOException {
        sources = Files.createDirectory(temporary.resolve("src"));
        generated = temporary.resolve("generated");
    }

    @Test
    void infersLocalTypesAndLowersReturnedSwitchExpressions() throws IOException {
        writeSample("""
                package berryboy;

                public class Sample {
                    public static int run(int number) {
                        var /* var is also in this comment */ value = number + 2;
                        return switch (value) {
                            case 2, 3 -> 7;
                            default -> value;
                        };
                    }
                }
                """);

        var changes = LegacyExport.export(sources, generated);
        var result = Files.readString(generated.resolve("Sample.java"));

        assertSoftly(softly -> {
            softly.assertThat(changes).as("one var and one switch").isEqualTo(2);
            softly.assertThat(result)
                    .contains("int /* var is also in this comment */ value", "case 2: case 3: return 7;")
                    .doesNotContain("->");
        });

        var compilation = CompilerFixture.compileLegacySources(generated, temporary.resolve("classes"));
        assertThat(compilation.successful()).as(compilation::diagnostics).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedSources")
    void rejectsUnsupportedSyntaxAndApis(RejectedSource sample) throws IOException {
        writeSample(sample.source());

        assertThatThrownBy(() -> LegacyExport.export(sources, generated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(sample.reason());
    }

    @ParameterizedTest(name = "host dependency: {0}")
    @ValueSource(strings = {"berryboy.cpu.Cpu", "berryboy.desktop.Main", "org.junit.jupiter.api.Test"})
    void rejectsTypesAvailableOnlyOnTheHostClasspath(String type) throws IOException {
        writeSample("package berryboy; public class Sample { " + type + " dependency; }");

        assertThatThrownBy(() -> LegacyExport.export(sources, generated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sample.java");
        assertThat(generated).doesNotExist();
    }

    @Test
    void compilesCrossFileReferencesFromTheExportInputs() throws IOException {
        writeSample("""
                package berryboy;
                public class Sample {
                    public static int run() { var value = Helper.answer(); return value; }
                }
                """);
        Files.writeString(sources.resolve("Helper.java"), """
                package berryboy;
                final class Helper { static int answer() { return 42; } }
                """);

        LegacyExport.export(sources, generated);

        var compilation = CompilerFixture.compileLegacySources(generated, temporary.resolve("classes"));
        assertThat(compilation.successful()).as(compilation::diagnostics).isTrue();
    }

    @Test
    void generatedCompilationCannotResolveMissingClassesFromTheHost() throws IOException {
        writeSample("package berryboy; public class Sample { Cpu missingCoreClass; }");

        var compilation = CompilerFixture.compileLegacySources(sources, temporary.resolve("classes"));

        assertSoftly(softly -> {
            softly.assertThat(compilation.successful()).isFalse();
            softly.assertThat(compilation.diagnostics()).contains("Cpu");
        });
    }

    private void writeSample(String source) throws IOException {
        Files.writeString(sources.resolve("Sample.java"), source);
    }

    private static Stream<RejectedSource> unsupportedSources() {
        return Stream.of(
                new RejectedSource("modern JDK API", """
                        package berryboy;
                        public class Sample {
                            public String repeat() { return "a".repeat(2); }
                        }
                        """, "Unapproved legacy API"),
                new RejectedSource("lambda", """
                        package berryboy;
                        public class Sample { Runnable action = () -> {}; }
                        """, "LAMBDA_EXPRESSION"),
                new RejectedSource("record", """
                        package berryboy;
                        public record Sample(int value) {}
                        """, "RECORD"),
                new RejectedSource("numeric separator", """
                        package berryboy;
                        public class Sample { int value = 1_000; }
                        """, "numeric literal"),
                new RejectedSource("switch assigned to a local variable", """
                        package berryboy;
                        public class Sample {
                            int run(int number) {
                                var value = switch (number) { default -> 1; };
                                return value;
                            }
                        }
                        """, "Only directly returned"));
    }

    private record RejectedSource(String name, String source, String reason) {
        @Override
        public String toString() {
            return name;
        }
    }
}

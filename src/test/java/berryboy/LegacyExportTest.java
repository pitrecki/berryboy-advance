package berryboy;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import berryboy.build.LegacyExport;
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

        assertAll(
                () -> assertEquals(2, changes, "one var and one switch"),
                () -> assertTrue(result.contains("int /* var is also in this comment */ value")),
                () -> assertTrue(result.contains("case 2: case 3: return 7;")),
                () -> assertFalse(result.contains("->")));

        var compilation = CompilerFixture.compileLegacySources(generated, temporary.resolve("classes"));
        assertTrue(compilation.successful(), compilation::diagnostics);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedSources")
    void rejectsUnsupportedSyntaxAndApis(RejectedSource sample) throws IOException {
        writeSample(sample.source());

        var error = assertThrows(IllegalArgumentException.class, () -> LegacyExport.export(sources, generated));

        assertTrue(error.getMessage().contains(sample.reason()), error::getMessage);
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

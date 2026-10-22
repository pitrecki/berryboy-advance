package berryboy.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("build")
@DisplayName("Incremental legacy export output")
class LegacyExportOutputTest {
    private static final String SAMPLE = "package berryboy; public class Sample { int value = 1; }";

    @TempDir
    Path temporary;

    private Path sources;
    private Path generated;

    @BeforeEach
    void createSources() throws IOException {
        sources = Files.createDirectory(temporary.resolve("src"));
        generated = temporary.resolve("generated");
        Files.writeString(sources.resolve("Sample.java"), SAMPLE);
    }

    @Test
    void removesDeletedSourcesAndRefreshesChangedSourcesWithoutACleanBuild() throws IOException {
        var retired = Files.writeString(sources.resolve("Retired.java"),
                "package berryboy; class Retired {}");
        LegacyExport.export(sources, generated);
        var notes = Files.writeString(generated.resolve("notes.txt"), "Keep this diagnostic");

        Files.delete(retired);
        Files.writeString(sources.resolve("Sample.java"), SAMPLE.replace("value = 1", "value = 2"));
        LegacyExport.export(sources, generated);

        assertSoftly(softly -> {
            softly.assertThat(generated.resolve("Retired.java")).doesNotExist();
            softly.assertThat(generated.resolve("Sample.java")).content().contains("value = 2");
            softly.assertThat(notes).hasContent("Keep this diagnostic");
            softly.assertThat(sources.resolve("Sample.java")).content().doesNotContain("Generated");
        });
    }

    @ParameterizedTest(name = "invalid source: {0}")
    @ValueSource(strings = {
            "package berryboy; class Broken { int value = ; }",
            "package berryboy; record Broken(int value) {}",
            "package berryboy; class Broken { String value() { return \"a\".repeat(2); } }"
    })
    void preservesTheLastExportWhenAnySourceFailsValidation(String invalidSource) throws IOException {
        LegacyExport.export(sources, generated);
        var previous = Files.readString(generated.resolve("Sample.java"));
        Files.writeString(sources.resolve("Sample.java"), SAMPLE.replace("value = 1", "value = 2"));
        Files.writeString(sources.resolve("Broken.java"), invalidSource);

        assertThatThrownBy(() -> LegacyExport.export(sources, generated))
                .isInstanceOf(IllegalArgumentException.class);

        assertSoftly(softly -> {
            softly.assertThat(generated.resolve("Sample.java")).hasContent(previous);
            softly.assertThat(generated.resolve("Broken.java")).doesNotExist();
        });
    }

    @ParameterizedTest(name = "overlapping output: {0}")
    @ValueSource(strings = {".", "generated", ".."})
    void rejectsOverlappingInputAndOutputDirectories(String relativeOutput) {
        var output = sources.resolve(relativeOutput);

        assertThatThrownBy(() -> LegacyExport.export(sources, output))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not overlap");
        assertThat(sources.resolve("Sample.java")).hasContent(SAMPLE);
    }

    @Test
    void rejectsAnOutputSymlinkPointingIntoTheSources() throws IOException {
        Files.createSymbolicLink(generated, sources);

        assertThatThrownBy(() -> LegacyExport.export(sources, generated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not overlap");
        assertThat(sources.resolve("Sample.java")).hasContent(SAMPLE);
    }

    @ParameterizedTest(name = "unowned Java file: {0}")
    @ValueSource(strings = {"Sample.java", "Unrelated.java"})
    void refusesToOverwriteOrDeleteHandwrittenJavaFiles(String name) throws IOException {
        Files.createDirectory(generated);
        var handwritten = Files.writeString(generated.resolve(name), "// Handwritten source");

        assertThatThrownBy(() -> LegacyExport.export(sources, generated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(name);
        assertThat(handwritten).hasContent("// Handwritten source");
    }

    @Test
    void refusesToWriteThroughAnOutputFileSymlink() throws IOException {
        LegacyExport.export(sources, generated);
        var exported = generated.resolve("Sample.java");
        var outside = Files.move(exported, temporary.resolve("outside.java"));
        var previous = Files.readString(outside);
        Files.createSymbolicLink(exported, outside);
        Files.writeString(sources.resolve("Sample.java"), SAMPLE.replace("value = 1", "value = 2"));

        assertThatThrownBy(() -> LegacyExport.export(sources, generated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sample.java");
        assertThat(outside).hasContent(previous);
    }

    @Test
    void preservesFeaturePackagesWithDuplicateBasenamesAndRemovesNestedStaleFiles() throws IOException {
        Files.createDirectories(sources.resolve("audio")); Files.createDirectories(sources.resolve("video"));
        Files.writeString(sources.resolve("audio/Model.java"), "package berryboy.audio; public class Model { public int value=2; }");
        Files.writeString(sources.resolve("video/Model.java"), "package berryboy.video; public class Model { public int value=3; }");
        Files.writeString(sources.resolve("Sample.java"), "package berryboy; public class Sample { int value=new berryboy.audio.Model().value + new berryboy.video.Model().value; }");
        LegacyExport.export(sources, generated);
        assertThat(generated.resolve("audio/Model.java")).content().contains("package berryboy.audio;");
        assertThat(generated.resolve("video/Model.java")).content().contains("package berryboy.video;");
        var compilation = CompilerFixture.compileLegacySources(generated, temporary.resolve("classes"));
        assertThat(compilation.successful()).as(compilation::diagnostics).isTrue();
        Files.writeString(sources.resolve("Sample.java"), SAMPLE);
        Files.delete(sources.resolve("audio/Model.java"));
        LegacyExport.export(sources, generated);
        assertThat(generated.resolve("audio/Model.java")).doesNotExist();
        assertThat(generated.resolve("video/Model.java")).exists();
    }

    @Test
    void rejectsNestedOutputSymlinkBeforeWritingAnyFiles() throws IOException {
        Files.createDirectories(sources.resolve("video"));
        Files.writeString(sources.resolve("video/Frame.java"), "package berryboy.video; public class Frame {}");
        LegacyExport.export(sources, generated);
        Files.delete(generated.resolve("video/Frame.java")); Files.delete(generated.resolve("video"));
        var outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createSymbolicLink(generated.resolve("video"), outside);
        assertThatThrownBy(() -> LegacyExport.export(sources, generated)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symlink");
        assertThat(outside.resolve("Frame.java")).doesNotExist();
    }
}

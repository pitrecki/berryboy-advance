package berryboy;

import static org.assertj.core.api.Assertions.assertThat;

import berryboy.build.LegacyExport;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("build")
@DisplayName("Complete generated core")
class LegacyCoreTest {
    @TempDir
    Path temporary;

    @Test
    void exportsCompilesAndRunsThePortableChecksInIsolation() throws Exception {
        var projectRoot = Path.of(System.getProperty("berryboy.projectRoot", "."));
        var sourceRoot = projectRoot.resolve("src/main/java/berryboy");
        var generated = temporary.resolve("generated/berryboy");
        var classes = temporary.resolve("classes");

        LegacyExport.export(sourceRoot, generated);
        var compilation = CompilerFixture.compileLegacySources(generated, classes);
        assertThat(compilation.successful()).as(compilation::diagnostics).isTrue();

        // A platform parent prevents the host core from shadowing the generated classes.
        var classpath = new URL[]{classes.toUri().toURL()};
        try (var loader = new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader())) {
            var checks = loader.loadClass("berryboy.RuntimeChecks");
            assertThat(checks.getClassLoader()).isSameAs(loader);
            assertThat(checks.getMethod("run").invoke(null)).isEqualTo(8);
        }
    }
}

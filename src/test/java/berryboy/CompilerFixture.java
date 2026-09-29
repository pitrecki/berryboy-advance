package berryboy;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;

/** Compiles generated sources in a temporary directory with real javac diagnostics. */
final class CompilerFixture {
    private CompilerFixture() {
    }

    static CompilationResult compileLegacySources(Path sourceRoot, Path output) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Tests require a full JDK, including javac");
        }
        Files.createDirectories(output);
        try (var paths = Files.walk(sourceRoot);
                var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var sources = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
            var files = manager.getJavaFileObjectsFromPaths(sources);
            var diagnostics = new StringWriter();
            var options = List.of("--release", "8", "-d", output.toString());
            var success = compiler.getTask(diagnostics, manager, null, options, null, files).call();
            return new CompilationResult(success, diagnostics.toString());
        }
    }

    record CompilationResult(boolean successful, String diagnostics) {
    }
}

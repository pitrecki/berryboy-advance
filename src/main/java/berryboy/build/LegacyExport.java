package berryboy.build;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/**
 * Exports the shared core's supported Java 21 syntax to legacy sources.
 * The SDK compiler remains responsible for checking the actual CLDC API surface.
 */
public final class LegacyExport {
    private LegacyExport() {
    }

    public static void main(String[] args) throws IOException {
        var root = args.length > 0 ? Path.of(args[0]) : Path.of("src/main/java/berryboy");
        var output = args.length > 1 ? Path.of(args[1]) : Path.of("target/blackberry/generated/berryboy");
        var changes = export(root, output);
        System.out.println("Legacy export: " + changes + " syntax rewrites (var / returned switch expressions)");
    }

    public static int export(Path root, Path output) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Legacy export requires a full JDK 21+");
        }

        var paths = sourcePaths(root);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var inputs = manager.getJavaFileObjectsFromPaths(paths);
            var release = System.getProperty("berryboy.sourceRelease", "21");
            var options = List.of("--release", release, "-proc:none");
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, options, null, inputs);
            var trees = Trees.instance(task);

            // javac replaces local var nodes during analysis, so collect edit positions first.
            var sources = parseSources(task, trees);
            task.analyze();
            rejectCompilerErrors(diagnostics);
            sources.forEach(source -> LegacyApiScanner.validate(source.tree(), trees));

            // Resolve every source before writing any output, including inferred var types.
            var outputs = new LinkedHashMap<Path, String>();
            for (var source : sources) {
                outputs.put(output.resolve(source.fileName()), source.rewrite(trees));
            }
            writeOutputs(output, outputs);
            return sources.stream().mapToInt(source -> source.edits().size()).sum();
        }
    }

    private static List<Path> sourcePaths(Path root) throws IOException {
        try (var files = Files.list(root)) {
            var paths = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
            if (paths.isEmpty()) {
                throw new IllegalArgumentException("No core Java files in " + root);
            }
            return paths;
        }
    }

    private static List<SourceFile> parseSources(JavacTask task, Trees trees) throws IOException {
        var sources = new ArrayList<SourceFile>();
        for (var tree : task.parse()) {
            var text = tree.getSourceFile().getCharContent(true).toString();
            var edits = LegacySyntaxScanner.collectEdits(tree, text, trees);
            sources.add(new SourceFile(tree, text, edits));
        }
        return sources;
    }

    private static void rejectCompilerErrors(DiagnosticCollector<JavaFileObject> diagnostics) {
        var errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(errors);
        }
    }

    private static void writeOutputs(Path output, Map<Path, String> sources) throws IOException {
        Files.createDirectories(output);
        for (var source : sources.entrySet()) {
            Files.writeString(source.getKey(), source.getValue(), StandardCharsets.UTF_8);
        }
    }

    private record SourceFile(CompilationUnitTree tree, String text, List<LegacySyntaxScanner.Edit> edits) {
        Path fileName() {
            return Path.of(tree.getSourceFile().toUri()).getFileName();
        }

        String rewrite(Trees trees) {
            var result = new StringBuilder(text);
            var reverseOrder = edits.stream()
                    .sorted(Comparator.comparingInt(LegacySyntaxScanner.Edit::start).reversed())
                    .toList();
            for (var edit : reverseOrder) {
                result.replace(edit.start(), edit.end(), edit.resolve(trees));
            }
            return "// Generated from Java 21 sources by LegacyExport; edit the original.\n" + result;
        }
    }
}

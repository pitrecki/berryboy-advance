package berryboy.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Owns generated Java files while protecting source trees and handwritten files. */
final class GeneratedSources {
    static final String HEADER = "// Generated from Java 21 sources by LegacyExport; edit the original.\n";

    private GeneratedSources() {
    }

    static Path outputDirectory(Path sourceRoot, Path requestedOutput) throws IOException {
        var source = sourceRoot.toRealPath();
        var output = resolveExistingParents(requestedOutput);
        if (source.startsWith(output) || output.startsWith(source)) {
            throw new IllegalArgumentException("Source and generated output directories must not overlap");
        }
        return output;
    }

    static void write(Path directory, Map<Path, String> sources) throws IOException {
        var previous = existingJavaFiles(directory);
        // Validate the entire destination before modifying any file.
        for (var file : previous) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !Files.readString(file).startsWith(HEADER)) {
                throw new IllegalArgumentException("Refusing to replace an unowned generated source: " + file);
            }
        }
        for (var target : sources.keySet()) {
            if (!target.normalize().startsWith(directory)) throw new IllegalArgumentException("Generated path escapes output");
            for (var parent = target.getParent(); parent.startsWith(directory); parent = parent.getParent()) {
                if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("Invalid generated source directory: " + parent);
                }
            }
        }

        Files.createDirectories(directory);
        for (var source : sources.entrySet()) {
            Files.createDirectories(source.getKey().getParent());
            Files.writeString(source.getKey(), HEADER + source.getValue());
        }
        for (var file : previous) {
            if (!sources.containsKey(file)) {
                Files.delete(file);
            }
        }
    }

    private static List<Path> existingJavaFiles(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (var entries = Files.walk(directory)) {
            return entries.peek(path -> {
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Refusing generated output symlink: " + path);
            }).filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static Path resolveExistingParents(Path path) throws IOException {
        var absolute = path.toAbsolutePath().normalize();
        var existing = absolute;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        return existing.toRealPath().resolve(existing.relativize(absolute));
    }
}

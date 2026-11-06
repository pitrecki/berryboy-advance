package berryboy.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.tools.ToolProvider;

public final class PhoneHarness {
    private PhoneHarness() { }
    public static int compile(Path root, Path output, boolean session) throws Exception {
        Files.createDirectories(output);
        var args = new ArrayList<String>();
        args.addAll(java.util.List.of("--release", "21", "-cp", root.resolve("target/classes").toString(), "-d", output.toString()));
        try (var sources = Files.walk(root.resolve("src/test/fixtures/phone"))) {
            sources.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
        }
        for (var name : new String[]{"PhoneFiles", "PhoneRecords", "PhoneStorage"}) args.add(root.resolve("platform/midp/berryboy/phone/" + name + ".java").toString());
        for (var name : new String[]{"FileUrls", "FilePicker"}) args.add(root.resolve("platform/midp/berryboy/phone/library/" + name + ".java").toString());
        if (session) args.add(root.resolve("platform/midp/berryboy/phone/PhoneSession.java").toString());
        return ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
    }
}

package berryboy.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.tools.ToolProvider;

public final class PhoneHarness {
    private PhoneHarness() { }
    public static int compile(Path root, Path output, boolean session) throws Exception {
        return compile(root, output, session, false);
    }
    public static int compileUi(Path root, Path output) throws Exception {
        return compile(root, output, true, true);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || compileUi(Path.of(args[0]), Path.of(args[1])) != 0) throw new IllegalStateException("UI harness compilation failed");
    }
    private static int compile(Path root, Path output, boolean session, boolean ui) throws Exception {
        Files.createDirectories(output);
        var args = new ArrayList<String>();
        args.addAll(java.util.List.of("--release", "21", "-cp", root.resolve("target/classes").toString(), "-d", output.toString()));
        try (var sources = Files.walk(root.resolve("src/test/fixtures/phone"))) {
            sources.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !ui || !p.endsWith("berryboy/phone/PhoneCanvas.java"))
                    .forEach(p -> args.add(p.toString()));
        }
        for (var name : new String[]{"PhoneFiles", "PhoneRecords", "PhoneStorage"}) args.add(root.resolve("platform/midp/berryboy/phone/" + name + ".java").toString());
        for (var name : new String[]{"FileUrls", "FilePicker"}) args.add(root.resolve("platform/midp/berryboy/phone/library/" + name + ".java").toString());
        if (session) args.add(root.resolve("platform/midp/berryboy/phone/PhoneSession.java").toString());
        if (ui) {
            try (var sources = Files.walk(root.resolve("src/test/fixtures/phone-ui"))) {
                sources.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
            }
            for (var name : new String[]{"PhoneCanvas", "MidpGraphics"}) args.add(root.resolve("platform/midp/berryboy/phone/" + name + ".java").toString());
            args.add(root.resolve("platform/midp/berryboy/phone/settings/BiosSettings.java").toString());
            args.add(root.resolve("platform/midp/berryboy/BlackBerryMidlet.java").toString());
            Files.copy(root.resolve("platform/midp/resources/berryboy-mark.png"), output.resolve("berryboy-mark.png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new));
    }
}

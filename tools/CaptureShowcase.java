package berryboy.desktop;

import berryboy.Machine;
import berryboy.bus.Memory;
import berryboy.cartridge.CartridgeBackup;
import berryboy.desktop.capture.JsonReport;
import berryboy.video.LcdTiming;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;

/** Records real guest frames in the production Swing view; never writes save files. */
public final class CaptureShowcase {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: CaptureShowcase scenarios output manifest sourceCommit");
        var scenarios = Path.of(args[0]).toAbsolutePath();
        var output = Path.of(args[1]).toAbsolutePath();
        if (Files.exists(output)) throw new IllegalArgumentException("Capture output must be new: " + output);
        Files.createDirectories(output);
        var clips = new ArrayList<LinkedHashMap<String, Object>>();
        var index = 0;
        for (var line : Files.readAllLines(Path.of(args[2]))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            var fields = line.split("\t", -1);
            if (fields.length != 5) throw new IllegalArgumentException("Invalid showcase manifest row");
            var first = Integer.parseInt(fields[2]);
            var end = Integer.parseInt(fields[3]);
            var loadSave = Boolean.parseBoolean(fields[4]);
            if (first < 1 || end <= first || end - first > 600) throw new IllegalArgumentException("Invalid clip interval");
            var configPath = scenarios.resolve(fields[0] + ".properties");
            var p = new Properties();
            try (var reader = Files.newBufferedReader(configPath)) { p.load(reader); }
            var base = configPath.getParent();
            var machine = new Machine(verified(base, p, "rom"), verified(base, p, "bios"));
            if (p.containsKey("backup")) machine.memory.setBackup(new CartridgeBackup(Integer.parseInt(p.getProperty("backup"))));
            machine.memory.gpio().configure(Integer.parseInt(p.getProperty("devices", "0")));
            var date = LocalDateTime.parse(p.getProperty("rtc", "2000-01-01T00:00:00"));
            machine.memory.gpio().rtc().setDateTime(date.getYear() % 100, date.getMonthValue(), date.getDayOfMonth(),
                    date.getDayOfWeek().getValue() % 7, date.getHour(), date.getMinute(), date.getSecond());
            String saveHash = null;
            if (loadSave) {
                var bytes = Files.readAllBytes(base.resolve(p.getProperty("save")));
                saveHash = ScenarioRunner.hash(bytes);
                if (!saveHash.equals(p.getProperty("initial_save_sha256"))) throw new IllegalArgumentException("Player save hash mismatch");
                machine.memory.backup().restore(bytes); // Owned copy; the original file is never written.
            }
            var inputBytes = verified(base, p, "input");
            for (var event : new String(inputBytes, StandardCharsets.UTF_8).split("\\R")) {
                if (event.isBlank() || event.startsWith("#")) continue;
                var values = event.strip().split("\\s+");
                if (values.length != 6) throw new IllegalArgumentException("Invalid input recording");
                var cycle = Long.parseLong(values[0]);
                var keys = Integer.decode(values[1]);
                var x = Integer.decode(values[2]); var y = Integer.decode(values[3]);
                var z = Integer.decode(values[4]); var solar = Integer.decode(values[5]);
                machine.clock.schedule(() -> {
                    machine.memory.setPressedKeys(keys);
                    machine.memory.gpio().setSensors(x, y, z, solar);
                }, cycle);
            }
            var panel = new DesktopPanel[1];
            var keyboard = new KeyboardInput();
            SwingUtilities.invokeAndWait(() -> {
                panel[0] = new DesktopPanel();
                keyboard.bind(panel[0]);
                panel[0].setSize(panel[0].getPreferredSize());
                CaptureDocumentation.layout(panel[0]);
            });
            var unique = new HashSet<String>();
            var clipFrames = 0;
            for (var frame = 1; frame < end; frame++) {
                if (loadSave && frame >= first) {
                    var relative = frame - first;
                    SwingUtilities.invokeAndWait(() -> {
                        keyboard.keyChanged(KeyEvent.VK_RIGHT, relative >= 6 && relative < 24);
                        keyboard.keyChanged(KeyEvent.VK_LEFT, relative >= 54 && relative < 72);
                    });
                    machine.memory.setPressedKeys(keyboard.pressedButtons());
                }
                machine.runCycles(frame * (long) LcdTiming.FRAME_CYCLES - machine.clock.now());
                if (frame < first || (frame - first) % 6 != 0) continue;
                var pixels = machine.memory.raster().completedFrame().clone();
                var keys = machine.memory.read16(Memory.KEY_INPUT);
                var file = output.resolve("%04d.png".formatted(index++));
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        panel[0].present(pixels, keys, fields[1] + " / recorded desktop session");
                        CaptureDocumentation.capture(panel[0], file);
                    } catch (Exception error) { throw new IllegalStateException("Cannot capture GUI", error); }
                });
                unique.add(ScenarioRunner.hash(Files.readAllBytes(file)));
                clipFrames++;
            }
            if (unique.size() < 2) throw new IllegalStateException("Clip has no animation: " + fields[1]);
            var clip = new LinkedHashMap<String, Object>();
            clip.put("game", fields[0]); clip.put("title", fields[1]);
            clip.put("rom_sha256", p.getProperty("rom_sha256")); clip.put("bios_sha256", p.getProperty("bios_sha256"));
            clip.put("input_sha256", p.getProperty("input_sha256")); clip.put("initial_save_sha256", saveHash);
            clip.put("first_guest_frame", first); clip.put("end_guest_frame_exclusive", end);
            clip.put("images", clipFrames); clip.put("unique_gui_images", unique.size());
            clip.put("extra_input", loadSave ? "Right at +6..23; Left at +54..71 guest frames" : "none");
            clips.add(clip);
            System.out.println(fields[1] + ": " + clipFrames + " GUI frames, " + unique.size() + " distinct");
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("source_base_commit", args[3]); report.put("source_dirty", true);
        report.put("capture", "Production Swing DesktopPanel painted headlessly from real Machine frame snapshots");
        report.put("sampling", "Every six guest frames; GIF encodes 100 ms per image; silent documentation montage");
        report.put("frames", index);
        var clipJson = clips.stream().map(JsonReport::encode).collect(Collectors.joining(","));
        var json = JsonReport.encode(report);
        // The production report writer handles flat maps; encode each clip
        // through it too, retaining structured metadata and string escaping.
        Files.writeString(output.resolve("capture.json"),
                json.substring(0, json.length() - 1) + ",\"clips\":[" + clipJson + "]}\n");
    }

    private static byte[] verified(Path base, Properties p, String key) throws Exception {
        var bytes = Files.readAllBytes(base.resolve(p.getProperty(key)));
        if (!ScenarioRunner.hash(bytes).equals(p.getProperty(key + "_sha256"))) throw new IllegalArgumentException("Hash mismatch: " + key);
        return bytes;
    }
}

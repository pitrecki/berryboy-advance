package berryboy.desktop;

import berryboy.Machine;
import berryboy.cartridge.CartridgeBackup;
import berryboy.desktop.capture.JsonReport;
import berryboy.MachineState;
import berryboy.video.LcdTiming;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Properties;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/** Real-asset state replay; snapshots and original save bytes stay outside Git. */
public final class StateRoundTrip {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: StateRoundTrip scenario firstFrame resumeFrames output");
        var config = Path.of(args[0]).toAbsolutePath(); var output = Path.of(args[3]);
        if (Files.exists(output)) throw new IllegalArgumentException("Use a fresh output folder");
        Files.createDirectories(output);
        var p = new Properties(); try (var reader = Files.newBufferedReader(config)) { p.load(reader); }
        var rom = verified(config, p, "rom"); var bios = verified(config, p, "bios"); var input = verified(config, p, "input");
        var original = new Machine(rom, bios);
        if (p.containsKey("backup")) original.memory.setBackup(new CartridgeBackup(Integer.parseInt(p.getProperty("backup"))));
        original.memory.gpio().configure(Integer.parseInt(p.getProperty("devices", "0")));
        var date = LocalDateTime.parse(p.getProperty("rtc", "2000-01-01T00:00:00"));
        original.memory.gpio().rtc().setDateTime(date.getYear() % 100, date.getMonthValue(), date.getDayOfMonth(), date.getDayOfWeek().getValue() % 7,
                date.getHour(), date.getMinute(), date.getSecond());
        String initialSaveHash = null; Path savePath = null;
        if (p.containsKey("initial_save_sha256")) {
            savePath = config.getParent().resolve(p.getProperty("save"));
            var bytes = Files.readAllBytes(savePath); initialSaveHash = ScenarioRunner.hash(bytes);
            if (!initialSaveHash.equals(p.getProperty("initial_save_sha256"))) throw new IllegalArgumentException("Initial save hash mismatch");
            original.memory.backup().restore(bytes);
        }
        long lastInput = 0;
        for (var line : new String(input, java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            var v = line.strip().split("\\s+");
            if (v.length != 6) throw new IllegalArgumentException("Invalid input trace");
            long cycle = Long.parseLong(v[0]); int keys = Integer.decode(v[1]);
            int x = Integer.decode(v[2]); int y = Integer.decode(v[3]); int z = Integer.decode(v[4]); int solar = Integer.decode(v[5]);
            original.clock.schedule(() -> { original.memory.setPressedKeys(keys); original.memory.gpio().setSensors(x,y,z,solar); }, cycle);
            lastInput = Math.max(lastInput, cycle);
        }
        var first = Integer.parseInt(args[1]); var resumed = Integer.parseInt(args[2]);
        var pcm = new short[2048]; original.memory.clockedAudio().setCapture(true);
        for (var frame = 1; frame <= first; frame++) {
            original.runCycles(frame * (long) LcdTiming.FRAME_CYCLES - original.clock.now());
            original.memory.clockedAudio().drain(pcm, original.memory.clockedAudio().availableFrames());
        }
        if (lastInput > original.clock.now()) throw new IllegalArgumentException("Snapshot must follow the final recorded input");
        var savedCycles = original.clock.now(); var state = MachineState.save(original);
        Files.write(output.resolve("state.bbs"), state);
        var restored = MachineState.load(rom, bios, state);
        if (!Arrays.equals(state, MachineState.save(restored))) throw new IllegalStateException("Immediate state differs");
        var image = new BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0,0,240,160,original.memory.raster().completedFrame(),0,240);
        ImageIO.write(image, "png", output.resolve("game.png").toFile());
        var secondPcm = new short[2048]; long samples = 0;
        for (var frame = first + 1; frame <= first + resumed; frame++) {
            var target = frame * (long) LcdTiming.FRAME_CYCLES;
            original.runCycles(target - original.clock.now()); restored.runCycles(target - restored.clock.now());
            var count = original.memory.clockedAudio().availableFrames();
            if (count != restored.memory.clockedAudio().availableFrames()) throw new IllegalStateException("Audio count differs");
            original.memory.clockedAudio().drain(pcm, count); restored.memory.clockedAudio().drain(secondPcm, count);
            if (!Arrays.equals(pcm, 0, count * 2, secondPcm, 0, count * 2)
                    || !Arrays.equals(original.memory.raster().completedFrame(), restored.memory.raster().completedFrame())) {
                throw new IllegalStateException("RGB/PCM differs after restoring at frame " + frame);
            }
            samples += count;
        }
        var finalState = MachineState.save(original);
        if (!Arrays.equals(finalState, MachineState.save(restored))) throw new IllegalStateException("Future device/CPU/event state differs");
        if (savePath != null && !initialSaveHash.equals(ScenarioRunner.hash(Files.readAllBytes(savePath)))) throw new IllegalStateException("Original save changed");
        if (original.memory.clockedAudio().overruns()!=0 || original.memory.clockedAudio().underruns()!=0) throw new IllegalStateException("Audio buffer loss");
        var report = new LinkedHashMap<String,Object>();
        report.put("title", original.cartridge.title()); report.put("rom_sha256", ScenarioRunner.hash(rom)); report.put("bios_sha256", ScenarioRunner.hash(bios));
        report.put("input_sha256", ScenarioRunner.hash(input)); report.put("initial_save_sha256", initialSaveHash);
        report.put("snapshot_frame", first); report.put("snapshot_cycles", savedCycles); report.put("resumed_frames", resumed); report.put("resumed_audio_frames", samples);
        report.put("state_bytes", state.length); report.put("state_sha256", ScenarioRunner.hash(state)); report.put("final_state_sha256", ScenarioRunner.hash(finalState));
        report.put("immediate_and_future_full_state_exact", true); report.put("all_resumed_rgb_pcm_exact", true); report.put("original_save_unchanged", true);
        report.put("scope", "Host core save/load replay, not independent game accuracy or physical phone validation");
        Files.writeString(output.resolve("report.json"), JsonReport.encode(report) + "\n");
        System.out.println(JsonReport.encode(report));
    }
    private static byte[] verified(Path config, Properties p, String key) throws Exception {
        var bytes = Files.readAllBytes(config.getParent().resolve(p.getProperty(key)));
        if (!ScenarioRunner.hash(bytes).equals(p.getProperty(key+"_sha256"))) throw new IllegalArgumentException("Hash mismatch: "+key);
        return bytes;
    }
}

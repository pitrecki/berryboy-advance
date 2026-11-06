package berryboy.desktop;

import berryboy.Machine;
import berryboy.cartridge.CartridgeBackup;
import berryboy.desktop.capture.JsonReport;
import berryboy.desktop.scenario.RecordedInputs;
import berryboy.video.LcdTiming;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Properties;
import javax.imageio.ImageIO;

/** Read-only frame-boundary observations of real cartridge writes; never persists to the input save. */
public final class CaptureBatteryCheckpoints {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: CaptureBatteryCheckpoints scenario firstFrame lastFrame freshOutput");
        var first = Integer.parseInt(args[1]); var last = Integer.parseInt(args[2]);
        if (first < 1 || last < first || last > 7000 || last - first >= 1024) {
            throw new IllegalArgumentException("Invalid checkpoint window: 1..7000 frames, at most 1024 observations");
        }
        var config = Path.of(args[0]).toAbsolutePath(); var output = Path.of(args[3]);
        if (Files.exists(output)) throw new IllegalArgumentException("Use a fresh output folder");
        var p = new Properties(); try (var reader = Files.newBufferedReader(config)) { p.load(reader); }
        var commit = required(p,"commit"); var dirty = required(p,"source_dirty");
        if (!p.getProperty("devices", "0").equals("0") || !p.getProperty("warmup_frames", "0").equals("0")
                || !p.getProperty("rtc", "2000-01-01T00:00:00").equals("2000-01-01T00:00:00")) {
            throw new IllegalArgumentException("Requires default RTC, no peripherals and zero warmup");
        }
        var rom = verified(config, p, "rom"); var bios = verified(config, p, "bios"); var input = verified(config, p, "input");
        var recording = RecordedInputs.parse(input);
        var machine = new Machine(rom, bios);
        machine.memory.setBackup(new CartridgeBackup(Integer.parseInt(p.getProperty("backup", "2"))));
        if (machine.memory.backup().type() != CartridgeBackup.FLASH_128K) throw new IllegalArgumentException("Requires Flash128 backup");
        var save = config.getParent().resolve(required(p, "save")); var seedHash = required(p, "initial_save_sha256");
        SaveFile.load(save, machine.memory.backup(), seedHash);
        var initial = machine.memory.backup().snapshot();
        recording.install(machine);
        Files.createDirectories(output); Files.write(output.resolve("initial.sav"), initial);
        var checkpoints = new LinkedHashMap<String,Object>(); byte[] previous = initial; var pcm = new short[2048];
        machine.memory.clockedAudio().setCapture(true);
        for (var frame = 1; frame <= last; frame++) {
            machine.runCycles(frame * (long) LcdTiming.FRAME_CYCLES - machine.clock.now());
            machine.memory.clockedAudio().drain(pcm, machine.memory.clockedAudio().availableFrames());
            if (frame < first) continue;
            var bytes = machine.memory.backup().snapshot();
            if (frame != first && Arrays.equals(previous, bytes)) continue;
            var name = "frame-" + frame; Files.write(output.resolve(name + ".sav"), bytes);
            var image = new BufferedImage(240,160,BufferedImage.TYPE_INT_RGB);
            image.setRGB(0,0,240,160,machine.memory.raster().completedFrame(),0,240);
            ImageIO.write(image,"png",output.resolve(name + ".png").toFile());
            var row = new LinkedHashMap<String,Object>();
            row.put("frame",frame); row.put("cycles",machine.clock.now()); row.put("pc",machine.cpu.pc());
            row.put("backup_sha256",ScenarioRunner.hash(bytes)); row.put("backup_dirty",machine.memory.backup().dirty());
            row.put("png_sha256",ScenarioRunner.hash(Files.readAllBytes(output.resolve(name + ".png"))));
            checkpoints.put(name,row); previous = bytes;
        }
        if (!seedHash.equals(ScenarioRunner.hash(Files.readAllBytes(save)))) throw new IllegalStateException("Input save changed");
        if (machine.memory.clockedAudio().overruns() != 0 || machine.memory.clockedAudio().underruns() != 0) {
            throw new IllegalStateException("Audio buffer loss");
        }
        var report = new LinkedHashMap<String,Object>();
        report.put("source_commit",commit); report.put("source_dirty",dirty);
        report.put("rom_sha256",ScenarioRunner.hash(rom)); report.put("bios_sha256",ScenarioRunner.hash(bios));
        report.put("input_sha256",ScenarioRunner.hash(input)); report.put("initial_save_sha256",seedHash);
        report.put("first_frame",first); report.put("last_frame",last); report.put("checkpoints",checkpoints);
        report.put("original_save_unchanged",true); report.put("audio_overruns",machine.memory.clockedAudio().overruns());
        report.put("audio_underruns",machine.memory.clockedAudio().underruns());
        report.put("scope","Host frame-boundary Flash128 snapshots; first observation and subsequent byte changes only. "
                + "No writes to the input save, no reset of dirty/command state, no physical power-loss or Flash busy-timing qualification.");
        Files.writeString(output.resolve("report.json"),JsonReport.encode(report)+"\n");
        System.out.println(JsonReport.encode(report));
    }

    private static byte[] verified(Path config, Properties properties, String key) throws Exception {
        var bytes = Files.readAllBytes(config.getParent().resolve(required(properties,key)));
        if (!ScenarioRunner.hash(bytes).equals(required(properties,key + "_sha256"))) throw new IllegalArgumentException("Hash mismatch: " + key);
        return bytes;
    }

    private static String required(Properties properties, String key) {
        var value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}

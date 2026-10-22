package berryboy.desktop;

import berryboy.desktop.capture.JsonReport;
import berryboy.desktop.capture.ScenarioCapture;
import berryboy.desktop.capture.VideoState;
import berryboy.desktop.capture.GuestEventTrace;

import berryboy.cartridge.CartridgeBackup;
import berryboy.video.LcdTiming;
import berryboy.Machine;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Deterministic headless capture runner. Physical presentation is never inferred. */
public final class ScenarioRunner {
    private ScenarioRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: ScenarioRunner <scenario.properties>");
        var configPath = Path.of(args[0]).toAbsolutePath();
        var config = new Properties();
        try (var reader = Files.newBufferedReader(configPath)) { config.load(reader); }
        System.out.println(JsonReport.encode(run(config, configPath.getParent())));
    }

    static Map<String, Object> run(Properties config, Path base) throws IOException, InterruptedException {
        var romPath = resolve(base, required(config, "rom"));
        var rom = verified(romPath, required(config, "rom_sha256"));
        byte[] bios = config.containsKey("bios") ? verified(resolve(base, config.getProperty("bios")), required(config, "bios_sha256")) : null;
        var frames = Integer.parseInt(config.getProperty("frames", "1"));
        var warmup = Integer.parseInt(config.getProperty("warmup_frames", "0"));
        if (frames < 1 || frames > 1000000 || warmup < 0 || warmup > 1000000) throw new IllegalArgumentException("Invalid frame budget");
        var checkpoints = checkpoints(config.getProperty("checkpoints", ""), warmup, frames);
        if (config.containsKey("reference_video_sha256") != config.containsKey("reference_audio_sha256")) {
            throw new IllegalArgumentException("Both video and audio references are required for comparison");
        }
        var machine = new Machine(rom, bios);
        if (config.containsKey("backup")) machine.memory.setBackup(new CartridgeBackup(Integer.parseInt(config.getProperty("backup"))));
        machine.memory.gpio().configure(Integer.parseInt(config.getProperty("devices", "0")));
        var seed = config.getProperty("rtc", "2000-01-01T00:00:00");
        var date = LocalDateTime.parse(seed);
        machine.memory.gpio().rtc().setDateTime(date.getYear() % 100, date.getMonthValue(), date.getDayOfMonth(),
                date.getDayOfWeek().getValue() % 7, date.getHour(), date.getMinute(), date.getSecond());
        var save = config.containsKey("save") ? resolve(base, config.getProperty("save")) : null;
        var loadedSave = save != null && Files.exists(save);
        if (save != null) SaveFile.load(save, machine.memory.backup());
        var initialSaveHash = loadedSave ? hash(machine.memory.backup().snapshot()) : null;
        var inputHash = installInputs(machine, config, base);
        var eventTrace = GuestEventTrace.from(config, machine, (warmup + (long) frames) * LcdTiming.FRAME_CYCLES);
        var output = resolve(base, required(config, "output"));
        if (Files.exists(output)) {
            try (var entries = Files.list(output)) {
                if (entries.findAny().isPresent()) throw new IllegalArgumentException("Output directory must be empty: " + output);
            }
        }
        Files.createDirectories(output);
        var stream = machine.memory.clockedAudio();
        stream.setCapture(true);
        var samples = new short[16384];
        var pcm = ByteBuffer.allocate(32768).order(ByteOrder.LITTLE_ENDIAN);
        var durations = new long[frames];
        long dropped = 0;
        long audioFrames = 0;
        long previous = 0;
        var report = new LinkedHashMap<String, Object>();
        var capture = new ScenarioCapture(output);
        try (capture) {
            if (eventTrace != null) machine.clock.setObserver(eventTrace);
            for (int frame = 0; frame < warmup + frames; frame++) {
                var target = (frame + 1L) * LcdTiming.FRAME_CYCLES;
                var started = System.nanoTime();
                machine.runCycles(Math.max(0, target - machine.clock.now()));
                var elapsed = System.nanoTime() - started;
                var completed = machine.memory.raster().completedFrames();
                if (completed != previous + 1) dropped += Math.abs(completed - previous - 1);
                previous = completed;
                int count = stream.availableFrames();
                stream.drain(samples, count);
                pcm.clear();
                for (int i = 0; i < count * 2; i++) pcm.putShort(samples[i]);
                if (frame < warmup) continue;
                durations[frame - warmup] = elapsed;
                audioFrames += count;
                capture.submit(frame + 1, machine.clock.now(), machine.cpu.pc(), machine.cpu.cpsr(),
                        machine.memory.raster().completedFrame(), pcm.array(), count * 4,
                        frame == warmup, frame == warmup + frames - 1,
                        checkpoints[frame + 1] ? VideoState.snapshot(machine.memory) : null);
            }
        } finally {
            machine.clock.setObserver(null);
        }
        if (eventTrace != null) report.put("event_trace", eventTrace.write(output.resolve("events.tsv")));
        var saveWritten = save != null && machine.memory.backup().dirty();
        if (save != null) SaveFile.save(save, machine.memory.backup());
        var videoHash = capture.result().videoSha256();
        var audioHash = capture.result().audioSha256();
        var compared = config.containsKey("reference_video_sha256") && config.containsKey("reference_audio_sha256");
        if (compared && (!videoHash.equals(config.getProperty("reference_video_sha256"))
                || !audioHash.equals(config.getProperty("reference_audio_sha256")))) throw new IllegalArgumentException("Reference capture mismatch");
        Arrays.sort(durations);
        long missed = Arrays.stream(durations).filter(n -> n > 1_000_000_000L * LcdTiming.FRAME_CYCLES / 16777216).count();
        report.put("status", dropped == 0 && stream.overruns() == 0 && stream.underruns() == 0 ? "captured" : "failed");
        report.put("correctness", compared ? "reference-matched" : "unverified");
        report.put("qualification", "headless-only; physical presentation and playback unverified");
        report.put("rom_sha256", hash(rom)); report.put("bios_sha256", bios == null ? null : hash(bios));
        report.put("input_sha256", inputHash); report.put("rtc_seed", seed);
        var backupSnapshot = machine.memory.backup().snapshot();
        report.put("initial_save_sha256", initialSaveHash);
        report.put("backup_sha256", hash(backupSnapshot));
        report.put("backup_type", machine.memory.backup().type());
        report.put("backup_bytes", backupSnapshot.length);
        report.put("save_written", saveWritten);
        report.put("devices", Integer.parseInt(config.getProperty("devices", "0")));
        report.put("rumble_active", machine.memory.gpio().rumbling());
        report.put("commit", config.getProperty("commit", "unrecorded"));
        report.put("source_dirty", config.getProperty("source_dirty", "unknown"));
        report.put("frames", frames); report.put("warmup_frames", warmup); report.put("guest_cycles", machine.clock.now());
        report.put("audio_frames", audioFrames); report.put("audio_overruns", stream.overruns()); report.put("audio_underruns", stream.underruns());
        report.put("produced_audio_frames", stream.producedFrames()); report.put("consumed_audio_frames", stream.consumedFrames());
        report.put("dropped_guest_frames", dropped); report.put("video_sha256", videoHash); report.put("audio_sha256", audioHash);
        report.put("work_p50_ns", durations[(frames - 1) / 2]); report.put("work_p95_ns", durations[(int) ((frames - 1L) * 95 / 100)]);
        report.put("work_p99_ns", durations[(int) ((frames - 1L) * 99 / 100)]); report.put("work_max_ns", durations[frames - 1]);
        report.put("work_deadline_misses", missed); report.put("physically_presented_frames", null); report.put("physical_audio_underruns", null);
        report.put("java", System.getProperty("java.runtime.version")); report.put("os", System.getProperty("os.name"));
        report.put("architecture", System.getProperty("os.arch"));
        report.put("hardware", config.getProperty("hardware", "unrecorded"));
        report.put("os_version", System.getProperty("os.version"));
        Files.writeString(output.resolve("report.json"), JsonReport.encode(report) + "\n");
        return report;
    }

    private static String installInputs(Machine machine, Properties config, Path base) throws IOException {
        if (!config.containsKey("input")) return null;
        var input = verified(resolve(base, config.getProperty("input")), required(config, "input_sha256"));
        long previous = -1;
        for (var line : new String(input, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            var values = line.strip().split("\\s+");
            if (values.length != 6) throw new IllegalArgumentException("Input: cycle keys tiltX tiltY gyro solar");
            var cycle = Long.parseLong(values[0]);
            var keys = Integer.decode(values[1]);
            var x = Integer.decode(values[2]); var y = Integer.decode(values[3]);
            var z = Integer.decode(values[4]); var solar = Integer.decode(values[5]);
            if (cycle <= previous || keys < 0 || keys > 1023 || x < 0 || x > 4095 || y < 0 || y > 4095
                    || z < 0 || z > 4095 || solar < 0 || solar > 255) throw new IllegalArgumentException("Invalid input event");
            previous = cycle;
            machine.clock.schedule(() -> { machine.memory.setPressedKeys(keys); machine.memory.gpio().setSensors(x, y, z, solar); }, cycle);
        }
        return hash(input);
    }

    private static boolean[] checkpoints(String setting, int warmup, int frames) {
        var selected = new boolean[warmup + frames + 1];
        if (setting.isBlank()) return selected;
        for (var value : setting.split(",", -1)) {
            var frame = Integer.parseInt(value.strip());
            if (frame <= warmup || frame > warmup + frames || selected[frame]) {
                throw new IllegalArgumentException("Checkpoint must be unique and inside measured frames");
            }
            selected[frame] = true;
        }
        return selected;
    }

    static String hash(byte[] data) { return HexFormat.of().formatHex(digest().digest(data)); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static byte[] verified(Path path, String expected) throws IOException {
        var bytes = Files.readAllBytes(path);
        if (!hash(bytes).equals(expected)) throw new IllegalArgumentException("SHA-256 mismatch: " + path);
        return bytes;
    }
    private static Path resolve(Path base, String path) { return base.resolve(path).normalize(); }
    private static String required(Properties config, String key) {
        var value = config.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing scenario property: " + key);
        return value;
    }
}

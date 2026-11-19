package berryboy.desktop.profile;

import berryboy.Machine;
import berryboy.MachineState;
import berryboy.desktop.capture.JsonReport;
import berryboy.desktop.scenario.FrameInputs;
import berryboy.video.LcdTiming;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.LockSupport;
import jdk.jfr.Recording;

/** Repeated, paced 1x windows with guest/output accounting and optional separate JFR sampling. */
public final class PlaybackProfiler {
    private PlaybackProfiler() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: PlaybackProfiler scene.properties");
        var config = Path.of(args[0]).toAbsolutePath(); var p = new Properties();
        try (var reader = Files.newBufferedReader(config)) { p.load(reader); }
        var rom = verified(config, p, "rom"); var bios = verified(config, p, "bios");
        var state = verified(config, p, "state"); var input = verified(config, p, "frame_input");
        var inputs = FrameInputs.parse(input);
        int first = integer(p, "start_frame", 0, 1000000), frames = integer(p, "frames", 1, 3600);
        int warmups = integer(p, "warmups", 0, 10), repetitions = integer(p, "repetitions", 1, 10);
        if (inputs.firstFrame() >= 0 && (inputs.firstFrame() < first || inputs.lastFrame() >= first + frames))
            throw new IllegalArgumentException("Profile inputs must lie inside the measured window");
        var output = config.getParent().resolve(required(p, "output"));
        if (Files.exists(output)) throw new IllegalArgumentException("Profile output must be fresh");
        Files.createDirectories(output);
        var runs = new LinkedHashMap<String, Object>();
        String expectedState = null;
        for (int i = 0; i < warmups + repetitions; i++) {
            var result = run(rom, bios, state, inputs, first, frames);
            require(result, p, frames);
            if (expectedState == null) expectedState = (String) result.get("final_state_sha256");
            else if (!expectedState.equals(result.get("final_state_sha256")))
                throw new IllegalStateException("Complete guest state differs across profile repetitions");
            if (i >= warmups) runs.put("run_" + (i - warmups + 1), result);
            System.out.println((i < warmups ? "warmup " : "measured ") + (i + 1) + " complete");
        }
        if (Boolean.parseBoolean(p.getProperty("jfr", "false"))) {
            try (var recording = new Recording()) {
                recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10)).withStackTrace();
                recording.enable("jdk.ObjectAllocationSample").withStackTrace();
                recording.enable("jdk.GarbageCollection"); recording.enable("jdk.GCHeapSummary");
                var sampled = run(rom, bios, state, inputs, first, frames, recording); require(sampled, p, frames);
                if (!expectedState.equals(sampled.get("final_state_sha256")))
                    throw new IllegalStateException("JFR sampling changed the complete guest state");
                recording.dump(output.resolve("profile.jfr"));
                runs.put("jfr_diagnostic_run", sampled);
            }
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("scope", "Paced Linux host core plus bounded headless scaling/PCM/checksum sink; no physical display/audio or BBOS claim");
        report.put("source_commit", required(p, "source_commit")); report.put("source_dirty", required(p, "source_dirty"));
        report.put("hardware", required(p, "hardware")); report.put("java", System.getProperty("java.runtime.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch") + " " + System.getProperty("os.version"));
        report.put("available_processors", Runtime.getRuntime().availableProcessors());
        report.put("jvm_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments().toString());
        for (var key : new String[]{"rom", "bios", "state", "frame_input"}) report.put(key + "_sha256", p.getProperty(key + "_sha256"));
        report.put("start_frame", first); report.put("frames", frames); report.put("speed", 1); report.put("frame_skip", 0);
        report.put("sound", "100 percent stereo, unmuted; synthesis/drain/mix/checksum, physical transport absent");
        report.put("warmups", warmups); report.put("repetitions", repetitions); report.put("runs", runs);
        Files.writeString(output.resolve("report.json"), JsonReport.encode(report) + "\n");
    }

    static Map<String, Object> run(byte[] rom, byte[] bios, byte[] state, FrameInputs inputs,
                                    int first, int frames) throws Exception {
        return run(rom, bios, state, inputs, first, frames, null);
    }
    private static Map<String, Object> run(byte[] rom, byte[] bios, byte[] state, FrameInputs inputs,
                                           int first, int frames, Recording recording) throws Exception {
        var machine = MachineState.load(rom, bios, state);
        if (machine.clock.now() / LcdTiming.FRAME_CYCLES != first) throw new IllegalArgumentException("State frame does not match profile start");
        var stream = machine.memory.clockedAudio();
        if (stream.availableFrames() != 0 || stream.overruns() != 0 || stream.underruns() != 0)
            throw new IllegalArgumentException("Profile needs a drained lossless capture-enabled state");
        var playback = inputs.playback(machine); var pcm = new short[2048];
        var guest = new long[frames]; var guestAllocation = new long[frames];
        var drain = new long[frames]; var submit = new long[frames]; var work = new long[frames]; var late = new long[frames];
        long produced = stream.producedFrames(), consumed = stream.consumedFrames();
        long completed = machine.memory.raster().completedFrames(), samples = 0, wait = 0;
        var sink = new ProfileOutput();
        long gc = ProfileMetrics.gcCount(), gcMs = ProfileMetrics.gcMillis();
        if (recording != null) recording.start(); // Exclude scene loading, state buffers and scaler setup.
        long allocated = ProfileMetrics.allocated(), cpu = ProfileMetrics.cpu(), started = System.nanoTime();
        try (sink) {
            for (int i = 0; i < frames; i++) {
                long begin = System.nanoTime(); playback.afterFrame(first + i);
                long allocation = ProfileMetrics.allocated(), at = System.nanoTime();
                machine.runCycles((first + i + 1L) * LcdTiming.FRAME_CYCLES - machine.clock.now());
                guest[i] = System.nanoTime() - at; guestAllocation[i] = ProfileMetrics.allocated() - allocation;
                if (machine.memory.raster().completedFrames() != ++completed) throw new IllegalStateException("Guest frame lost");
                at = System.nanoTime(); int count = stream.availableFrames();
                if (count == 0) throw new IllegalStateException("Profile state must preserve enabled PCM capture");
                stream.drain(pcm, count); samples += count;
                drain[i] = System.nanoTime() - at;
                at = System.nanoTime(); sink.submit(machine.memory.raster().completedFrame(), pcm, count);
                submit[i] = System.nanoTime() - at; work[i] = System.nanoTime() - begin;
                long deadline = started + (i + 1L) * LcdTiming.FRAME_CYCLES * 1000000000L / 16777216;
                late[i] = Math.max(0, System.nanoTime() - deadline);
                at = System.nanoTime();
                while (System.nanoTime() < deadline) {
                    LockSupport.parkNanos(deadline - System.nanoTime());
                    if (Thread.interrupted()) throw new InterruptedException("Profile interrupted");
                }
                wait += System.nanoTime() - at;
            }
        }
        long elapsed = System.nanoTime() - started;
        long ownerAllocation = ProfileMetrics.allocated() - allocated, ownerCpu = ProfileMetrics.cpu() - cpu;
        if (recording != null) recording.stop(); // Exclude final serialization/report construction.
        var result = new LinkedHashMap<String, Object>();
        result.put("wall_ns", elapsed); result.put("guest_ns", ProfileMetrics.distribution(guest));
        result.put("guest_allocated_bytes", ProfileMetrics.distribution(guestAllocation));
        result.put("drain_ns", ProfileMetrics.distribution(drain)); result.put("snapshot_submit_ns", ProfileMetrics.distribution(submit));
        result.put("owner_work_ns", ProfileMetrics.distribution(work)); result.put("owner_cpu_ns", ownerCpu);
        result.put("owner_allocated_bytes", ownerAllocation); result.put("pacing_wait_ns", wait);
        result.put("producer_deadline_late_ns", ProfileMetrics.distribution(late));
        result.put("producer_deadline_misses", java.util.Arrays.stream(late).filter(v -> v > 0).count());
        result.put("gc_count", ProfileMetrics.gcCount() - gc); result.put("gc_collection_ms", ProfileMetrics.gcMillis() - gcMs);
        result.put("produced_audio_frames", stream.producedFrames() - produced); result.put("consumed_audio_frames", stream.consumedFrames() - consumed);
        result.put("audio_frames", samples); result.put("audio_overruns", stream.overruns()); result.put("audio_underruns", stream.underruns());
        result.put("output", sink.result());
        result.put("final_state_sha256", hash(MachineState.save(machine)));
        return result;
    }
    private static void require(Map<String, Object> result, Properties p, int frames) {
        var output = (Map<?, ?>) result.get("output");
        if (!required(p, "expected_rgb_chain_sha256").equals(output.get("rgb_chain_sha256"))
                || !required(p, "expected_pcm_sha256").equals(output.get("pcm_sha256"))
                || ((Number) output.get("frames")).longValue() != frames
                || ((Number) result.get("audio_overruns")).longValue() != 0
                || ((Number) result.get("audio_underruns")).longValue() != 0
                || !result.get("produced_audio_frames").equals(result.get("consumed_audio_frames"))
                || !result.get("produced_audio_frames").equals(output.get("audio_frames")))
            throw new IllegalStateException("Profile differs from the accepted M2 RGB/PCM or loses output");
    }
    private static int integer(Properties p, String key, int min, int max) {
        int value = Integer.parseInt(required(p, key));
        if (value < min || value > max) throw new IllegalArgumentException("Invalid profile " + key);
        return value;
    }
    private static String required(Properties p, String key) {
        var value = p.getProperty(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key); return value;
    }
    private static byte[] verified(Path config, Properties p, String key) throws Exception {
        var bytes = Files.readAllBytes(config.getParent().resolve(required(p, key)));
        if (!hash(bytes).equals(required(p, key + "_sha256"))) throw new IllegalArgumentException("Profile hash mismatch: " + key);
        return bytes;
    }
    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(ProfileOutput.digest().digest(bytes)); }
}

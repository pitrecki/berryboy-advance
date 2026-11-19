package berryboy.desktop.profile;

import berryboy.desktop.concurrent.BoundedWorker;
import berryboy.mobile.audio.OutputMixer;
import berryboy.mobile.settings.PhoneOptions;
import berryboy.mobile.video.FrameScaler;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded headless presentation/audio sink receiving owned data only; no device transport. */
final class ProfileOutput implements AutoCloseable {
    record Frame(int[] rgb, short[] pcm, int count) {
        Frame {
            if (rgb.length != 240 * 160 || count < 0 || count > pcm.length / 2 || count > 1024)
                throw new IllegalArgumentException("Invalid owned output frame");
            rgb = rgb.clone(); pcm = Arrays.copyOf(pcm, count * 2);
        }
    }
    private final BoundedWorker<Frame> worker;
    private final FrameScaler scaler = new FrameScaler();
    private final PhoneOptions options = new PhoneOptions();
    private final MessageDigest video = digest(), audio = digest(), frameHash = digest();
    private final byte[] rgbBytes = new byte[240 * 160 * 3], pcmBytes = new byte[4096];
    private long frames, samples, hashNanos, scaleNanos, mixNanos, allocated, cpu;
    private int renderedSample;
    private boolean closed;
    private Map<String, Object> result;

    ProfileOutput() {
        options.setVolume(100); // Full stereo sound, normal speed, no skips.
        scaler.scale(new int[240 * 160], 640, 426); // Cache coordinate maps before measurement.
        worker = new BoundedWorker<>("berryboy-profile-output", 2, this::process);
    }
    void submit(int[] rgb, short[] pcm, int count) throws InterruptedException {
        worker.submit(new Frame(rgb, pcm, count));
    }
    private void process(Frame frame) {
        long allocation = ProfileMetrics.allocated(), processor = ProfileMetrics.cpu();
        long start = System.nanoTime();
        OutputMixer.apply(frame.pcm, frame.pcm.length / 2, options);
        mixNanos += System.nanoTime() - start;
        start = System.nanoTime();
        int[] rendered = scaler.scale(frame.rgb, 640, 426);
        renderedSample ^= rendered[0] ^ rendered[rendered.length - 1];
        scaleNanos += System.nanoTime() - start;
        start = System.nanoTime();
        for (int i = 0; i < frame.rgb.length; i++) {
            rgbBytes[i * 3] = (byte) (frame.rgb[i] >>> 16);
            rgbBytes[i * 3 + 1] = (byte) (frame.rgb[i] >>> 8); rgbBytes[i * 3 + 2] = (byte) frame.rgb[i];
        }
        video.update(frameHash.digest(rgbBytes));
        for (int i = 0; i < frame.pcm.length; i++) {
            pcmBytes[i * 2] = (byte) frame.pcm[i]; pcmBytes[i * 2 + 1] = (byte) (frame.pcm[i] >>> 8);
        }
        audio.update(pcmBytes, 0, frame.pcm.length * 2);
        hashNanos += System.nanoTime() - start;
        frames++; samples += frame.pcm.length / 2;
        cpu += ProfileMetrics.cpu() - processor;
        allocated += ProfileMetrics.allocated() - allocation;
    }
    Map<String, Object> result() {
        if (!closed) throw new IllegalStateException("Close output before inspecting results");
        return result;
    }
    private Map<String, Object> finish() {
        var result = new LinkedHashMap<String, Object>();
        var queue = worker.statistics();
        result.put("frames", frames); result.put("audio_frames", samples);
        result.put("rgb_chain_sha256", HexFormat.of().formatHex(video.digest()));
        result.put("pcm_sha256", HexFormat.of().formatHex(audio.digest()));
        result.put("scaling_ns", scaleNanos); result.put("mix_ns", mixNanos); result.put("verification_hash_ns", hashNanos);
        result.put("processing_cpu_ns", cpu); result.put("processing_allocated_bytes", allocated);
        result.put("queue_capacity", queue.capacity()); result.put("queue_maximum", queue.maximumQueued());
        result.put("submitted", queue.submitted()); result.put("processed", queue.processed());
        result.put("blocked_submissions", queue.blockedSubmissions()); result.put("blocked_ns", queue.blockedNanos());
        result.put("rendered_sample", renderedSample);
        return result;
    }
    @Override public void close() {
        if (closed) return;
        worker.close(); result = Map.copyOf(finish()); closed = true;
    }
    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

package berryboy.desktop.capture;

import berryboy.desktop.concurrent.BoundedWorker;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import javax.imageio.ImageIO;

/** Host capture actor: owns hashing/PNG/WAV/trace IO and receives deep snapshots. */
public final class ScenarioCapture implements AutoCloseable {
    public record Result(String videoSha256, String audioSha256) {
    }

    record Frame(int number, long cycle, int pc, int cpsr, int[] pixels, byte[] pcm, int bytes,
                 boolean first, boolean last, byte[] videoState) {
        Frame {
            if (pixels.length != 240 * 160 || bytes < 0 || bytes > pcm.length || bytes % 4 != 0) {
                throw new IllegalArgumentException("Invalid capture snapshot");
            }
            pixels = pixels.clone();
            pcm = Arrays.copyOf(pcm, bytes);
            videoState = videoState == null ? null : videoState.clone();
        }

        Frame(int number, long cycle, int pc, int cpsr, int[] pixels, byte[] pcm, int bytes,
              boolean first, boolean last) {
            this(number, cycle, pc, cpsr, pixels, pcm, bytes, first, last, null);
        }
    }

    private final Path output;
    private final BufferedWriter trace;
    private final WaveRecorder wave;
    private final MessageDigest video = digest();
    private final MessageDigest audio = digest();
    private final byte[] rgb = new byte[240 * 160 * 3];
    private final BoundedWorker<Frame> worker;
    private Result result;
    private boolean closed;

    public ScenarioCapture(Path output) throws IOException {
        this.output = output;
        trace = Files.newBufferedWriter(output.resolve("frames.jsonl"));
        try { wave = new WaveRecorder(output.resolve("audio.wav")); }
        catch (IOException error) { trace.close(); throw error; }
        worker = new BoundedWorker<>("berryboy-capture", 2, this::write);
    }

    public void submit(int frame, long cycle, int pc, int cpsr, int[] pixels, byte[] pcm, int bytes,
                       boolean first, boolean last) throws InterruptedException {
        worker.submit(new Frame(frame, cycle, pc, cpsr, pixels, pcm, bytes, first, last));
    }

    public void submit(int frame, long cycle, int pc, int cpsr, int[] pixels, byte[] pcm, int bytes,
                       boolean first, boolean last, byte[] videoState) throws InterruptedException {
        worker.submit(new Frame(frame, cycle, pc, cpsr, pixels, pcm, bytes, first, last, videoState));
    }

    private void write(Frame frame) throws IOException {
        audio.update(frame.pcm()); wave.write(frame.pcm(), frame.pcm().length);
        for (int i = 0; i < frame.pixels().length; i++) {
            rgb[i * 3] = (byte) (frame.pixels()[i] >>> 16);
            rgb[i * 3 + 1] = (byte) (frame.pixels()[i] >>> 8); rgb[i * 3 + 2] = (byte) frame.pixels()[i];
        }
        video.update(rgb);
        var checkpoint = new LinkedHashMap<String, Object>();
        checkpoint.put("frame", frame.number()); checkpoint.put("cycle", frame.cycle());
        checkpoint.put("pc", frame.pc()); checkpoint.put("cpsr", frame.cpsr());
        checkpoint.put("rgb_sha256", HexFormat.of().formatHex(digest().digest(rgb)));
        checkpoint.put("audio_frames", frame.pcm().length / 4);
        if (frame.videoState() != null) {
            Files.write(output.resolve("video-state-" + frame.number() + ".bin"), frame.videoState());
            checkpoint.put("video_state_sha256", HexFormat.of().formatHex(digest().digest(frame.videoState())));
        }
        trace.write(JsonReport.encode(checkpoint)); trace.newLine();
        if (frame.first() || frame.last() || frame.videoState() != null) {
            var image = new BufferedImage(240, 160, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 240, 160, frame.pixels(), 0, 240);
            if (frame.first()) save(image, "first.png");
            if (frame.last()) save(image, "last.png");
            if (frame.videoState() != null) save(image, "frame-" + frame.number() + ".png");
        }
    }

    private void save(BufferedImage image, String name) throws IOException {
        if (!ImageIO.write(image, "png", output.resolve(name).toFile())) throw new IOException("PNG encoder unavailable");
    }

    public Result result() {
        if (result == null) throw new IllegalStateException("Capture must close successfully before reading results");
        return result;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        Result captured;
        try {
            worker.close();
            captured = new Result(HexFormat.of().formatHex(video.digest()), HexFormat.of().formatHex(audio.digest()));
        } finally {
            try { trace.close(); } finally { wave.close(); }
        }
        result = captured;
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

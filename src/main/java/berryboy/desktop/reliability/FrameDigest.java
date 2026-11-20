package berryboy.desktop.reliability;

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Constant-memory frame-chain and PCM accounting; keeps no gameplay history or guest buffers. */
public final class FrameDigest {
    public record Result(long frames, long audioFrames, String rgbChainSha256, String pcmSha256) { }
    private final MessageDigest frame = sha256(), video = sha256(), audio = sha256();
    private final byte[] rgb = new byte[240 * 160 * 3], pcm = new byte[4096], hash = new byte[32];
    private long frames, samples;
    private Result finished;

    public void add(FrameGate.Frame value) {
        add(value.rgb(), value.pcm(), value.pcm().length / 2);
    }
    /** Synchronously consumes a single owner's buffers; retains neither array. */
    public void add(int[] pixels, short[] samples, int count) {
        if (finished != null) throw new IllegalStateException("Digest already finished");
        if (pixels.length != 240 * 160 || count < 1 || count > 1024 || count * 2 > samples.length
                || (samples.length & 1) != 0)
            throw new IllegalArgumentException("Expected one full RGB/stereo PCM frame");
        int offset = 0;
        for (var pixel : pixels) {
            rgb[offset++] = (byte) (pixel >>> 16); rgb[offset++] = (byte) (pixel >>> 8); rgb[offset++] = (byte) pixel;
        }
        frame.update(rgb);
        try { frame.digest(hash, 0, hash.length); }
        catch (DigestException impossible) { throw new IllegalStateException(impossible); }
        video.update(hash);
        offset = 0;
        for (int i = 0; i < count * 2; i++) { var sample = samples[i]; pcm[offset++] = (byte) sample; pcm[offset++] = (byte) (sample >>> 8); }
        audio.update(pcm, 0, offset); frames++; this.samples += count;
    }
    /** Finalizes once; repeated calls retain the same report rather than consuming digests again. */
    public Result finish() {
        if (finished == null) finished = new Result(frames, samples,
                HexFormat.of().formatHex(video.digest()), HexFormat.of().formatHex(audio.digest()));
        return finished;
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

package berryboy.mobile;

/** Small PCM16 stereo WAV packets for the phone MMAPI transport. */
public final class PcmWave {
    public static final int MAX_FRAMES = 4096;
    private PcmWave() { }
    public static byte[] encode(short[] pcm, int frames) {
        if (frames < 1 || frames > MAX_FRAMES || frames > pcm.length / 2) throw new IllegalArgumentException("Invalid PCM packet");
        var data = new byte[44 + frames * 4];
        text(data, 0, "RIFF"); integer(data, 4, data.length - 8);
        text(data, 8, "WAVEfmt "); integer(data, 16, 16);
        data[20] = 1; data[22] = 2;
        integer(data, 24, 32768); integer(data, 28, 32768 * 4);
        data[32] = 4; data[34] = 16;
        text(data, 36, "data"); integer(data, 40, frames * 4);
        for (var i = 0; i < frames * 2; i++) { data[44 + i * 2] = (byte) pcm[i]; data[45 + i * 2] = (byte) (pcm[i] >>> 8); }
        return data;
    }
    private static void integer(byte[] data, int offset, int value) {
        for (var i = 0; i < 4; i++) data[offset + i] = (byte) (value >>> (i * 8));
    }
    private static void text(byte[] data, int offset, String value) {
        for (var i = 0; i < value.length(); i++) data[offset + i] = (byte) value.charAt(i);
    }
}

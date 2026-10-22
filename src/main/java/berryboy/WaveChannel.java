package berryboy;

/** Channel 3 banked 4-bit wave RAM and programmable sample playback. */
final class WaveChannel {
    private final byte[] wave = new byte[32];
    private int control;
    private int volume;
    private int frequency;
    private int remaining;
    private int phase;
    private int position;
    private boolean active;

    int readControl() { return control; }
    int readVolume() { return volume << 8; }
    int readFrequency() { return frequency & 0x4000; }
    boolean active() { return active; }

    void writeControl(int value) {
        control = value & 0xe0;
        if ((control & 0x80) == 0) active = false;
    }

    void writeLength(int value) { remaining = (256 - (value & 255)) * (AudioUnit.SAMPLE_RATE / 256); }
    void writeVolume(int value) { volume = value & 0xe0; }

    void writeFrequency(int index, int value) {
        frequency = (frequency & ~(255 << (index * 8))) | ((value & 255) << (index * 8));
        if ((frequency & 0x8000) != 0) {
            active = (control & 0x80) != 0;
            position = 0; phase = 0;
            if (remaining == 0) remaining = 256 * (AudioUnit.SAMPLE_RATE / 256);
            frequency &= ~0x8000;
        }
    }

    int readWave(int index) { return wave[accessBank() * 16 + index] & 255; }
    void writeWave(int index, int value) { wave[accessBank() * 16 + index] = (byte) value; }

    int nextSample() {
        if (!active) return 0;
        var bank = ((control >>> 6) & 1) ^ (((control & 0x20) != 0) ? position / 32 : 0);
        var data = wave[bank * 16 + (position & 31) / 2] & 255;
        var sample = ((data >>> ((position & 1) == 0 ? 4 : 0)) & 15) - 8;
        var level = (volume >>> 5) & 3;
        sample = (volume & 0x80) != 0 ? sample * 3 / 4 : level == 0 ? 0 : sample >> (level - 1);
        var period = AudioUnit.SAMPLE_RATE * (2048 - (frequency & 2047));
        phase += 2097152;
        while (phase >= period) {
            phase -= period;
            position = (position + 1) & ((control & 0x20) != 0 ? 63 : 31);
        }
        if ((frequency & 0x4000) != 0 && --remaining <= 0) active = false;
        return sample;
    }

    void reset() { control = 0; volume = 0; frequency = 0; remaining = 0; active = false; }
    private int accessBank() { return ((control >>> 6) & 1) ^ 1; }
}

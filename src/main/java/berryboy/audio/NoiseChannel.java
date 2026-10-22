package berryboy.audio;

/** Channel 4 envelope and 15/7-bit linear-feedback noise generator. */
final class NoiseChannel {
    private int length;
    private int envelope;
    private int frequency;
    private int remaining;
    private int envelopeSamples;
    private int volume;
    private int phase;
    private int lfsr = 0x7fff;
    private boolean active;

    int readControl() { return envelope << 8; }
    int readFrequency() { return frequency & 0x40ff; }
    boolean active() { return active; }
    void writeLength(int value) { length = value & 63; remaining = (64 - length) * (AudioUnit.SAMPLE_RATE / 256); }
    void writeEnvelope(int value) { envelope = value & 255; if ((envelope & 0xf8) == 0) active = false; }

    void writeFrequency(int index, int value) {
        frequency = (frequency & ~(255 << (index * 8))) | ((value & 255) << (index * 8));
        if ((frequency & 0x8000) != 0) {
            active = (envelope & 0xf8) != 0;
            volume = envelope >>> 4; envelopeSamples = 0; phase = 0; lfsr = 0x7fff;
            if (remaining == 0) remaining = 64 * (AudioUnit.SAMPLE_RATE / 256);
            frequency &= ~0x8000;
        }
    }

    int nextSample() {
        if (!active) return 0;
        var sample = (lfsr & 1) == 0 ? volume : -volume;
        var divisor = frequency & 7;
        var rate = (divisor == 0 ? 1048576 : 524288 / divisor) >>> (((frequency >>> 4) & 15) + 1);
        phase += rate;
        while (phase >= AudioUnit.SAMPLE_RATE) {
            phase -= AudioUnit.SAMPLE_RATE;
            var bit = (lfsr ^ (lfsr >>> 1)) & 1;
            lfsr = (lfsr >>> 1) | (bit << 14);
            if ((frequency & 8) != 0) lfsr = (lfsr & ~0x40) | (bit << 6);
        }
        var envelopePeriod = (envelope & 7) * (AudioUnit.SAMPLE_RATE / 64);
        if (envelopePeriod != 0 && ++envelopeSamples >= envelopePeriod) {
            envelopeSamples = 0;
            if ((envelope & 8) != 0 && volume < 15) volume++;
            if ((envelope & 8) == 0 && volume > 0) volume--;
        }
        if ((frequency & 0x4000) != 0 && --remaining <= 0) active = false;
        return sample;
    }

    void reset() { length = 0; envelope = 0; frequency = 0; remaining = 0; active = false; }
}

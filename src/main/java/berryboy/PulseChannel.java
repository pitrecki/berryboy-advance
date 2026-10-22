package berryboy;

/** Channel 2 tone generator, advanced by output samples rather than CPU cycles. */
final class PulseChannel {
    private static final int FREQUENCY_CLOCK = 131072;
    private static final int FREQUENCY_RANGE = 2048;
    private static final int FREQUENCY_MASK = 0x07ff;
    private static final int LENGTH_ENABLED = 0x4000;
    private static final int RESTART = 0x8000;
    private static final int ENVELOPE_INCREASE = 0x08;
    private static final int MAX_VOLUME = 15;
    private static final int SAMPLES_PER_LENGTH_TICK = AudioUnit.SAMPLE_RATE / 256;
    private static final int SAMPLES_PER_ENVELOPE_TICK = AudioUnit.SAMPLE_RATE / 64;

    private int dutyAndLength;
    private int envelope;
    private int frequencyControl;
    private int remainingSamples;
    private int envelopeSamples;
    private int volume;
    private int phase;
    private boolean active;

    int readControl() {
        return (envelope << 8) | (dutyAndLength & 0xc0);
    }

    int readFrequency() {
        return frequencyControl & LENGTH_ENABLED;
    }

    boolean active() {
        return active;
    }

    int frequency() { return frequencyControl & FREQUENCY_MASK; }

    void sweepFrequency(int value) { frequencyControl = (frequencyControl & ~FREQUENCY_MASK) | value; }

    void silence() { active = false; }

    void writeDutyAndLength(int value) {
        dutyAndLength = value & 0xff;
        remainingSamples = (64 - (value & 0x3f)) * SAMPLES_PER_LENGTH_TICK;
    }

    void writeEnvelope(int value) {
        envelope = value & 0xff;
        if (!dacEnabled()) {
            active = false;
        }
    }

    void writeFrequencyByte(int byteIndex, int value) {
        var shift = byteIndex * 8;
        frequencyControl = (frequencyControl & ~(0xff << shift)) | ((value & 0xff) << shift);
        phase %= period();
        if ((frequencyControl & RESTART) != 0) {
            restart();
            frequencyControl &= ~RESTART;
        }
    }

    int nextSample() {
        if (!active) {
            return 0;
        }
        var sample = phase * 8 < period() * dutySteps() ? volume : -volume;
        phase = (phase + FREQUENCY_CLOCK) % period();
        advanceEnvelope();
        if ((frequencyControl & LENGTH_ENABLED) != 0 && --remainingSamples <= 0) {
            active = false;
        }
        return sample;
    }

    void reset() {
        dutyAndLength = 0;
        envelope = 0;
        frequencyControl = 0;
        remainingSamples = 0;
        envelopeSamples = 0;
        volume = 0;
        phase = 0;
        active = false;
    }

    private void restart() {
        active = dacEnabled();
        volume = envelope >>> 4;
        envelopeSamples = 0;
        phase = 0;
        if (remainingSamples == 0) {
            remainingSamples = 64 * SAMPLES_PER_LENGTH_TICK;
        }
    }

    private boolean dacEnabled() {
        return (envelope & 0xf8) != 0;
    }

    private int period() {
        return AudioUnit.SAMPLE_RATE * (FREQUENCY_RANGE - (frequencyControl & FREQUENCY_MASK));
    }

    private int dutySteps() {
        return switch (dutyAndLength >>> 6) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 4;
            default -> 6;
        };
    }

    private void advanceEnvelope() {
        var stepTime = envelope & 7;
        if (stepTime == 0 || ++envelopeSamples < stepTime * SAMPLES_PER_ENVELOPE_TICK) {
            return;
        }
        envelopeSamples = 0;
        if ((envelope & ENVELOPE_INCREASE) != 0) {
            if (volume < MAX_VOLUME) {
                volume++;
            }
        } else if (volume > 0) {
            volume--;
        }
    }
}

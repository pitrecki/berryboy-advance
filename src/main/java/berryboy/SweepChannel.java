package berryboy;

/** Channel 1 pulse oscillator with the 128 Hz frequency sweep unit. */
final class SweepChannel {
    final PulseChannel pulse = new PulseChannel();
    private int sweep;
    private int shadow;
    private int remaining;
    private boolean sweepEnabled;
    private boolean negated;

    void writeSweep(int value) {
        if (negated && (value & 8) == 0) pulse.silence();
        sweep = value & 0x7f;
    }

    int readSweep() { return sweep; }

    void writeFrequency(int byteIndex, int value) {
        pulse.writeFrequencyByte(byteIndex, value);
        if (byteIndex == 1 && (value & 0x80) != 0) {
            shadow = pulse.frequency();
            negated = false;
            remaining = period();
            sweepEnabled = (sweep & 0x77) != 0;
            if ((sweep & 7) != 0) calculate(false);
        }
    }

    int nextSample() {
        var sample = pulse.nextSample();
        if (sweepEnabled && --remaining <= 0) {
            remaining = period();
            if ((sweep & 0x70) != 0 && (sweep & 7) != 0) {
                calculate(true);
                calculate(false);
            }
        }
        return sample;
    }

    void reset() { pulse.reset(); sweep = 0; remaining = 0; sweepEnabled = false; negated = false; }

    private int period() {
        var value = (sweep >>> 4) & 7;
        return (value == 0 ? 8 : value) * (AudioUnit.SAMPLE_RATE / 128);
    }

    private void calculate(boolean apply) {
        var delta = shadow >>> (sweep & 7);
        var frequency = (sweep & 8) != 0 ? shadow - delta : shadow + delta;
        if ((sweep & 8) != 0) negated = true;
        if (frequency > 2047) pulse.silence();
        else if (apply) { shadow = frequency; pulse.sweepFrequency(frequency); }
    }
}

package berryboy.audio;

import berryboy.timing.CycleEvent;
import berryboy.timing.CycleScheduler;

/** Fixed-rate sample clock with optional bounded capture and explicit buffer statistics. */
public final class ClockedAudio implements CycleEvent {
    public static final int CYCLES_PER_SAMPLE = CycleScheduler.CLOCK_HZ / AudioUnit.SAMPLE_RATE;
    private static final int CAPACITY = 8192;
    private final CycleScheduler clock;
    private final AudioUnit audio;
    private final short[] sample = new short[2];
    private final short[] buffer = new short[CAPACITY * 2];
    private int head;
    private int size;
    private boolean capture;
    private long produced;
    private long consumed;
    private long overruns;
    private long underruns;

    public ClockedAudio(CycleScheduler clock, AudioUnit audio) {
        this.clock = clock;
        this.audio = audio;
        clock.schedule(this, clock.now() + CYCLES_PER_SAMPLE);
    }

    /** Capture is opt-in; diagnostics may execute without an attached audio sink. */
    public void setCapture(boolean enabled) { capture = enabled; head = 0; size = 0; }
    public int availableFrames() { return size; }
    public long producedFrames() { return produced; }
    public long consumedFrames() { return consumed; }
    public long overruns() { return overruns; }
    public long underruns() { return underruns; }

    public int drain(short[] output) {
        if (output == null) throw new IllegalArgumentException("Complete stereo frames required");
        return drain(output, output.length / 2);
    }

    public int drain(short[] output, int requested) {
        if (output == null || (output.length & 1) != 0 || requested < 0 || requested > output.length / 2) {
            throw new IllegalArgumentException("Complete stereo frames required");
        }
        var count = requested < size ? requested : size;
        for (var i = 0; i < count; i++) {
            output[i * 2] = buffer[head * 2];
            output[i * 2 + 1] = buffer[head * 2 + 1];
            head = (head + 1) % CAPACITY;
        }
        for (var i = count * 2; i < requested * 2; i++) output[i] = 0;
        size -= count;
        consumed += count;
        if (count < requested) underruns++;
        return count;
    }

    public void fire() {
        audio.render(sample);
        produced++;
        if (capture) {
            if (size == CAPACITY) overruns++;
            else {
                var tail = (head + size) % CAPACITY;
                buffer[tail * 2] = sample[0]; buffer[tail * 2 + 1] = sample[1]; size++;
            }
        }
        clock.schedule(this, clock.now() + CYCLES_PER_SAMPLE);
    }
}

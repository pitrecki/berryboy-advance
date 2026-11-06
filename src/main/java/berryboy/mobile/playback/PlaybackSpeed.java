package berryboy.mobile.playback;

import berryboy.timing.CycleScheduler;

/** Host pacing and submission cadence; never changes guest device clocks. */
public final class PlaybackSpeed {
    private PlaybackSpeed() { }
    public static void requireFactor(int factor) {
        if (factor != 1 && factor != 2 && factor != 4 && factor != 8) throw new IllegalArgumentException("Speed must be 1x, 2x, 4x or 8x");
    }
    public static int cycle(int factor, int direction) {
        requireFactor(factor);
        if (direction != -1 && direction != 1) throw new IllegalArgumentException("Invalid speed direction");
        return direction == 1 ? (factor == 8 ? 1 : factor * 2) : (factor == 1 ? 8 : factor / 2);
    }
    public static long wallMillis(long guestCycles, int factor) {
        requireFactor(factor);
        if (guestCycles < 0) throw new IllegalArgumentException("Negative guest duration");
        var frequency = (long) CycleScheduler.CLOCK_HZ * factor;
        // Divide first so long-running sessions cannot overflow cycles * 1000.
        return guestCycles / frequency * 1000 + guestCycles % frequency * 1000 / frequency;
    }
    public static int presentationInterval(int frameSkip, int factor) {
        requireFactor(factor);
        if (frameSkip < 0 || frameSkip > 5) throw new IllegalArgumentException("Invalid frame skip");
        return (frameSkip + 1) * factor;
    }
}

package berryboy;

/** GBA bus transfer costs without Game Pak prefetch or display-bus contention. */
public final class BusTiming {
    private static final int[] FIRST_WAIT = {5, 4, 3, 9};
    private final CycleScheduler clock;
    private int waitControl;
    private boolean nextFetchSequential;
    private int expectedFetch;
    private int nextData;
    private int dataRegion = -1;
    private boolean dataWrite;

    public BusTiming(CycleScheduler clock) {
        this.clock = clock;
    }

    public int waitControl() {
        return waitControl;
    }

    public void setWaitControl(int value) {
        if ((value & 0x4000) != 0) {
            throw new EmulationException("Game Pak prefetch timing is not implemented");
        }
        waitControl = value & 0x5fff;
    }

    public int accessCycles(int address, int width, boolean sequential) {
        var region = address >>> 24;
        if (region >= 8 && region <= 13) {
            var bank = (region - 8) / 2;
            var shift = 2 + bank * 3;
            var first = FIRST_WAIT[(waitControl >>> shift) & 3];
            var second = (waitControl & (1 << (shift + 2))) != 0 ? 2 : (2 << bank) + 1;
            // Each 128 KiB Game Pak boundary starts a nonsequential access.
            if ((address & 0x1ffff) == 0) sequential = false;
            return (sequential ? second : first) + (width == 4 ? second : 0);
        }
        if (region == 2) return width == 4 ? 6 : 3;
        if (region == 5 || region == 6) return width == 4 ? 2 : 1;
        if (region == 14 || region == 15) return FIRST_WAIT[waitControl & 3];
        return 1; // BIOS, IWRAM, IO and OAM use a 32-bit bus.
    }

    void fetch(int address, int width) {
        clock.advance(accessCycles(address, width, nextFetchSequential && address == expectedFetch));
        expectedFetch = address + width;
        nextFetchSequential = true;
        dataRegion = -1;
    }

    void data(int address, int width, boolean write) {
        var region = address >>> 24;
        var sequential = region == dataRegion && address == nextData && write == dataWrite;
        clock.advance(accessCycles(address, width, sequential));
        nextData = address + width;
        dataRegion = region;
        dataWrite = write;
        nextFetchSequential = false;
    }

    void refill(int address, int width) {
        clock.advance(accessCycles(address, width, false));
        clock.advance(accessCycles(address + width, width, true));
        expectedFetch = address;
        nextFetchSequential = true;
    }

    void invalidateFetch() {
        nextFetchSequential = false;
    }
}

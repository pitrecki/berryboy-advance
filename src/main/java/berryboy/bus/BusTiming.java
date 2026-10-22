package berryboy.bus;

import berryboy.EmulationException;
import berryboy.timing.CycleScheduler;

/** GBA bus transfer costs and an eight-halfword Game Pak opcode prefetch model. */
public final class BusTiming {
    private static final int[] FIRST_WAIT = {5, 4, 3, 9};
    private final CycleScheduler clock;
    private int waitControl;
    private boolean nextFetchSequential;
    private int expectedFetch;
    private int nextData;
    private int dataRegion = -1;
    private boolean dataWrite;
    private int prefetched;
    private int prefetchCredit;
    private int ewramCycles = 3;
    private boolean externalRam = true;

    public BusTiming(CycleScheduler clock) {
        this.clock = clock;
    }

    public int waitControl() {
        return waitControl;
    }

    public void setWaitControl(int value) {
        waitControl = value & 0x5fff;
        invalidateFetch();
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
        if (region == 2) return externalRam ? ewramCycles * (width == 4 ? 2 : 1) : 1;
        if (region == 5 || region == 6) return width == 4 ? 2 : 1;
        if (region == 14 || region == 15) return FIRST_WAIT[waitControl & 3];
        return 1; // BIOS, IWRAM, IO and OAM use a 32-bit bus.
    }

    void setMemoryControl(int value) {
        if (((value >>> 24) & 15) == 15) throw new EmulationException("WRAM wait setting locks up hardware");
        externalRam = (value & 0x20) != 0;
        ewramCycles = 16 - ((value >>> 24) & 15);
    }

    public void fetch(int address, int width) {
        var sequential = nextFetchSequential && address == expectedFetch;
        var cost = accessCycles(address, width, sequential);
        var hit = false;
        if (prefetchEnabled() && gamePakRom(address) && sequential) {
            var halfwords = width / 2;
            if (prefetched >= halfwords) { prefetched -= halfwords; cost = 1; hit = true; }
            else {
                var missingAddress = address + prefetched * 2;
                var missing = halfwords - prefetched;
                cost = -prefetchCredit;
                for (var i = 0; i < missing; i++) cost += accessCycles(missingAddress + i * 2, 2, true);
                if (cost < 1) cost = 1;
                prefetched = 0; prefetchCredit = 0;
            }
        } else { prefetched = 0; prefetchCredit = 0; }
        expectedFetch = address + width;
        nextFetchSequential = true;
        dataRegion = -1;
        clock.advance(cost);
        if (hit) fillPrefetch(1);
    }

    void data(int address, int width, boolean write) {
        var region = address >>> 24;
        var sequential = region == dataRegion && address == nextData && write == dataWrite;
        var cost = accessCycles(address, width, sequential);
        clock.advance(cost);
        nextData = address + width;
        dataRegion = region;
        dataWrite = write;
        if (prefetchEnabled() && (address >>> 24) < 8) fillPrefetch(cost);
        else invalidateFetch();
    }

    public void internal(int cycles, boolean branch) {
        clock.advance(cycles);
        if (!branch && cycles > 0) {
            if (prefetchEnabled()) fillPrefetch(cycles);
            else if (gamePakRom(expectedFetch)) invalidateFetch(); // Documented prefetch-disable N-cycle bug.
        }
    }

    public void refill(int address, int width) {
        invalidateFetch();
        clock.advance(accessCycles(address, width, false));
        clock.advance(accessCycles(address + width, width, true));
        expectedFetch = address;
        nextFetchSequential = true;
    }

    public void invalidateFetch() {
        nextFetchSequential = false;
        prefetched = 0;
        prefetchCredit = 0;
    }

    private boolean prefetchEnabled() { return (waitControl & 0x4000) != 0; }

    private static boolean gamePakRom(int address) { return (address >>> 24) >= 8 && (address >>> 24) <= 13; }

    private void fillPrefetch(int freeCycles) {
        if (!nextFetchSequential || !gamePakRom(expectedFetch)) return;
        prefetchCredit += freeCycles;
        while (prefetched < 8) {
            var cost = accessCycles(expectedFetch + prefetched * 2, 2, true);
            if (prefetchCredit < cost) break;
            prefetchCredit -= cost;
            prefetched++;
        }
        if (prefetched == 8) prefetchCredit = 0;
    }
}

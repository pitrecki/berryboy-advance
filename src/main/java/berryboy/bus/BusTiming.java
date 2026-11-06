package berryboy.bus;

import berryboy.state.StateBuffer;
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
    private boolean prefetchActive;
    private int ewramCycles = 3;
    private boolean externalRam = true;
    private int refillAddress;
    private int refillWidth;
    private int refillRemaining;

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
            var second = sequentialRomCycles(address);
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
        fetch(address, address, width);
    }

    /** IRQ discards a pipeline fetch two instruction slots beyond logical PC. */
    public void discardedFetch(int address, int width) {
        fetch(address, address + width * 2, width);
    }

    private void fetch(int address, int busAddress, int width) {
        var sequential = nextFetchSequential && address == expectedFetch;
        var cost = accessCycles(busAddress, width, sequential);
        var hit = false;
        if (prefetchEnabled() && gamePakRom(busAddress) && prefetchActive && address == expectedFetch) {
            var halfwords = width / 2;
            if (prefetched >= halfwords) { prefetched -= halfwords; cost = 1; hit = true; }
            else {
                var missing = halfwords - prefetched;
                // An active prefetch stream retains its sequential duty even
                // across a Game Pak page boundary or an off-cartridge DMA.
                cost = missing * sequentialRomCycles(busAddress) - prefetchCredit;
                if (cost < 1) cost = 1;
                prefetched = 0; prefetchCredit = 0;
            }
        } else clearPrefetch();
        expectedFetch = address + width;
        nextFetchSequential = true;
        prefetchActive = prefetchEnabled() && gamePakRom(busAddress);
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

    /** Begin two refill accesses; the CPU arbitrates DMA before each one. */
    public void beginRefill(int address, int width) {
        if (refilling()) throw new EmulationException("Pipeline refill already pending");
        if (width != 2 && width != 4) throw new IllegalArgumentException("Invalid refill width");
        invalidateFetch();
        refillAddress = address;
        refillWidth = width;
        refillRemaining = 2;
    }

    public boolean refilling() { return refillRemaining != 0; }

    /** Advance one access, respecting DMA invalidation between the two reads. */
    public void refillNext() {
        if (!refilling()) throw new EmulationException("No pending pipeline refill");
        var second = refillRemaining == 1;
        var address = refillAddress + (second ? refillWidth : 0);
        var cycles = accessCycles(address, refillWidth, second && nextFetchSequential);
        nextFetchSequential = true;
        clock.advance(cycles);
        if (--refillRemaining == 0) expectedFetch = refillAddress;
    }

    public void cancelRefill() {
        refillRemaining = 0;
        invalidateFetch();
    }

    public void invalidateFetch() {
        nextFetchSequential = false;
        clearPrefetch();
    }

    private void clearPrefetch() {
        prefetchActive = false;
        prefetched = 0;
        prefetchCredit = 0;
    }

    /** DMA interrupts CPU sequencing; off-cartridge DMA leaves prefetch running. */
    public void invalidateCpuSequence() {
        nextFetchSequential = false;
        dataRegion = -1;
    }

    /** Time a DMA access while the independent Game Pak prefetch unit can run. */
    public void dmaAccess(int address, int width, boolean sequential) {
        if ((address >>> 24) >= 8) clearPrefetch();
        var cycles = accessCycles(address, width, sequential);
        clock.advance(cycles);
        fillPrefetch(cycles);
    }

    public void dmaIdle(int cycles) {
        clock.advance(cycles);
        fillPrefetch(cycles);
    }

    private boolean prefetchEnabled() { return (waitControl & 0x4000) != 0; }

    private static boolean gamePakRom(int address) { return (address >>> 24) >= 8 && (address >>> 24) <= 13; }

    private int sequentialRomCycles(int address) {
        var bank = ((address >>> 24) - 8) / 2;
        return (waitControl & (1 << (4 + bank * 3))) != 0 ? 2 : (2 << bank) + 1;
    }

    private void fillPrefetch(int freeCycles) {
        if (!prefetchActive || !gamePakRom(expectedFetch)) return;
        prefetchCredit += freeCycles;
        while (prefetched < 8) {
            var cost = sequentialRomCycles(expectedFetch);
            if (prefetchCredit < cost) break;
            prefetchCredit -= cost;
            prefetched++;
        }
        if (prefetched == 8) prefetchCredit = 0;
    }

    /** Device-owned fields for versioned machine state; call only between CPU steps. */
    public void transferState(StateBuffer state) {
        waitControl = state.value(waitControl);
        expectedFetch = state.value(expectedFetch);
        nextData = state.value(nextData);
        dataRegion = state.value(dataRegion);
        prefetched = state.value(prefetched);
        prefetchCredit = state.value(prefetchCredit);
        ewramCycles = state.value(ewramCycles);
        refillAddress = state.value(refillAddress);
        refillWidth = state.value(refillWidth);
        refillRemaining = state.value(refillRemaining);
        nextFetchSequential = state.flag(nextFetchSequential);
        dataWrite = state.flag(dataWrite);
        prefetchActive = state.flag(prefetchActive);
        externalRam = state.flag(externalRam);
    }
}

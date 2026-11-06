package berryboy.timing;

import berryboy.state.StateBuffer;

/** IE/IF/IME interrupt registers. IF uses write-one-to-clear semantics. */
public final class InterruptController {
    private int enabled;
    private int requested;
    private int master;

    public void request(int mask) { requested |= mask & 0x3fff; }

    public boolean wakePending() { return (enabled & requested) != 0; }

    public boolean irqPending() { return master != 0 && wakePending(); }

    public static boolean handles(int address) {
        return (address >= 0x04000200 && address <= 0x04000203)
                || (address >= 0x04000208 && address <= 0x0400020b);
    }

    public int read8(int address) {
        var offset = address - 0x04000200;
        // IME is a four-byte register with only bit 0 implemented. Word loads
        // used by interrupt libraries must read zero from its reserved lanes.
        var value = offset < 2 ? enabled : offset < 4 ? requested : offset == 8 ? master : 0;
        return (value >>> ((offset & 1) * 8)) & 255;
    }

    public void write8(int address, int value) {
        var offset = address - 0x04000200;
        var shift = (offset & 1) * 8;
        value &= 255;
        if (offset < 2) enabled = ((enabled & ~(255 << shift)) | (value << shift)) & 0x3fff;
        else if (offset < 4) requested &= ~(value << shift);
        else if (offset == 8) master = value & 1;
    }

    /** Device-owned fields for versioned machine state; call only between CPU steps. */
    public void transferState(StateBuffer state) {
        enabled = state.value(enabled);
        requested = state.value(requested);
        master = state.value(master);
    }
}

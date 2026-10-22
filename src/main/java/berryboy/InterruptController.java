package berryboy;

/** IE/IF/IME interrupt registers. IF uses write-one-to-clear semantics. */
public final class InterruptController {
    private int enabled;
    private int requested;
    private int master;

    public void request(int mask) { requested |= mask & 0x3fff; }

    public boolean wakePending() { return (enabled & requested) != 0; }

    public boolean irqPending() { return master != 0 && wakePending(); }

    static boolean handles(int address) {
        return (address >= 0x04000200 && address <= 0x04000203)
                || (address >= 0x04000208 && address <= 0x04000209);
    }

    int read8(int address) {
        var offset = address - 0x04000200;
        var value = offset < 2 ? enabled : offset < 4 ? requested : master;
        return (value >>> ((offset & 1) * 8)) & 255;
    }

    void write8(int address, int value) {
        var offset = address - 0x04000200;
        var shift = (offset & 1) * 8;
        value &= 255;
        if (offset < 2) enabled = ((enabled & ~(255 << shift)) | (value << shift)) & 0x3fff;
        else if (offset < 4) requested &= ~(value << shift);
        else if (offset == 8) master = value & 1;
    }
}

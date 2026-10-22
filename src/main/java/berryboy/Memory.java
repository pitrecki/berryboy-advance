package berryboy;

/** Little-endian GBA bus with mirrored RAM and explicit unsupported hardware errors. */
public final class Memory {
    public static final int EWRAM_BASE = 0x02000000;
    public static final int IWRAM_BASE = 0x03000000;
    public static final int IO_BASE = 0x04000000;
    public static final int PALETTE_BASE = 0x05000000;
    public static final int VRAM_BASE = 0x06000000;
    public static final int OAM_BASE = 0x07000000;
    public static final int ROM_BASE = 0x08000000;

    static final int DISPLAY_CONTROL = IO_BASE;
    static final int BACKGROUND_2_CONTROL = IO_BASE + 0x0c;
    static final int BACKGROUND_2_TRANSFORM = IO_BASE + 0x20;
    public static final int KEY_INPUT = IO_BASE + 0x130;

    private static final int BIOS_SIZE = 0x4000;

    private final Cartridge cartridge;
    private final byte[] externalRam = new byte[0x40000];
    private final byte[] internalRam = new byte[0x8000];
    private final byte[] ioRegisters = new byte[0x400];
    private final byte[] palette = new byte[0x400];
    private final byte[] videoRam = new byte[0x18000];
    private final byte[] objectAttributes = new byte[0x400];

    private byte[] bios;
    private final Keypad keypad = new Keypad();
    private final AudioUnit audio = new AudioUnit();
    private final CycleScheduler clock = new CycleScheduler();
    private final BusTiming bus = new BusTiming(clock);
    private final InterruptController interrupts = new InterruptController();
    private final LcdTiming lcd = new LcdTiming(clock, interrupts);
    private final TimerUnit timers = new TimerUnit(clock, interrupts);
    private final DmaController dma = new DmaController(this);
    private final RasterPpu raster = new RasterPpu(this);
    private long videoVersion;
    private boolean cpuAccess;
    private boolean halted;
    private int postFlag;
    private int keyControl;
    private boolean keyMatched;

    public Memory(Cartridge cartridge) {
        this.cartridge = cartridge;
        lcd.attachDma(dma);
    }

    public void setBios(byte[] image) {
        if (image == null || image.length != BIOS_SIZE) {
            throw new IllegalArgumentException("BIOS must be 16 KiB");
        }
        bios = new byte[image.length];
        System.arraycopy(image, 0, bios, 0, image.length);
    }

    public boolean hasBios() {
        return bios != null;
    }

    /** GBA bit order: A, B, Select, Start, Right, Left, Up, Down, R, L. */
    public void setPressedKeys(int mask) {
        keypad.setPressed(mask);
        updateKeyInterrupt();
    }

    public AudioUnit audio() {
        return audio;
    }

    public CycleScheduler clock() { return clock; }

    public BusTiming busTiming() { return bus; }

    public InterruptController interrupts() { return interrupts; }

    public LcdTiming lcd() { return lcd; }

    public DmaController dma() { return dma; }

    public RasterPpu raster() { return raster; }

    long videoVersion() { return videoVersion; }

    public boolean halted() { return halted; }

    void wake() { halted = false; }

    int fetch(int address, boolean thumb) {
        bus.fetch(address, thumb ? 2 : 4);
        cpuAccess = true;
        return thumb ? rawRead16(address) : rawRead16(address) | (rawRead16(address + 2) << 16);
    }

    void finishInstruction() { cpuAccess = false; }

    public int read8(int address) {
        if (cpuAccess) bus.data(address, 1, false);
        return rawRead8(address);
    }

    private int rawRead8(int address) {
        return switch (address >>> 24) {
            case 0x00 -> readBios(address);
            case 0x02 -> externalRam[address & 0x3ffff] & 0xff;
            case 0x03 -> internalRam[address & 0x7fff] & 0xff;
            case 0x04 -> readIo(address);
            case 0x05 -> palette[address & 0x3ff] & 0xff;
            case 0x06 -> videoRam[videoRamOffset(address)] & 0xff;
            case 0x07 -> objectAttributes[address & 0x3ff] & 0xff;
            case 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d -> readCartridge(address);
            default -> unavailable(address);
        };
    }

    public int read16(int address) {
        if (cpuAccess) bus.data(address, 2, false);
        return rawRead16(address);
    }

    private int rawRead16(int address) {
        return rawRead8(address) | (rawRead8(address + 1) << 8);
    }

    int peek8(int address) { return rawRead8(address); }

    int peek16(int address) { return rawRead16(address); }

    public int read32(int address) {
        if (cpuAccess) bus.data(address, 4, false);
        return rawRead16(address) | (rawRead16(address + 2) << 16);
    }

    public void write8(int address, int value) {
        if (cpuAccess) bus.data(address, 1, true);
        var region = address >>> 24;
        if (region == 0x05) {
            writeRepeatedByte(address, value);
        } else if (region == 0x06) {
            writeVideoByte(address, value);
        } else if (region != 0x07) {
            writeByteLane(address, value);
        }
        // The OAM bus ignores byte stores.
    }

    public void write16(int address, int value) {
        if (cpuAccess) bus.data(address, 2, true);
        rawWrite16(address, value);
    }

    private void rawWrite16(int address, int value) {
        var alignedAddress = address & ~1;
        writeByteLane(alignedAddress, value);
        writeByteLane(alignedAddress + 1, value >>> 8);
    }

    public void write32(int address, int value) {
        if (cpuAccess) bus.data(address, 4, true);
        var alignedAddress = address & ~3;
        rawWrite16(alignedAddress, value);
        rawWrite16(alignedAddress + 2, value >>> 16);
    }

    private int readBios(int address) {
        if (bios != null && address < bios.length) {
            return bios[address] & 0xff;
        }
        return unavailable(address);
    }

    private int readCartridge(int address) {
        var offset = address & 0x1ffffff;
        if (offset < cartridge.size()) {
            return cartridge.byteAt(offset);
        }
        // Undriven ROM space returns the low 16 bits of the halfword address.
        var halfwordAddress = address >>> 1;
        var byteShift = (address & 1) * 8;
        return (halfwordAddress >>> byteShift) & 0xff;
    }

    private int readIo(int address) {
        if (address >= IO_BASE + 0xb0 && address < IO_BASE + 0xe0) return dma.read8(address);
        if (address == IO_BASE + 0x300) return postFlag;
        if (address == IO_BASE + 0x301) return 0;
        if ((address & ~1) == KEY_INPUT + 2) return (keyControl >>> ((address & 1) * 8)) & 255;
        if (InterruptController.handles(address)) return interrupts.read8(address);
        if (address >= IO_BASE + 4 && address <= IO_BASE + 7) return lcd.read8(address);
        if (address >= IO_BASE + 0x100 && address < IO_BASE + 0x110) return timers.read8(address);
        if ((address & ~1) == IO_BASE + 0x204) return (bus.waitControl() >>> ((address & 1) * 8)) & 0xff;
        if (address == KEY_INPUT) {
            return keypad.readInput() & 0xff;
        }
        if (address == KEY_INPUT + 1) {
            return keypad.readInput() >>> 8;
        }
        if (AudioUnit.handles(address)) {
            return audio.read8(address);
        }
        if (isDisplayRegister(address)) {
            return ioRegisters[address - IO_BASE] & 0xff;
        }
        return unavailable(address);
    }

    private void writeIo(int address, int value) {
        if (address >= IO_BASE + 0xb0 && address < IO_BASE + 0xe0) { dma.write8(address, value); return; }
        if (address == IO_BASE + 0x300) { postFlag |= value & 1; return; }
        if (address == IO_BASE + 0x301) {
            if ((value & 0x80) != 0) throw new EmulationException("STOP oscillator behavior is not implemented");
            halted = true;
            return;
        }
        if ((address & ~1) == KEY_INPUT + 2) {
            var shift = (address & 1) * 8;
            keyControl = ((keyControl & ~(255 << shift)) | ((value & 255) << shift)) & 0xc3ff;
            updateKeyInterrupt();
            return;
        }
        if (InterruptController.handles(address)) { interrupts.write8(address, value); return; }
        if (address >= IO_BASE + 4 && address <= IO_BASE + 7) { lcd.write8(address, value); return; }
        if (address >= IO_BASE + 0x100 && address < IO_BASE + 0x110) { timers.write8(address, value); return; }
        if ((address & ~1) == IO_BASE + 0x204) {
            var shift = (address & 1) * 8;
            bus.setWaitControl((bus.waitControl() & ~(255 << shift)) | ((value & 255) << shift));
            return;
        }
        if (address == KEY_INPUT || address == KEY_INPUT + 1) {
            return; // KEYINPUT is read-only.
        }
        if (AudioUnit.handles(address)) {
            audio.write8(address, value);
            return;
        }
        if (!isDisplayRegister(address)) {
            unavailable(address);
        }
        ioRegisters[address - IO_BASE] = (byte) value;
        videoVersion++;
        raster.affineWrite(address);
    }

    private void writeRepeatedByte(int address, int value) {
        // Palette and background VRAM repeat byte writes across both bus lanes.
        rawWrite16(address & ~1, (value & 0xff) * 0x101);
    }

    private void writeVideoByte(int address, int value) {
        var offset = videoRamOffset(address);
        var displayMode = ioRegisters[0] & 7;
        var objectTilesStart = displayMode <= 2 ? 0x10000 : 0x14000;
        if (offset < objectTilesStart) {
            writeRepeatedByte(address, value);
        }
    }

    private void writeByteLane(int address, int value) {
        switch (address >>> 24) {
            case 0x00:
                if (address >= BIOS_SIZE) {
                    unavailable(address);
                }
                break;
            case 0x02:
                externalRam[address & 0x3ffff] = (byte) value;
                break;
            case 0x03:
                internalRam[address & 0x7fff] = (byte) value;
                break;
            case 0x04:
                writeIo(address, value);
                break;
            case 0x05:
                palette[address & 0x3ff] = (byte) value;
                videoVersion++;
                break;
            case 0x06:
                videoRam[videoRamOffset(address)] = (byte) value;
                videoVersion++;
                break;
            case 0x07:
                objectAttributes[address & 0x3ff] = (byte) value;
                videoVersion++;
                break;
            case 0x08:
            case 0x09:
            case 0x0a:
            case 0x0b:
            case 0x0c:
            case 0x0d:
                break; // Cartridge ROM is read-only.
            default:
                unavailable(address);
                break;
        }
    }

    private static boolean isDisplayRegister(int address) {
        return address >= IO_BASE && address < IO_BASE + 0x56;
    }

    private void updateKeyInterrupt() {
        var selected = keyControl & 0x3ff;
        var pressed = ~keypad.readInput() & selected;
        var match = selected != 0 && ((keyControl & 0x8000) != 0 ? pressed == selected : pressed != 0);
        if (match && !keyMatched && (keyControl & 0x4000) != 0) interrupts.request(1 << 12);
        keyMatched = match;
    }

    private static int videoRamOffset(int address) {
        var offset = address & 0x1ffff;
        return offset >= 0x18000 ? offset - 0x8000 : offset;
    }

    private static int unavailable(int address) {
        throw new EmulationException("Unimplemented bus / IO address 0x" + Integer.toHexString(address));
    }
}

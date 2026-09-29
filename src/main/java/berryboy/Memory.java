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

    public Memory(Cartridge cartridge) {
        this.cartridge = cartridge;
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
    }

    public AudioUnit audio() {
        return audio;
    }

    public int read8(int address) {
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
        return read8(address) | (read8(address + 1) << 8);
    }

    public int read32(int address) {
        return read16(address) | (read16(address + 2) << 16);
    }

    public void write8(int address, int value) {
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
        var alignedAddress = address & ~1;
        writeByteLane(alignedAddress, value);
        writeByteLane(alignedAddress + 1, value >>> 8);
    }

    public void write32(int address, int value) {
        var alignedAddress = address & ~3;
        write16(alignedAddress, value);
        write16(alignedAddress + 2, value >>> 16);
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
    }

    private void writeRepeatedByte(int address, int value) {
        // Palette and background VRAM repeat byte writes across both bus lanes.
        write16(address & ~1, (value & 0xff) * 0x101);
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
                break;
            case 0x06:
                videoRam[videoRamOffset(address)] = (byte) value;
                break;
            case 0x07:
                objectAttributes[address & 0x3ff] = (byte) value;
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
        // Only registers consumed by the current bitmap snapshot renderer.
        return address == DISPLAY_CONTROL || address == DISPLAY_CONTROL + 1
                || address == BACKGROUND_2_CONTROL || address == BACKGROUND_2_CONTROL + 1
                || (address >= BACKGROUND_2_TRANSFORM && address < BACKGROUND_2_TRANSFORM + 16);
    }

    private static int videoRamOffset(int address) {
        var offset = address & 0x1ffff;
        return offset >= 0x18000 ? offset - 0x8000 : offset;
    }

    private static int unavailable(int address) {
        throw new EmulationException("Unimplemented bus / IO address 0x" + Integer.toHexString(address));
    }
}

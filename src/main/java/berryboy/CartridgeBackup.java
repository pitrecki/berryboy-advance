package berryboy;

/** Portable SRAM, Flash and serial EEPROM storage; host adapters own file persistence. */
public final class CartridgeBackup {
    public static final int SRAM = 0;
    public static final int FLASH_64K = 1;
    public static final int FLASH_128K = 2;
    public static final int EEPROM_512 = 3;
    public static final int EEPROM_8K = 4;
    private final int type;
    private final byte[] data;
    private boolean dirty;
    private int unlock;
    private int command;
    private int bank;
    private boolean identify;
    private final int[] serial = new int[81];
    private int serialCount;
    private int responseBit = -1;
    private int responseAddress;

    public CartridgeBackup(int type) {
        if (type < SRAM || type > EEPROM_8K) throw new IllegalArgumentException("Unknown backup type");
        this.type = type;
        data = new byte[type == SRAM ? 0x8000 : type == FLASH_64K ? 0x10000
                : type == FLASH_128K ? 0x20000 : type == EEPROM_512 ? 512 : 8192];
        erase(0, data.length);
        dirty = false;
    }

    public int type() { return type; }
    public boolean isEeprom() { return type == EEPROM_512 || type == EEPROM_8K; }
    public boolean dirty() { return dirty; }
    public void markSaved() { dirty = false; }

    public byte[] snapshot() {
        var copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        return copy;
    }

    public void restore(byte[] image) {
        if (image == null || image.length != data.length) throw new IllegalArgumentException("Backup size mismatch");
        System.arraycopy(image, 0, data, 0, data.length);
        dirty = false;
        unlock = 0; command = 0; bank = 0; identify = false; serialCount = 0; responseBit = -1;
    }

    public int read8(int address) {
        if (isEeprom()) return 255;
        var offset = address & 0xffff;
        if (identify) {
            if (offset == 0) return 0xc2; // Macronix.
            if (offset == 1) return type == FLASH_128K ? 0x09 : 0x1c;
        }
        return data[(bank * 0x10000 + offset) & (data.length - 1)] & 255;
    }

    public void write8(int address, int value) {
        value &= 255;
        var offset = address & 0xffff;
        if (type == SRAM) { data[offset & 0x7fff] = (byte) value; dirty = true; return; }
        if (isEeprom()) return;
        if (command == 0xa0) { // Program byte (only one-to-zero without erase).
            data[bank * 0x10000 + offset] &= (byte) value;
            dirty = true; command = 0; return;
        }
        if (value == 0xf0) { identify = false; unlock = 0; command = 0; return; }
        if (command == 0xb0) {
            if (offset == 0 && type == FLASH_128K) bank = value & 1;
            command = 0; return;
        }
        if (unlock == 0 && offset == 0x5555 && value == 0xaa) { unlock = 1; return; }
        if (unlock == 1 && offset == 0x2aaa && value == 0x55) { unlock = 2; return; }
        if (unlock == 2) {
            unlock = 0;
            if (command == 0x80) {
                if (offset == 0x5555 && value == 0x10) erase(0, data.length);
                else if (value == 0x30) erase(bank * 0x10000 + (offset & 0xf000), 0x1000);
                command = 0;
            } else if (offset == 0x5555) {
                if (value == 0x90) identify = true;
                else if (value == 0xa0 || value == 0x80 || value == 0xb0) command = value;
            }
            return;
        }
        unlock = 0;
    }

    public void writeSerial(int value) {
        responseBit = -1;
        if (serialCount == 0 && (value & 1) == 0) return; // Commands begin with one.
        serial[serialCount++] = value & 1;
        var addressBits = type == EEPROM_512 ? 6 : 14;
        if (serialCount < 2) return;
        var read = serial[1] == 1;
        var expected = 2 + addressBits + (read ? 0 : 64) + 1;
        if (serialCount < expected) return;
        if (serial[expected - 1] != 0) { serialCount = 0; return; }
        int block = 0;
        for (var i = 2; i < 2 + addressBits; i++) block = (block << 1) | serial[i];
        var address = (block * 8) & (data.length - 1);
        if (read) { responseAddress = address; responseBit = 0; }
        else {
            for (var i = 0; i < 8; i++) {
                int sample = 0;
                for (var bit = 0; bit < 8; bit++) sample = (sample << 1) | serial[2 + addressBits + i * 8 + bit];
                data[address + i] = (byte) sample;
            }
            dirty = true;
        }
        serialCount = 0;
    }

    public int readSerial() {
        if (responseBit < 0 || responseBit >= 68) return 1; // Ready; write busy latency is not modeled.
        var bit = responseBit++ - 4;
        return bit < 0 ? 0 : (data[responseAddress + bit / 8] >>> (7 - bit % 8)) & 1;
    }

    private void erase(int start, int length) {
        for (var i = start; i < start + length; i++) data[i] = (byte) 255;
        dirty = true;
    }
}

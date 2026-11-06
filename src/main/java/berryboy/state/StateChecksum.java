package berryboy.state;

/** CRC32 for accidental corruption and cartridge/BIOS identity, not authentication. */
public final class StateChecksum {
    private static final int[] TABLE = table();
    private StateChecksum() { }
    public static int bytes(byte[] data, int length) {
        var crc = -1;
        for (var i = 0; i < length; i++) crc = next(crc, data[i]);
        return ~crc;
    }
    public static int next(int crc, int octet) { return TABLE[(crc ^ octet) & 255] ^ (crc >>> 8); }
    private static int[] table() {
        var values = new int[256];
        for (var i = 0; i < values.length; i++) {
            var crc = i;
            for (var bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) != 0 ? 0xedb88320 : 0);
            values[i] = crc;
        }
        return values;
    }
}

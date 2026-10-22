package berryboy;

/** Immutable ROM image and the small set of header fields used by the launcher. */
public final class Cartridge {
    public static final int MAX_SIZE = 32 * 1024 * 1024;
    private final byte[] rom;

    public Cartridge(byte[] image) {
        if (image == null || image.length < 0xC0 || image.length > MAX_SIZE) {
            throw new IllegalArgumentException("GBA ROM must be 192 bytes to 32 MiB");
        }
        rom = new byte[image.length];
        System.arraycopy(image, 0, rom, 0, image.length);
    }

    public int size() {
        return rom.length;
    }

    public int backupType() {
        if (contains("FLASH1M_V")) return CartridgeBackup.FLASH_128K;
        if (contains("FLASH512_V") || contains("FLASH_V")) return CartridgeBackup.FLASH_64K;
        if (contains("EEPROM_V")) return CartridgeBackup.EEPROM_8K;
        return CartridgeBackup.SRAM;
    }

    private boolean contains(String marker) {
        for (var offset = 0; offset <= rom.length - marker.length(); offset++) {
            var i = 0;
            while (i < marker.length() && (rom[offset + i] & 255) == marker.charAt(i)) i++;
            if (i == marker.length()) return true;
        }
        return false;
    }

    public int byteAt(int offset) {
        return offset >= 0 && offset < rom.length ? rom[offset] & 0xff : 0xff;
    }
    public String title() {
        return ascii(0xa0, 12);
    }

    public String gameCode() {
        return ascii(0xac, 4);
    }

    public boolean headerChecksumValid() {
        var sum = 0x19;
        for (var offset = 0xa0; offset <= 0xbd; offset++) {
            sum += byteAt(offset);
        }
        return (sum & 0xff) == 0;
    }

    private String ascii(int start, int length) {
        var text = new StringBuffer();
        for (var offset = 0; offset < length; offset++) {
            var character = byteAt(start + offset);
            if (character == 0) {
                break;
            }
            text.append(character >= 32 && character <= 126 ? (char) character : '?');
        }
        return text.toString();
    }
}

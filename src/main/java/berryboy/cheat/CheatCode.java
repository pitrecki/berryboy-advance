package berryboy.cheat;

import berryboy.bus.Memory;

/** Explicit RAM writes. Encrypted/conditional formats are rejected, never guessed. */
public final class CheatCode {
    public static final int RAW = 0;
    public static final int CODE_BREAKER = 1;
    private final String source;
    private final int format;
    private final int address;
    private final int width;
    private final int value;

    public CheatCode(String source, int format) {
        if (source == null || source.length() > 64) throw new IllegalArgumentException("Invalid cheat code");
        this.source = source; this.format = format;
        var compact = new StringBuffer();
        for (var i = 0; i < source.length(); i++) {
            var c = source.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') compact.append(c);
        }
        var text = compact.toString();
        if (format == RAW) {
            var first = -1; var second = -1;
            for (var i = 0; i < text.length(); i++) if (text.charAt(i) == ':') {
                if (first < 0) first = i;
                else if (second < 0) second = i;
                else throw new IllegalArgumentException("Use ADDRESS:8|16|32:VALUE in hex");
            }
            if (first != 8 || second < first + 2 || second > first + 3) throw new IllegalArgumentException("Use ADDRESS:8|16|32:VALUE in hex");
            address = hex(text, 0, first);
            var bits = 0;
            for (var i = first + 1; i < second; i++) {
                var c = text.charAt(i);
                if (c < '0' || c > '9') throw new IllegalArgumentException("Invalid cheat width");
                bits = bits * 10 + c - '0';
            }
            if (bits != 8 && bits != 16 && bits != 32) throw new IllegalArgumentException("Cheat width must be 8, 16 or 32");
            width = bits / 8; value = hex(text, second + 1, text.length());
        } else if (format == CODE_BREAKER) {
            if (text.length() != 12) throw new IllegalArgumentException("Use XXXXXXXX YYYY CodeBreaker");
            var command = hex(text, 0, 8);
            var type = command >>> 28;
            if (type != 3 && type != 8) throw new IllegalArgumentException("Supported CodeBreaker types: 3 (8-bit), 8 (16-bit), unencrypted");
            address = command & 0x0fffffff; width = type == 3 ? 1 : 2;
            value = hex(text, 8, 12);
        } else throw new IllegalArgumentException("Unknown cheat format");
        var ewram = address >= 0x02000000 && address <= 0x02040000 - width;
        var iwram = address >= 0x03000000 && address <= 0x03008000 - width;
        if ((!ewram && !iwram) || (address & (width - 1)) != 0) throw new IllegalArgumentException("Cheats require aligned EWRAM/IWRAM addresses");
        if ((width == 1 && (value & ~255) != 0) || (width == 2 && (value & ~65535) != 0)) {
            throw new IllegalArgumentException("Cheat value exceeds width");
        }
    }
    public String source() { return source; }
    public int format() { return format; }
    public void apply(Memory memory) {
        if (width == 1) memory.write8(address, value);
        else if (width == 2) memory.write16(address, value);
        else memory.write32(address, value);
    }
    private static int hex(String text, int start, int end) {
        if (end <= start || end - start > 8) throw new IllegalArgumentException("Invalid hex length");
        var value = 0;
        for (var i = start; i < end; i++) {
            var c = text.charAt(i);
            var digit = c >= '0' && c <= '9' ? c - '0' : c >= 'A' && c <= 'F' ? c - 'A' + 10
                    : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
            if (digit < 0) throw new IllegalArgumentException("Invalid hex digit");
            value = (value << 4) | digit;
        }
        return value;
    }
}

package berryboy.state;

/** Bounded little-endian state transfer. The owning device defines its fixed field order. */
public final class StateBuffer {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private byte[] data;
    private int position;
    private final boolean reading;

    public StateBuffer() { data = new byte[1024]; reading = false; }
    public StateBuffer(byte[] image) {
        if (image == null || image.length > MAX_BYTES) throw new IllegalArgumentException("Invalid state size");
        data = image; reading = true;
    }
    public int value(int current) {
        int result = 0;
        for (var i = 0; i < 4; i++) result |= octet(current >>> (i * 8)) << (i * 8);
        return result;
    }
    public long value(long current) {
        var low = value((int) current);
        var high = value((int) (current >>> 32));
        return (low & 0xffffffffL) | ((long) high << 32);
    }
    public boolean flag(boolean current) {
        var value = octet(current ? 1 : 0);
        if (value > 1) throw new IllegalArgumentException("Invalid state flag");
        return value != 0;
    }
    public void bytes(byte[] array) { for (var i = 0; i < array.length; i++) array[i] = (byte) octet(array[i]); }
    public void ints(int[] array) { for (var i = 0; i < array.length; i++) array[i] = value(array[i]); }
    public void shorts(short[] array) { for (var i = 0; i < array.length; i++) array[i] = (short) value(array[i]); }
    public void flags(boolean[] array) { for (var i = 0; i < array.length; i++) array[i] = flag(array[i]); }
    public boolean reading() { return reading; }
    public void requireEnd() {
        if (!reading || position != data.length) throw new IllegalArgumentException("State has trailing or missing fields");
    }
    public byte[] finish() {
        var copy = new byte[position];
        System.arraycopy(data, 0, copy, 0, position);
        return copy;
    }
    private int octet(int current) {
        if (reading) {
            if (position == data.length) throw new IllegalArgumentException("Truncated state");
            return data[position++] & 255;
        }
        if (position == MAX_BYTES) throw new IllegalArgumentException("State exceeds size limit");
        if (position == data.length) {
            var bigger = new byte[data.length * 2];
            System.arraycopy(data, 0, bigger, 0, position); data = bigger;
        }
        data[position++] = (byte) current;
        return current & 255;
    }
}

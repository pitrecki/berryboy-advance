package berryboy.desktop.capture;

import berryboy.video.VideoBus;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Raw end-of-frame video state, not a trace of register changes during drawing. */
public final class VideoState {
    private static final int[] ADDRESSES = {0x04000000, 0x05000000, 0x06000000, 0x07000000};
    private static final int[] LENGTHS = {0x56, 0x400, 0x18000, 0x400};

    private VideoState() {
    }

    /** Called on the emulation owner; returns independently owned bytes. */
    public static byte[] snapshot(VideoBus memory) {
        var size = 8 + ADDRESSES.length * 8;
        for (var length : LENGTHS) size += length;
        var output = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        output.put("BBVID001".getBytes(StandardCharsets.US_ASCII));
        for (var region = 0; region < ADDRESSES.length; region++) {
            output.putInt(ADDRESSES[region]); output.putInt(LENGTHS[region]);
            for (var offset = 0; offset < LENGTHS[region]; offset++) {
                output.put((byte) memory.peek8(ADDRESSES[region] + offset));
            }
        }
        return output.array();
    }
}

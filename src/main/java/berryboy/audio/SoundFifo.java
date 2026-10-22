package berryboy.audio;

/** 32-byte signed PCM FIFO. Timer overflows latch one sample; DMA refills at <=16. */
final class SoundFifo {
    private final byte[] samples = new byte[32];
    private int head;
    private int size;
    private int output;

    void push(int value) {
        if (size < 32) { samples[(head + size) & 31] = (byte) value; size++; }
    }

    void tick() {
        output = size == 0 ? 0 : samples[head];
        if (size > 0) { head = (head + 1) & 31; size--; }
    }

    int output() { return output; }
    boolean needsDma() { return size <= 16; }
    void reset() { head = 0; size = 0; output = 0; }
}

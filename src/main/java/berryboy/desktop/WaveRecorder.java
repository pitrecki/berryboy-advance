package berryboy.desktop;

import berryboy.AudioUnit;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/** Stream PCM16 without retaining a complete benchmark recording in memory. */
final class WaveRecorder implements AutoCloseable {
    private final RandomAccessFile file;
    private long bytes;

    WaveRecorder(Path path) throws IOException {
        file = new RandomAccessFile(path.toFile(), "rw");
        file.setLength(44);
        file.seek(44);
    }

    void write(byte[] pcm, int count) throws IOException {
        if (bytes + count > 0xffffffffL - 36) throw new IOException("WAV exceeds RIFF size limit");
        file.write(pcm, 0, count); bytes += count;
    }

    public void close() throws IOException {
        try {
            var header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(0x46464952).putInt((int) bytes + 36).putInt(0x45564157);
            header.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 2);
            header.putInt(AudioUnit.SAMPLE_RATE).putInt(AudioUnit.SAMPLE_RATE * 4).putShort((short) 4).putShort((short) 16);
            header.putInt(0x61746164).putInt((int) bytes);
            file.seek(0); file.write(header.array());
        } finally { file.close(); }
    }
}

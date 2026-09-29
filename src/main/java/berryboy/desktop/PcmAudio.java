package berryboy.desktop;

import berryboy.AudioUnit;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/** Linux/desktop PCM output. Device access never enters the portable core. */
final class PcmAudio implements AutoCloseable {
    static final AudioFormat FORMAT = new AudioFormat(AudioUnit.SAMPLE_RATE, 16, 2, true, false);
    private static final int DEVICE_BUFFER_BYTES = 8192;
    private final SourceDataLine line;
    private byte[] buffer = new byte[0];

    private PcmAudio(SourceDataLine line) {
        this.line = line;
    }

    static PcmAudio open() throws LineUnavailableException {
        var line = AudioSystem.getSourceDataLine(FORMAT);
        try {
            line.open(FORMAT, DEVICE_BUFFER_BYTES);
            line.start();
            return new PcmAudio(line);
        } catch (LineUnavailableException | RuntimeException failure) {
            line.close();
            throw failure;
        }
    }

    void write(short[] samples) {
        if (buffer.length != samples.length * Short.BYTES) {
            buffer = new byte[samples.length * Short.BYTES];
        }
        encode(samples, buffer);
        var offset = 0;
        while (offset < buffer.length && line.isOpen()) {
            var written = line.write(buffer, offset, buffer.length - offset);
            if (written <= 0) {
                break;
            }
            offset += written;
        }
    }

    void drain() {
        line.drain();
    }

    static void writeWave(Path output, short[] samples) throws IOException {
        if ((samples.length & 1) != 0) {
            throw new IllegalArgumentException("A WAV file requires complete stereo frames");
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        var bytes = new byte[samples.length * Short.BYTES];
        encode(samples, bytes);
        try (var stream = new AudioInputStream(new ByteArrayInputStream(bytes), FORMAT, samples.length / 2)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, output.toFile());
        }
    }

    private static void encode(short[] samples, byte[] output) {
        ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples);
    }

    @Override
    public void close() {
        line.close();
    }
}

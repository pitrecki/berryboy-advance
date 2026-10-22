package berryboy.desktop;

import berryboy.audio.AudioUnit;
import berryboy.cartridge.Cartridge;
import berryboy.bus.Memory;
import java.io.IOException;
import java.nio.file.Path;
import javax.sound.sampled.LineUnavailableException;

/** A deterministic one-second tone for testing the audio path without a game or BIOS. */
final class AudioDemo {
    private static final int FREQUENCY_A4 = 1750; // 131072 / (2048 - 1750) = about 440 Hz.
    private static final int VOLUME_12 = 12 << 12;
    private static final int DUTY_50_PERCENT = 2 << 6;
    private static final int ENVELOPE_STEP_2 = 2 << 8;
    private static final int MAX_STEREO_VOLUME = 0x77;

    private AudioDemo() {
    }

    static short[] samples() {
        var memory = new Memory(new Cartridge(new byte[192]));
        memory.write16(AudioUnit.MASTER_CONTROL, AudioUnit.MASTER_ENABLE);
        memory.write16(AudioUnit.STEREO_CONTROL,
                AudioUnit.CHANNEL_2_LEFT | AudioUnit.CHANNEL_2_RIGHT | MAX_STEREO_VOLUME);
        memory.write16(AudioUnit.MIX_CONTROL, AudioUnit.FULL_PSG_VOLUME);
        memory.write16(AudioUnit.CHANNEL_2_CONTROL, VOLUME_12 | DUTY_50_PERCENT | ENVELOPE_STEP_2);
        memory.write16(AudioUnit.CHANNEL_2_FREQUENCY, AudioUnit.RESTART_CHANNEL | FREQUENCY_A4);
        var samples = new short[AudioUnit.SAMPLE_RATE * 2];
        memory.audio().render(samples);
        return samples;
    }

    static void run(boolean headless) throws IOException {
        var samples = samples();
        var output = Path.of("target/audio-demo.wav");
        PcmAudio.writeWave(output, samples);
        System.out.println("Channel 2 tone, about 440 Hz, stereo PCM16: " + output.toAbsolutePath());
        if (!headless) {
            try (var audio = PcmAudio.open()) {
                audio.write(samples);
                audio.drain();
            } catch (LineUnavailableException | IllegalArgumentException | SecurityException unavailable) {
                System.err.println("Audio device unavailable; the WAV file is ready for playback. " + unavailable.getMessage());
            }
        }
    }
}

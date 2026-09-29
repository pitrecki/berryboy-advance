package berryboy.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import berryboy.AudioUnit;
import java.nio.file.Path;
import java.util.Arrays;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("desktop")
@DisplayName("Desktop PCM and WAV export without audio hardware")
class PcmAudioTest {
    @TempDir
    Path temporary;

    @Test
    void writesStereoPcmInLittleEndianOrderWithTheCorrectDuration() throws Exception {
        var output = temporary.resolve("sound/tone.wav");
        var samples = new short[]{0x1234, -2, Short.MIN_VALUE, Short.MAX_VALUE};

        PcmAudio.writeWave(output, samples);

        try (var stream = AudioSystem.getAudioInputStream(output.toFile())) {
            assertSoftly(softly -> {
                softly.assertThat(stream.getFrameLength()).isEqualTo(2);
                softly.assertThat(stream.getFormat().getSampleRate()).isEqualTo(AudioUnit.SAMPLE_RATE);
                softly.assertThat(stream.getFormat().getChannels()).isEqualTo(2);
                softly.assertThat(stream.getFormat().getSampleSizeInBits()).isEqualTo(16);
                softly.assertThat(stream.getFormat().isBigEndian()).isFalse();
            });
            assertThat(stream.readAllBytes()).containsExactly(
                    (byte) 0x34, (byte) 0x12, (byte) 0xfe, (byte) 0xff,
                    (byte) 0x00, (byte) 0x80, (byte) 0xff, (byte) 0x7f);
        }
    }

    @Test
    void producesARepeatableOneSecondToneThatFadesToSilence() {
        var samples = AudioDemo.samples();

        assertSoftly(softly -> {
            softly.assertThat(samples).hasSize(AudioUnit.SAMPLE_RATE * 2).isEqualTo(AudioDemo.samples());
            softly.assertThat(samples[0]).isPositive();
            softly.assertThat(Arrays.copyOfRange(samples, samples.length / 2, samples.length))
                    .containsOnly((short) 0);
        });
    }

    @Test
    void rejectsIncompleteStereoFramesBeforeCreatingTheOutput() {
        var output = temporary.resolve("invalid.wav");

        assertThatThrownBy(() -> PcmAudio.writeWave(output, new short[1]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(output).doesNotExist();
    }
}

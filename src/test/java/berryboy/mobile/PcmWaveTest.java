package berryboy.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.io.ByteArrayInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class PcmWaveTest {
    @Test
    void producesAReadableStereoPcm16WavePacket() throws Exception {
        var pcm = new short[]{32767, -32768, 1234, -1234};
        var bytes = PcmWave.encode(pcm, 2);
        try (var audio = AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes))) {
            assertThat(audio.getFormat().getChannels()).isEqualTo(2);
            assertThat(audio.getFormat().getSampleRate()).isEqualTo(32768f);
            assertThat(audio.getFormat().getSampleSizeInBits()).isEqualTo(16);
            assertThat(audio.getFormat().isBigEndian()).isFalse();
            assertThat(audio.getFrameLength()).isEqualTo(2);
            assertThat(audio.readAllBytes()).containsExactly((byte)0xff,(byte)0x7f,(byte)0,(byte)0x80,
                    (byte)0xd2,(byte)0x04,(byte)0x2e,(byte)0xfb);
        }
        assertThatThrownBy(() -> PcmWave.encode(pcm, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PcmWave.encode(pcm, 3)).isInstanceOf(IllegalArgumentException.class);
    }
}

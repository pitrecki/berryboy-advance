package berryboy.mobile.audio;

import berryboy.mobile.settings.PhoneOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("core")
class OutputMixerTest {
    @Test
    void changesOutputWithoutOverflowOrTouchingOtherBufferFrames() {
        var options = new PhoneOptions(); options.setVolume(100); options.setMono(true);
        var pcm = new short[]{32767, 32767, -32768, 32767, 123, 456};
        OutputMixer.apply(pcm, 2, options);
        assertThat(pcm).containsExactly((short) 32767, (short) 32767, (short) 0, (short) 0, (short) 123, (short) 456);
        options.setMuted(true); OutputMixer.apply(pcm, 3, options);
        assertThat(pcm).containsOnly((short) 0);
    }
}

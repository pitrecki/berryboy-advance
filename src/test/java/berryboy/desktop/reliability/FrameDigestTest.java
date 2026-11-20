package berryboy.desktop.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class FrameDigestTest {
    @Test
    void hashesRgbFrameHashesAndLittleEndianStereoSamplesWithoutRetainingOldArrays() throws Exception {
        var digest = new FrameDigest(); var rgb = new int[38400];
        var pcm = new short[]{0x1234, -2};
        digest.add(new FrameGate.Frame(1, rgb, pcm));
        rgb[0] = 0xffffff; pcm[0] = 0;
        var result = digest.finish();
        var hash = MessageDigest.getInstance("SHA-256");
        var expected = hash.digest(hash.digest(new byte[38400 * 3]));
        assertThat(result.rgbChainSha256()).isEqualTo(HexFormat.of().formatHex(expected));
        assertThat(result.pcmSha256()).isEqualTo(HexFormat.of().formatHex(hash.digest(new byte[]{0x34,0x12,-2,-1})));
        assertThat(result.frames()).isEqualTo(1); assertThat(result.audioFrames()).isEqualTo(1);
        assertThat(digest.finish()).isSameAs(result);
        assertThatThrownBy(() -> digest.add(new FrameGate.Frame(2,rgb,pcm))).hasMessageContaining("already finished");
    }

    @Test
    void rejectsIncompleteFramesAndUnpairedStereoSamples() {
        var digest = new FrameDigest();
        assertThatThrownBy(() -> digest.add(new FrameGate.Frame(1,new int[1],new short[2])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> digest.add(new FrameGate.Frame(1,new int[38400],new short[1])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(digest.finish().frames()).isZero();
    }
}

package berryboy.mobile.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("core")
class FrameScalerTest {
    @Test
    void fractionalScalingPreservesCornersAndOwnsItsReusableOutput() {
        var pixels = new int[240 * 160];
        pixels[0] = 0xff0000; pixels[239] = 0x00ff00; pixels[239 + 159 * 240] = 0x0000ff;
        var before = pixels.clone(); var scaler = new FrameScaler();
        var scaled = scaler.scale(pixels, 640, 426);
        assertSoftly(s -> {
            s.assertThat(scaled).hasSize(640 * 426);
            s.assertThat(scaled[0]).isEqualTo(0xff0000);
            s.assertThat(scaled[639]).isEqualTo(0x00ff00);
            s.assertThat(scaled[scaled.length - 1]).isEqualTo(0x0000ff);
            s.assertThat(pixels).isEqualTo(before);
        });
        pixels[0] = 0x123456;
        assertThat(scaler.scale(pixels, 640, 426)).isSameAs(scaled);
        assertThat(scaled[0]).isEqualTo(0x123456);
        assertThat(scaler.scale(pixels, 240, 160)).isSameAs(pixels);
    }
}

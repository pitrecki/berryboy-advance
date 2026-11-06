package berryboy.mobile.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("core")
class FramePresentationTest {
    @Test
    void skipsOnlyPresentationsAndMeasuresGuestAndPresentationRatesSeparately() {
        var frames = new FramePresentation();
        var shown = 0;
        for (var i = 0; i <= 60; i++) if (frames.next(1, i * 1000L / 60)) shown++;
        assertThat(shown).isEqualTo(31);
        assertSoftly(s -> {
            s.assertThat(frames.guestTenths()).isEqualTo(600);
        });
        frames.reset();
        assertThat(frames.guestTenths()).isZero();
        assertThat(frames.next(0, 0)).isTrue();
    }
}

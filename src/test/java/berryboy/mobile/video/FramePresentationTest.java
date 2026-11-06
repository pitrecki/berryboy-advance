package berryboy.mobile.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("core")
class FramePresentationTest {
    @ParameterizedTest
    @CsvSource({"2,0,61", "4,0,61", "8,0,61", "8,1,31", "8,5,11"})
    void acceleratedPlayCountsAllGuestFramesAndBoundsDisplaySubmissions(int speed, int skip, int expectedShown) {
        var frames = new FramePresentation(); var shown = 0;
        for (var i = 0; i <= 60 * speed; i++) if (frames.next(skip, speed, i * 1000L / (60 * speed))) shown++;
        assertThat(shown).isEqualTo(expectedShown);
        assertThat(frames.guestTenths()).isEqualTo(600 * speed);
        frames.reset();
        assertThat(frames.next(0, 1, 2000)).isTrue();
        assertThat(frames.next(0, 1, 2017)).isTrue();
    }
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

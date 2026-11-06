package berryboy.mobile.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("core")
class GameViewportTest {
    @ParameterizedTest
    @CsvSource({"640,480,2,0,27,640,426", "640,480,3,0,0,640,480", "640,480,1,80,80,480,320",
            "640,480,0,200,160,240,160", "480,640,2,0,160,480,320", "128,128,1,0,21,128,85",
            "128,128,0,0,21,128,85", "240,160,2,0,0,240,160"})
    void usesTheWholeCanvasWithoutReservingApplicationChrome(int width, int height, int mode, int x, int y, int w, int h) {
        var view = GameViewport.create(width, height, mode);
        assertSoftly(s -> {
            s.assertThat(view.x()).isEqualTo(x); s.assertThat(view.y()).isEqualTo(y);
            s.assertThat(view.width()).isEqualTo(w); s.assertThat(view.height()).isEqualTo(h);
        });
    }
}

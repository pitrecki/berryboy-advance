package berryboy.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("core")
class GameViewportTest {
    @ParameterizedTest
    @CsvSource({"512,L locked", "256,R locked", "768,L+R locked"})
    void shoulderLocksRemainVisibleWithFpsOffAndDoNotOverlapTheFpsRow(int locks, String label) {
        var screen = new PhoneScreen(); PhoneMenus.game(screen, "Game"); screen.setFrame(new int[240 * 160]);
        screen.setShoulderLocks(locks);
        var plain = new RecordingGraphics(); PhoneRenderer.paint(plain, screen, 640, 480);
        assertThat(plain.text).containsExactly(label);
        assertThat(plain.textY).containsExactly(12);
        screen.setFps("GBA 59.7 / Draw 59.7 fps");
        var fps = new RecordingGraphics(); PhoneRenderer.paint(fps, screen, 640, 480);
        assertThat(fps.text).containsExactly(screen.fps(), label);
        assertThat(fps.textY).containsExactly(12, 52);
        screen.setShoulderLocks(0); screen.setFps("");
        var clear = new RecordingGraphics(); PhoneRenderer.paint(clear, screen, 640, 480);
        assertThat(clear.text).isEmpty();
    }
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
    @Test
    void playingHasNoApplicationHeaderLogoOrFooterAndFpsIsOptional() {
        var screen = new PhoneScreen(); PhoneMenus.game(screen, "Game"); screen.setFrame(new int[240 * 160]);
        screen.setMessage("Menu instructions should not cover gameplay");
        var g = new RecordingGraphics();
        PhoneRenderer.paint(g, screen, 640, 480);
        assertSoftly(s -> {
            s.assertThat(g.logoCalls).isZero(); s.assertThat(g.textCalls).isZero();
            s.assertThat(g.pixelX).isZero(); s.assertThat(g.pixelY).isEqualTo(27);
            s.assertThat(g.pixelWidth).isEqualTo(640); s.assertThat(g.pixelHeight).isEqualTo(426);
        });
        screen.setFps("GBA 59.7 / Draw 59.7 fps");
        PhoneRenderer.paint(g, screen, 640, 480);
        assertThat(g.textCalls).isEqualTo(1);
    }
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
    private static final class RecordingGraphics implements PhoneGraphics {
        int logoCalls, textCalls, pixelX, pixelY, pixelWidth, pixelHeight;
        final java.util.List<String> text = new java.util.ArrayList<>();
        final java.util.List<Integer> textY = new java.util.ArrayList<>();
        public void fill(int x, int y, int w, int h, int color) { }
        public void text(String value, int x, int y, int color, boolean heading) { textCalls++; text.add(value); textY.add(y); }
        public int textWidth(String text, boolean heading) { return text.length() * 8; }
        public void logo(int x, int y) { logoCalls++; }
        public void pixels(int[] rgb, int x, int y, int w, int h) { pixelX=x; pixelY=y; pixelWidth=w; pixelHeight=h; }
    }
}

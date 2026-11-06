package berryboy.mobile.ui;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("core")
class PhoneRendererTest {
    @ParameterizedTest
    @CsvSource({"2,false", "4,true", "8,true"})
    void accelerationStaysVisibleAndDoesNotOverlapFpsOrShoulderBadges(int speed, boolean fps) {
        var screen = new PhoneScreen(); PhoneMenus.game(screen, "Game"); screen.setSpeed(speed);
        screen.setShoulderLocks(768); screen.setFps(fps ? "GBA 120 / Draw 60 fps" : "");
        var g = new RecordingGraphics(); PhoneRenderer.paint(g, screen, 640, 480);
        if (fps) {
            assertThat(g.text).containsExactly(screen.fps(), speed + "x / sound muted", "L+R locked");
            assertThat(g.textY).containsExactly(12, 52, 92);
        } else {
            assertThat(g.text).containsExactly(speed + "x / sound muted", "L+R locked");
            assertThat(g.textY).containsExactly(12, 52);
        }
        screen.setSpeed(1); screen.setShoulderLocks(0); screen.setFps("");
        var normal = new RecordingGraphics(); PhoneRenderer.paint(normal, screen, 640, 480);
        assertThat(normal.text).isEmpty();
    }
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

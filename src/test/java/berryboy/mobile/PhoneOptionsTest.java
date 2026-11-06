package berryboy.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import berryboy.input.Keypad;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class PhoneOptionsTest {
    @Test
    void persistsPreferencesAndMapsAllTenButtons() {
        var options = new PhoneOptions();
        options.setFrameSkip(3); options.setShowFps(true); options.setMono(true); options.setVolume(30);
        options.bind(0, 'Q');
        var copy = PhoneOptions.decode(options.encode());
        assertSoftly(s -> {
            s.assertThat(copy.frameSkip()).isEqualTo(3);
            s.assertThat(copy.volume()).isEqualTo(30);
            s.assertThat(copy.mono()).isTrue();
            s.assertThat(copy.showFps()).isTrue();
            s.assertThat(copy.buttonsFor('Q')).isEqualTo(Keypad.A);
            s.assertThat(copy.buttonsFor('\r')).isEqualTo(Keypad.START);
            for (var i = 0; i < 10; i++) s.assertThat(copy.buttonsFor(copy.key(i))).isEqualTo(1 << i);
        });
        copy.setVolume(90);
        assertThat(options.volume()).isEqualTo(30);
        assertThatThrownBy(() -> copy.bind(1, 'q')).hasMessageContaining("already assigned");
        assertThatThrownBy(() -> copy.bind(1, -11)).hasMessageContaining("reserved");
        assertThatThrownBy(() -> copy.setVolume(101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy.setFrameSkip(-1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void changesOutputWithoutOverflowOrTouchingOtherBufferFrames() {
        var options = new PhoneOptions(); options.setVolume(100); options.setMono(true);
        var pcm = new short[]{32767, 32767, -32768, 32767, 123, 456};
        OutputMixer.apply(pcm, 2, options);
        assertThat(pcm).containsExactly((short) 32767, (short) 32767, (short) 0, (short) 0, (short) 123, (short) 456);
        options.setMuted(true); OutputMixer.apply(pcm, 3, options);
        assertThat(pcm).containsOnly((short) 0);
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

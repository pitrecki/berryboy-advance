package berryboy.mobile.settings;

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
        options.bind(0, 'Q'); options.setScaling(PhoneOptions.SCALE_STRETCH); options.setStickyShoulders(true); options.setShowBoot(false);
        options.setSpeed(4);
        var copy = PhoneOptions.decode(options.encode());
        assertSoftly(s -> {
            s.assertThat(copy.frameSkip()).isEqualTo(3);
            s.assertThat(copy.volume()).isEqualTo(30);
            s.assertThat(copy.mono()).isTrue();
            s.assertThat(copy.showFps()).isTrue();
            s.assertThat(copy.scaling()).isEqualTo(PhoneOptions.SCALE_STRETCH);
            s.assertThat(copy.stickyShoulders()).isTrue();
            s.assertThat(copy.showBoot()).isFalse();
            s.assertThat(copy.speed()).isEqualTo(4);
            s.assertThat(copy.buttonsFor('Q')).isEqualTo(Keypad.A);
            s.assertThat(copy.buttonsFor('\r')).isEqualTo(Keypad.START);
            for (var i = 0; i < 10; i++) s.assertThat(copy.buttonsFor(copy.key(i))).isEqualTo(1 << i);
        });
        copy.setVolume(90); copy.setStickyShoulders(false);
        assertThat(options.volume()).isEqualTo(30);
        assertThat(options.copy().stickyShoulders()).isTrue();
        assertThat(options.copy().showBoot()).isFalse();
        assertThat(options.copy().speed()).isEqualTo(4);
        assertThatThrownBy(() -> copy.bind(1, 'q')).hasMessageContaining("already assigned");
        assertThatThrownBy(() -> copy.bind(1, -11)).hasMessageContaining("reserved");
        assertThatThrownBy(() -> copy.setVolume(101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> copy.setFrameSkip(-1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void migratesVersionOneWithoutLosingMappingOrImageChoice() {
        assertThat(new PhoneOptions().scaling()).isEqualTo(PhoneOptions.SCALE_FIT);
        for (var integer : new boolean[]{false, true}) {
            var old = java.nio.ByteBuffer.allocate(56).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            old.putInt(1).putInt(60).putInt(2).put((byte)0).put((byte)1).put((byte)1).put((byte)(integer ? 1 : 0));
            var defaults = new PhoneOptions();
            for (var i = 0; i < 10; i++) old.putInt(i == 0 ? 'q' : defaults.key(i));
            var bytes = old.array(); var before = bytes.clone();
            var migrated = PhoneOptions.decode(bytes);
            assertSoftly(s -> {
                s.assertThat(migrated.volume()).isEqualTo(60);
                s.assertThat(migrated.mono()).isTrue();
                s.assertThat(migrated.scaling()).isEqualTo(integer ? PhoneOptions.SCALE_INTEGER : PhoneOptions.SCALE_NATIVE);
                s.assertThat(migrated.buttonsFor('q')).isEqualTo(Keypad.A);
                s.assertThat(migrated.encode()).hasSize(65);
                s.assertThat(migrated.stickyShoulders()).isFalse();
                s.assertThat(migrated.showBoot()).isTrue();
                s.assertThat(migrated.speed()).isEqualTo(1);
                s.assertThat(PhoneOptions.decode(migrated.encode()).scaling()).isEqualTo(migrated.scaling());
                s.assertThat(bytes).isEqualTo(before);
            });
        }
    }
    @Test
    void migratesVersionFourAndRejectsCorruptOrUnsupportedSpeed() {
        var options = new PhoneOptions(); options.setShowBoot(false); options.setSpeed(8);
        var old = java.util.Arrays.copyOf(options.encode(), 61); old[0] = 4;
        var migrated = PhoneOptions.decode(old);
        assertThat(migrated.showBoot()).isFalse();
        assertThat(migrated.speed()).isEqualTo(1);
        var corrupt = options.encode(); corrupt[61] = 3;
        assertThatThrownBy(() -> PhoneOptions.decode(corrupt)).hasMessageContaining("Speed");
        assertThatThrownBy(() -> options.setSpeed(0)).hasMessageContaining("Speed");
    }
    @Test
    void migratesVersionThreeWithoutChangingItsBootBehavior() {
        var options = new PhoneOptions(); options.setStickyShoulders(true); options.setShowBoot(false);
        var old = java.util.Arrays.copyOf(options.encode(), 60); old[0] = 3;
        var copy = PhoneOptions.decode(old);
        assertThat(copy.showBoot()).isTrue();
        assertThat(copy.stickyShoulders()).isTrue();
        assertThat(copy.speed()).isEqualTo(1);
    }
    @Test
    void migratesVersionTwoKeepingExistingSettingsAndLeavingAssistOff() {
        var options = new PhoneOptions(); options.setVolume(40); options.setScaling(PhoneOptions.SCALE_STRETCH);
        options.bind(9, 'q'); options.setStickyShoulders(true);
        var old = java.util.Arrays.copyOf(options.encode(), 59); old[0] = 2;
        var before = old.clone(); var copy = PhoneOptions.decode(old);
        assertSoftly(s -> {
            s.assertThat(copy.volume()).isEqualTo(40);
            s.assertThat(copy.scaling()).isEqualTo(PhoneOptions.SCALE_STRETCH);
            s.assertThat(copy.buttonsFor('Q')).isEqualTo(Keypad.L);
            s.assertThat(copy.stickyShoulders()).isFalse();
            s.assertThat(copy.speed()).isEqualTo(1);
            s.assertThat(old).isEqualTo(before);
        });
    }
}

package berryboy.mobile;

import berryboy.input.Keypad;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@Tag("core")
class PhoneInputTest {
    @Test
    void standardControlsRemainMomentaryForEveryButton() {
        var input = new PhoneInput();
        var options = new PhoneOptions();
        for (var button = 0; button < 10; button++) input.press(options.key(button), 1 << button);
        assertThat(input.pressed()).isEqualTo(Keypad.ALL_BUTTONS);
        assertThat(input.lockedShoulders()).isZero();
        var remaining = Keypad.ALL_BUTTONS;
        for (var button = 0; button < 10; button++) {
            input.release(options.key(button));
            remaining &= ~(1 << button);
            assertThat(input.pressed()).isEqualTo(remaining);
        }
    }

    @Test
    void locksBothShouldersIndependentlyWhileActionAndDirectionStayMomentary() {
        var input = new PhoneInput(); input.setStickyShoulders(true);
        input.press('a', Keypad.L); input.release('a');
        input.press('s', Keypad.R); input.release('s');
        input.press('z', Keypad.A); input.press('x', Keypad.B); input.press('j', Keypad.LEFT);
        assertSoftly(s -> {
            s.assertThat(input.pressed()).isEqualTo(Keypad.L | Keypad.R | Keypad.A | Keypad.B | Keypad.LEFT);
            s.assertThat(input.lockedShoulders()).isEqualTo(Keypad.L | Keypad.R);
        });
        input.release('z'); input.release('x'); input.release('j');
        assertThat(input.pressed()).isEqualTo(Keypad.L | Keypad.R);
        input.press('a', Keypad.L);
        assertThat(input.pressed()).isEqualTo(Keypad.R);
        input.release('a'); input.press('s', Keypad.R);
        assertThat(input.pressed()).isZero();
    }

    @Test
    void duplicateKeyDownsAndCaseAliasesCannotToggleTheLockAgain() {
        var input = new PhoneInput(); input.setStickyShoulders(true);
        input.press('Q', Keypad.L);
        for (var repeat = 0; repeat < 10; repeat++) input.press('q', Keypad.L);
        assertThat(input.pressed()).isEqualTo(Keypad.L);
        input.release('q'); input.press('q', Keypad.L);
        assertThat(input.pressed()).isZero();
        input.release('Q'); input.press('q', Keypad.L);
        assertThat(input.pressed()).isEqualTo(Keypad.L);
    }

    @Test
    void releaseAllClearsLocksAndPhysicalKeysButRetainsTheChosenMode() {
        var input = new PhoneInput(); input.setStickyShoulders(true);
        input.press('a', Keypad.L); input.press('z', Keypad.A); input.press('i', Keypad.UP);
        input.releaseAll();
        assertThat(input.pressed()).isZero();
        assertThat(input.lockedShoulders()).isZero();
        input.release('a'); input.release('z'); input.release('i');
        input.press('a', Keypad.L); input.release('a');
        assertThat(input.pressed()).isEqualTo(Keypad.L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changingModeReleasesInputAndReapplyingItPreservesInput(boolean initial) {
        var input = new PhoneInput(); input.setStickyShoulders(initial);
        input.press('a', Keypad.L); input.press('z', Keypad.A);
        input.setStickyShoulders(initial);
        assertThat(input.pressed()).isEqualTo(Keypad.L | Keypad.A);
        input.setStickyShoulders(!initial);
        assertThat(input.pressed()).isZero();
        input.release('a'); input.release('z');
        input.press('a', Keypad.L); input.release('a');
        assertThat(input.pressed()).isEqualTo(initial ? 0 : Keypad.L);
    }

    @Test
    void releasingOneOfTwoPhysicalKeysForAnActionDoesNotReleaseTheOther() {
        var input = new PhoneInput(); input.setStickyShoulders(true);
        input.press('z', Keypad.A); input.press(-5, Keypad.A); // Trackpad FIRE fallback.
        input.release('z'); input.release('!');
        assertThat(input.pressed()).isEqualTo(Keypad.A);
        input.release(-5);
        assertThat(input.pressed()).isZero();
    }

    @Test
    void unmappedAndOverflowKeysCannotCreateUnreleasableLocks() {
        var input = new PhoneInput(); input.setStickyShoulders(true);
        for (var i = 0; i < 100; i++) input.press(1000 + i, 0);
        input.press('a', Keypad.L); input.release('a');
        assertThat(input.pressed()).isEqualTo(Keypad.L);
        input.releaseAll();
        for (var i = 0; i < 16; i++) input.press(1000 + i, Keypad.A);
        input.press('a', Keypad.L); input.release('a');
        assertThat(input.lockedShoulders()).isZero();
        input.releaseAll();
        assertThat(input.pressed()).isZero();
        input.press('a', Keypad.L); input.release('a');
        assertThat(input.pressed()).isEqualTo(Keypad.L);
    }
}

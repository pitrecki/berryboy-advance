package berryboy.mobile.ui;

import berryboy.mobile.settings.PhoneOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("core")
class PhoneMenusTest {
    @Test
    void biosSelectionIsAvailableBeforeOpeningAGame() {
        var screen = new PhoneScreen(); var options = new PhoneOptions();
        PhoneMenus.settings(screen, options);
        assertThat(screen.label(8)).isEqualTo("BIOS file");
        assertThat(screen.value(8)).isEqualTo("Not set");
        assertThat(screen.enabled(8)).isTrue();
        PhoneMenus.settings(screen, options, "Selected");
        assertThat(screen.value(8)).isEqualTo("Selected");
    }
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8})
    void speedIsAvailableInPauseAndSettingsWithoutMovingBootPreference(int speed) {
        var screen = new PhoneScreen(); var options = new PhoneOptions(); options.setSpeed(speed);
        PhoneMenus.pause(screen, "Game", speed);
        assertThat(screen.label(0)).isEqualTo("Resume game");
        assertThat(screen.label(1)).isEqualTo("Speed");
        assertThat(screen.value(1)).isEqualTo(speed + "x");
        assertThat(screen.label(2)).isEqualTo("Save state");
        assertThat(screen.label(3)).isEqualTo("Load state");
        PhoneMenus.settings(screen, options);
        assertThat(screen.label(6)).isEqualTo("GBA boot animation");
        assertThat(screen.label(7)).isEqualTo("Speed");
        assertThat(screen.value(7)).isEqualTo(speed + "x");
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void homeExposesTheGameFolderAndKeepsResumeAvailability(boolean loaded) {
        var screen = new PhoneScreen(); PhoneMenus.home(screen, loaded);
        assertThat(screen.label(0)).isEqualTo("Open game");
        assertThat(screen.label(1)).isEqualTo("ROM folder");
        assertThat(screen.enabled(1)).isTrue();
        assertThat(screen.label(2)).isEqualTo("Resume game");
        assertThat(screen.enabled(2)).isEqualTo(loaded);
    }
    @Test
    void controlsMenuOffersAssistAndKeepsAllTenMappingsAccessible() {
        var screen = new PhoneScreen(); var options = new PhoneOptions();
        PhoneMenus.controls(screen, options);
        assertThat(screen.count()).isEqualTo(11);
        assertThat(screen.label(0)).isEqualTo("L/R assist");
        assertThat(screen.value(0)).isEqualTo("Off");
        for (var i = 0; i < 10; i++) assertThat(screen.label(i + 1)).isEqualTo(PhoneMenus.buttonName(i));
        options.setStickyShoulders(true); PhoneMenus.controls(screen, options);
        assertThat(screen.value(0)).isEqualTo("Tap to lock");
    }
}

package berryboy.mobile.ui;

import berryboy.mobile.settings.PhoneOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("core")
class PhoneMenusTest {
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

package berryboy.mobile.ui;

import berryboy.mobile.settings.PhoneOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("core")
class PhoneMenusTest {
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

package berryboy.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import berryboy.input.Keypad;
import java.awt.event.KeyEvent;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("desktop")
@DisplayName("Desktop keyboard bindings")
class KeyboardInputTest {
    @ParameterizedTest(name = "key code {0} -> GBA button {1}")
    @CsvSource({"90, 1", "88, 2", "8, 4", "10, 8", "39, 16", "37, 32",
            "38, 64", "40, 128", "83, 256", "65, 512"})
    void mapsPressRepeatAndReleaseThroughTheActualSwingActions(int keyCode, int expected) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var input = new KeyboardInput();
            var component = new JPanel();
            input.bind(component);

            dispatch(component, keyCode, false);
            dispatch(component, keyCode, false);
            assertThat(input.pressedButtons()).as("auto-repeat is idempotent").isEqualTo(expected);

            dispatch(component, keyCode, true);
            assertThat(input.pressedButtons()).isZero();
        });
    }

    @Test
    void releasingOneButtonPreservesOtherHeldButtons() {
        var input = new KeyboardInput();
        input.keyChanged(KeyEvent.VK_RIGHT, true);
        input.keyChanged(KeyEvent.VK_Z, true);
        input.keyChanged(KeyEvent.VK_Z, false);

        assertThat(input.pressedButtons()).isEqualTo(Keypad.RIGHT);
    }

    @Test
    void focusLossOrClosingClearsEveryHeldButton() {
        var input = new KeyboardInput();
        input.keyChanged(KeyEvent.VK_Z, true);
        input.keyChanged(KeyEvent.VK_ENTER, true);

        input.releaseAll();

        assertThat(input.pressedButtons()).isZero();
    }

    @Test
    void ignoresUnmappedKeys() {
        var input = new KeyboardInput();
        input.keyChanged(KeyEvent.VK_F12, true);

        assertThat(input.pressedButtons()).isZero();
    }

    private static void dispatch(JComponent component, int keyCode, boolean released) {
        var stroke = KeyStroke.getKeyStroke(keyCode, 0, released);
        var action = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke);
        assertThat(action).as("mapped keystroke %s", stroke).isNotNull();
        component.getActionMap().get(action).actionPerformed(null);
    }
}

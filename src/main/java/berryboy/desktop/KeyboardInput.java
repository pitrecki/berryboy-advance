package berryboy.desktop;

import berryboy.Keypad;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

/** Collects GUI events without letting the GUI thread mutate the emulated machine. */
final class KeyboardInput {
    record Binding(int keyCode, int button) {
    }

    static final List<Binding> BINDINGS = List.of(
            new Binding(KeyEvent.VK_Z, Keypad.A),
            new Binding(KeyEvent.VK_X, Keypad.B),
            new Binding(KeyEvent.VK_BACK_SPACE, Keypad.SELECT),
            new Binding(KeyEvent.VK_ENTER, Keypad.START),
            new Binding(KeyEvent.VK_RIGHT, Keypad.RIGHT),
            new Binding(KeyEvent.VK_LEFT, Keypad.LEFT),
            new Binding(KeyEvent.VK_UP, Keypad.UP),
            new Binding(KeyEvent.VK_DOWN, Keypad.DOWN),
            new Binding(KeyEvent.VK_S, Keypad.R),
            new Binding(KeyEvent.VK_A, Keypad.L));

    private final AtomicInteger pressed = new AtomicInteger();

    int pressedButtons() {
        return pressed.get();
    }

    void releaseAll() {
        pressed.set(0);
    }

    void bind(JComponent component) {
        for (var binding : BINDINGS) {
            bindEvent(component, binding, false);
            bindEvent(component, binding, true);
        }
    }

    void keyChanged(int keyCode, boolean down) {
        BINDINGS.stream().filter(binding -> binding.keyCode() == keyCode).findFirst()
                .ifPresent(binding -> pressed.getAndUpdate(buttons ->
                        down ? buttons | binding.button() : buttons & ~binding.button()));
    }

    private void bindEvent(JComponent component, Binding binding, boolean released) {
        var action = (released ? "release-" : "press-") + binding.keyCode();
        var keystroke = KeyStroke.getKeyStroke(binding.keyCode(), 0, released);
        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(keystroke, action);
        component.getActionMap().put(action, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                keyChanged(binding.keyCode(), !released);
            }
        });
    }
}

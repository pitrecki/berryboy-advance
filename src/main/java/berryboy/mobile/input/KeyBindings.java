package berryboy.mobile.input;

import berryboy.input.Keypad;
import berryboy.state.StateBuffer;

/** Owns phone key assignments, alias normalization and their ten-word storage layout. */
public final class KeyBindings {
    private final int[] keys = {'z', 'x', '\b', '\n', 'l', 'j', 'i', 'k', 's', 'a'};

    public int key(int button) { return keys[button]; }

    public void bind(int button, int key) {
        key = normalizeKey(key);
        if (button < 0 || button >= keys.length || key == 0 || key == -11 || key == 27 || key == '#') {
            throw new IllegalArgumentException("Choose a game key; Back is reserved for the menu");
        }
        for (var i = 0; i < keys.length; i++) if (i != button && keys[i] == key) {
            throw new IllegalArgumentException("Key already assigned to another button");
        }
        keys[button] = key;
    }

    public int buttonsFor(int code) {
        code = normalizeKey(code);
        var buttons = 0;
        for (var i = 0; i < keys.length; i++) if (keys[i] == code) buttons |= 1 << i;
        return buttons & Keypad.ALL_BUTTONS;
    }

    public static int normalizeKey(int code) {
        if (code >= 'A' && code <= 'Z') code += 'a' - 'A';
        return code == '\r' ? '\n' : code;
    }

    public void copyFrom(KeyBindings source) { System.arraycopy(source.keys, 0, keys, 0, keys.length); }

    /** Preserve the established preference layout; validate into the newly decoded owner. */
    public void transferState(StateBuffer state) {
        state.ints(keys);
        if (state.reading()) for (var i = 0; i < keys.length; i++) bind(i, keys[i]);
    }
}

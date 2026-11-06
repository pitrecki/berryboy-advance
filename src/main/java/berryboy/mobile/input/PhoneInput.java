package berryboy.mobile.input;

import berryboy.mobile.settings.PhoneOptions;

import berryboy.input.Keypad;

/** UI-owned physical keys and optional shoulder locks; sends only button masks to the guest. */
public final class PhoneInput {
    private static final int SHOULDERS = Keypad.L | Keypad.R;
    private final int[] keys = new int[16];
    private final int[] buttons = new int[16];
    private int count;
    private int locked;
    private boolean stickyShoulders;

    public void setStickyShoulders(boolean enabled) {
        if (stickyShoulders != enabled) releaseAll();
        stickyShoulders = enabled;
    }

    public void press(int code, int mapped) {
        code = PhoneOptions.normalizeKey(code);
        mapped &= Keypad.ALL_BUTTONS;
        if (mapped == 0 || count == keys.length) return;
        for (var i = 0; i < count; i++) if (keys[i] == code) return;
        keys[count] = code;
        if (stickyShoulders) {
            locked ^= mapped & SHOULDERS;
            buttons[count] = mapped & ~SHOULDERS;
        } else buttons[count] = mapped;
        count++;
    }

    public void release(int code) {
        code = PhoneOptions.normalizeKey(code);
        for (var i = 0; i < count; i++) if (keys[i] == code) {
            count--;
            keys[i] = keys[count];
            buttons[i] = buttons[count];
            return;
        }
    }

    /** Pause, focus loss, mode changes and game/state switches release all input. */
    public void releaseAll() { count = 0; locked = 0; }

    public int pressed() {
        var result = locked;
        for (var i = 0; i < count; i++) result |= buttons[i];
        return result;
    }

    public int lockedShoulders() { return locked; }
}

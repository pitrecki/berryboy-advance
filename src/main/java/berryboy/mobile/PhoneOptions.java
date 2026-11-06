package berryboy.mobile;

import berryboy.input.Keypad;
import berryboy.state.StateBuffer;

/** Frontend preferences; never change guest sound registers or hardware timing. */
public final class PhoneOptions {
    public static final int SCALE_NATIVE = 0;
    public static final int SCALE_INTEGER = 1;
    public static final int SCALE_FIT = 2;
    public static final int SCALE_STRETCH = 3;
    private int volume = 80;
    private int frameSkip;
    private boolean muted;
    private boolean mono;
    private boolean showFps;
    private int scaling = SCALE_FIT;
    private final int[] keys = {'z', 'x', '\b', '\n', 'l', 'j', 'i', 'k', 's', 'a'};

    public int volume() { return volume; }
    public int frameSkip() { return frameSkip; }
    public boolean muted() { return muted; }
    public boolean mono() { return mono; }
    public boolean showFps() { return showFps; }
    public int scaling() { return scaling; }
    public int key(int button) { return keys[button]; }
    public void setVolume(int value) {
        if (value < 0 || value > 100) throw new IllegalArgumentException("Volume must be 0..100");
        volume = value;
    }
    public void setFrameSkip(int value) {
        if (value < 0 || value > 5) throw new IllegalArgumentException("Frame skip must be 0..5");
        frameSkip = value;
    }
    public void setMuted(boolean value) { muted = value; }
    public void setMono(boolean value) { mono = value; }
    public void setShowFps(boolean value) { showFps = value; }
    public void setScaling(int value) {
        if (value < SCALE_NATIVE || value > SCALE_STRETCH) throw new IllegalArgumentException("Invalid image scaling mode");
        scaling = value;
    }
    public void bind(int button, int key) {
        if (key >= 'A' && key <= 'Z') key += 'a' - 'A';
        if (key == '\r') key = '\n';
        if (button < 0 || button >= keys.length || key == 0 || key == -11 || key == 27 || key == '#') {
            throw new IllegalArgumentException("Choose a game key; Back is reserved for the menu");
        }
        for (var i = 0; i < keys.length; i++) if (i != button && keys[i] == key) {
            throw new IllegalArgumentException("Key already assigned to another button");
        }
        keys[button] = key;
    }
    public int buttonsFor(int code) {
        if (code >= 'A' && code <= 'Z') code += 'a' - 'A';
        if (code == '\r') code = '\n';
        var buttons = 0;
        for (var i = 0; i < keys.length; i++) if (keys[i] == code) buttons |= 1 << i;
        return buttons & Keypad.ALL_BUTTONS;
    }
    public PhoneOptions copy() {
        var result = new PhoneOptions();
        result.volume = volume; result.frameSkip = frameSkip;
        result.muted = muted; result.mono = mono;
        result.showFps = showFps; result.scaling = scaling;
        System.arraycopy(keys, 0, result.keys, 0, keys.length);
        return result;
    }
    public byte[] encode() {
        var state = new StateBuffer();
        state.value(2); transfer(state, 2); return state.finish();
    }
    public static PhoneOptions decode(byte[] bytes) {
        var state = new StateBuffer(bytes);
        var version = state.value(0);
        if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported settings version");
        var result = new PhoneOptions(); result.transfer(state, version); state.requireEnd();
        result.setVolume(result.volume); result.setFrameSkip(result.frameSkip); result.setScaling(result.scaling);
        for (var i = 0; i < result.keys.length; i++) result.bind(i, result.keys[i]);
        return result;
    }
    private void transfer(StateBuffer state, int version) {
        volume = state.value(volume); frameSkip = state.value(frameSkip);
        muted = state.flag(muted); mono = state.flag(mono);
        showFps = state.flag(showFps);
        scaling = version == 1 ? (state.flag(false) ? SCALE_INTEGER : SCALE_NATIVE) : state.value(scaling);
        state.ints(keys);
    }
}

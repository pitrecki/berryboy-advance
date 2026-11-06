package berryboy.mobile.settings;

import berryboy.mobile.input.KeyBindings;
import berryboy.mobile.playback.PlaybackSpeed;
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
    private boolean stickyShoulders;
    private boolean showBoot = true;
    private int speed = 1;
    private int scaling = SCALE_FIT;
    private final KeyBindings keys = new KeyBindings();

    public int volume() { return volume; }
    public int frameSkip() { return frameSkip; }
    public boolean muted() { return muted; }
    public boolean mono() { return mono; }
    public boolean showFps() { return showFps; }
    public boolean stickyShoulders() { return stickyShoulders; }
    public boolean showBoot() { return showBoot; }
    public int speed() { return speed; }
    public int scaling() { return scaling; }
    public int key(int button) { return keys.key(button); }
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
    public void setStickyShoulders(boolean value) { stickyShoulders = value; }
    public void setShowBoot(boolean value) { showBoot = value; }
    public void setSpeed(int value) { PlaybackSpeed.requireFactor(value); speed = value; }
    public void setScaling(int value) {
        if (value < SCALE_NATIVE || value > SCALE_STRETCH) throw new IllegalArgumentException("Invalid image scaling mode");
        scaling = value;
    }
    public void bind(int button, int key) { keys.bind(button, key); }
    public int buttonsFor(int code) { return keys.buttonsFor(code); }
    public PhoneOptions copy() {
        var result = new PhoneOptions();
        result.volume = volume; result.frameSkip = frameSkip;
        result.muted = muted; result.mono = mono;
        result.showFps = showFps; result.scaling = scaling;
        result.stickyShoulders = stickyShoulders;
        result.showBoot = showBoot;
        result.speed = speed;
        result.keys.copyFrom(keys);
        return result;
    }
    public byte[] encode() {
        var state = new StateBuffer();
        state.value(5); transfer(state, 5); return state.finish();
    }
    public static PhoneOptions decode(byte[] bytes) {
        var state = new StateBuffer(bytes);
        var version = state.value(0);
        if (version < 1 || version > 5) throw new IllegalArgumentException("Unsupported settings version");
        var result = new PhoneOptions(); result.transfer(state, version); state.requireEnd();
        result.setVolume(result.volume); result.setFrameSkip(result.frameSkip); result.setScaling(result.scaling);
        result.setSpeed(result.speed);
        return result;
    }
    private void transfer(StateBuffer state, int version) {
        volume = state.value(volume); frameSkip = state.value(frameSkip);
        muted = state.flag(muted); mono = state.flag(mono);
        showFps = state.flag(showFps);
        scaling = version == 1 ? (state.flag(false) ? SCALE_INTEGER : SCALE_NATIVE) : state.value(scaling);
        keys.transferState(state);
        stickyShoulders = version >= 3 && state.flag(stickyShoulders);
        showBoot = version < 4 || state.flag(showBoot);
        speed = version < 5 ? 1 : state.value(speed);
    }
}

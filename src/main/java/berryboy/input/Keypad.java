package berryboy.input;

/** The ten GBA buttons. Frontends provide pressed bits; KEYINPUT exposes active-low bits. */
public final class Keypad {
    public static final int A = 1;
    public static final int B = 1 << 1;
    public static final int SELECT = 1 << 2;
    public static final int START = 1 << 3;
    public static final int RIGHT = 1 << 4;
    public static final int LEFT = 1 << 5;
    public static final int UP = 1 << 6;
    public static final int DOWN = 1 << 7;
    public static final int R = 1 << 8;
    public static final int L = 1 << 9;
    public static final int ALL_BUTTONS = 0x03ff;

    private int pressed;

    public void setPressed(int buttons) {
        pressed = buttons & ALL_BUTTONS;
    }

    public int readInput() {
        return ~pressed & ALL_BUTTONS;
    }
}

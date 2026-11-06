package berryboy.mobile;

import berryboy.mobile.settings.PhoneOptions;

import berryboy.input.Keypad;

/** UI-owned view state. Guest memory and render buffers never enter this object. */
public final class PhoneScreen {
    private String title = "Home";
    private String subtitle = "Choose a game to begin";
    private String message = "";
    private String[] labels = new String[0];
    private String[] values = new String[0];
    private boolean[] enabled = new boolean[0];
    private int selection;
    private int[] frame;
    private int scaling = PhoneOptions.SCALE_FIT;
    private String fps = "";
    private String notice = "";
    private int shoulderLocks;
    public void menu(String title, String subtitle, String[] labels, String[] values, boolean[] enabled) {
        if (labels == null || values == null || enabled == null || labels.length != values.length || labels.length != enabled.length) {
            throw new IllegalArgumentException("Incomplete phone menu");
        }
        this.title = title; this.subtitle = subtitle;
        this.labels = labels; this.values = values; this.enabled = enabled;
        selection = 0;
    }
    public void move(int direction) {
        if (labels.length != 0) selection = (selection + direction + labels.length) % labels.length;
    }
    public void select(int value) {
        if (value < 0 || value >= labels.length) throw new IllegalArgumentException("Invalid menu selection");
        selection = value;
    }
    public void setMessage(String text) { message = text; }
    public void setFrame(int[] ownedRgb) {
        if (ownedRgb != null && ownedRgb.length != 240 * 160) throw new IllegalArgumentException("GBA frame required");
        frame = ownedRgb;
    }
    public void setScaling(int value) { scaling = value; }
    public void setFps(String text) { fps = text; }
    public void setNotice(String text) { notice = text; }
    public void setShoulderLocks(int buttons) { shoulderLocks = buttons & (Keypad.L | Keypad.R); }
    public String title() { return title; }
    public String subtitle() { return subtitle; }
    public String message() { return message; }
    public int count() { return labels.length; }
    public String label(int i) { return labels[i]; }
    public String value(int i) { return values[i]; }
    public boolean enabled(int i) { return enabled[i]; }
    public int selection() { return selection; }
    public int[] frame() { return frame; }
    public int scaling() { return scaling; }
    public String fps() { return fps; }
    public String notice() { return notice; }
    public int shoulderLocks() { return shoulderLocks; }
}

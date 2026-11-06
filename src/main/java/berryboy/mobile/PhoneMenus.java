package berryboy.mobile;

import berryboy.mobile.settings.PhoneOptions;

import berryboy.cheat.CheatList;

/** Menu content shared by the running MIDlet and reproducible application captures. */
public final class PhoneMenus {
    private static final String[] BUTTONS = {"A", "B", "Select", "Start", "Right", "Left", "Up", "Down", "R", "L"};
    private PhoneMenus() { }
    public static String buttonName(int index) { return BUTTONS[index]; }
    public static void home(PhoneScreen screen, boolean loaded) {
        show(screen, "Home", "Game Boy Advance / BBOS", new String[]{"Open game", "Resume game", "Display & audio", "Controls", "Diagnostics", "About"},
                new String[]{">", loaded ? ">" : "No game", ">", ">", ">", ">"}, new boolean[]{true, loaded, true, true, true, true});
    }
    public static void pause(PhoneScreen screen, String game) {
        show(screen, "Paused", game, new String[]{"Resume game", "Save state", "Load state", "Cheats", "Display & audio", "Controls", "Restart game", "Home"},
                new String[]{">", "5 slots", "5 slots", ">", ">", ">", ">", ">"}, null);
    }
    public static void settings(PhoneScreen screen, PhoneOptions options) {
        show(screen, "Display & audio", "Left / Right to change", new String[]{"Volume", "Mute", "Sound", "Frame skip", "FPS counter", "Game image"},
                new String[]{options.volume() + "%", options.muted() ? "On" : "Off", options.mono() ? "Mono" : "Stereo",
                    options.frameSkip() == 0 ? "Off" : "1 in " + (options.frameSkip() + 1), options.showFps() ? "On" : "Off", scalingName(options.scaling())}, null);
    }
    public static void controls(PhoneScreen screen, PhoneOptions options) {
        var labels = new String[11];
        var keys = new String[11];
        labels[0] = "L/R assist";
        keys[0] = options.stickyShoulders() ? "Tap to lock" : "Off";
        for (var i = 0; i < BUTTONS.length; i++) {
            var key = options.key(i);
            labels[i + 1] = BUTTONS[i];
            keys[i + 1] = key == 10 ? "Enter" : key == 8 ? "Backspace" : key >= 32 && key < 127 ? "" + (char) key : "Key " + key;
        }
        show(screen, "Controls", "Select assist or remap a button", labels, keys, null);
    }
    public static void slots(PhoneScreen screen, boolean loading, String[] summaries, boolean supported) {
        var labels = new String[5]; var enabled = new boolean[5];
        for (var i = 0; i < 5; i++) {
            labels[i] = "Slot " + (i + 1);
            enabled[i] = supported && (!loading || !same(summaries[i], "Empty"));
        }
        show(screen, loading ? "Load state" : "Save state", supported ? "Full machine snapshot" : "Open a local game to use slots", labels, summaries, enabled);
    }
    public static void cheats(PhoneScreen screen, CheatList cheats) {
        var labels = new String[cheats.size() + 1]; var values = new String[labels.length];
        labels[0] = "Add cheat"; values[0] = ">";
        for (var i = 0; i < cheats.size(); i++) { labels[i + 1] = cheats.name(i); values[i + 1] = cheats.enabled(i) ? "On" : "Off"; }
        show(screen, "Cheats", "Select toggles / Delete removes", labels, values, null);
    }
    public static void game(PhoneScreen screen, String game) {
        show(screen, "Playing", game, new String[0], new String[0], null);
    }
    private static String scalingName(int mode) {
        return mode == PhoneOptions.SCALE_NATIVE ? "Native 1x" : mode == PhoneOptions.SCALE_INTEGER ? "Integer fit"
                : mode == PhoneOptions.SCALE_FIT ? "Full screen" : "Stretch";
    }
    private static void show(PhoneScreen screen, String title, String subtitle, String[] labels, String[] values, boolean[] enabled) {
        if (enabled == null) {
            enabled = new boolean[labels.length];
            for (var i = 0; i < enabled.length; i++) enabled[i] = true;
        }
        screen.menu(title, subtitle, labels, values, enabled);
    }
    private static boolean same(String a, String b) {
        if (a.length() != b.length()) return false;
        for (var i = 0; i < a.length(); i++) if (a.charAt(i) != b.charAt(i)) return false;
        return true;
    }
}

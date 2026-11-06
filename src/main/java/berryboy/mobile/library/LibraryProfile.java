package berryboy.mobile.library;

import berryboy.state.StateBuffer;

/** Local game-folder preference and last browser selections, independent of device IO. */
public final class LibraryProfile {
    public static final int MAX_URL = 512;
    private String directory = "file:///";
    private String rom = "";
    private String bios = "";

    public String directory() { return directory; }
    public String rom() { return rom; }
    public String bios() { return bios; }

    public void setDirectory(String value) {
        check(value, false);
        if (value.charAt(value.length() - 1) != '/') value += "/";
        if (value.length() > MAX_URL) throw new IllegalArgumentException("Folder URL is too long");
        if (!same(directory, value)) rom = "";
        directory = value;
    }
    public void setRom(String value) { check(value, true); rom = value; }
    public void setBios(String value) { check(value, true); bios = value; }

    public LibraryProfile copy() {
        var result = new LibraryProfile();
        result.directory = directory; result.rom = rom; result.bios = bios;
        return result;
    }
    public byte[] encode() {
        var state = new StateBuffer(); state.value(1);
        write(state, directory); write(state, rom); write(state, bios);
        return state.finish();
    }
    public static LibraryProfile decode(byte[] bytes) {
        var state = new StateBuffer(bytes);
        if (state.value(0) != 1) throw new IllegalArgumentException("Unsupported library settings version");
        var result = new LibraryProfile(); result.setDirectory(read(state));
        result.setRom(read(state)); result.setBios(read(state)); state.requireEnd();
        return result;
    }
    private static void check(String value, boolean emptyAllowed) {
        if (value == null || value.length() > MAX_URL) throw new IllegalArgumentException("Use a local file:/// URL up to 512 characters");
        if (emptyAllowed && value.length() == 0) return;
        var prefix = "file:///";
        if (value.length() < prefix.length()) throw new IllegalArgumentException("Use a local file:/// URL");
        for (var i = 0; i < prefix.length(); i++) if (value.charAt(i) != prefix.charAt(i)) {
            throw new IllegalArgumentException("Use a local file:/// URL");
        }
        for (var i = 0; i < value.length(); i++) if (value.charAt(i) < 32) {
            throw new IllegalArgumentException("Control characters are not allowed in a file URL");
        }
    }
    private static boolean same(String a, String b) {
        if (a.length() != b.length()) return false;
        for (var i = 0; i < a.length(); i++) if (a.charAt(i) != b.charAt(i)) return false;
        return true;
    }
    private static void write(StateBuffer state, String value) {
        state.value(value.length());
        for (var i = 0; i < value.length(); i++) state.value((int) value.charAt(i));
    }
    private static String read(StateBuffer state) {
        var length = state.value(0);
        if (length < 0 || length > MAX_URL) throw new IllegalArgumentException("Invalid library URL length");
        var value = new StringBuffer();
        for (var i = 0; i < length; i++) {
            var c = state.value(0);
            if (c < 0 || c > 65535) throw new IllegalArgumentException("Invalid library URL character");
            value.append((char) c);
        }
        return value.toString();
    }
}

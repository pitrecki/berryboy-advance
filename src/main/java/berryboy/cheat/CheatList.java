package berryboy.cheat;

import berryboy.bus.Memory;

/** Small per-cartridge cheat list, applied only by the guest owner between frames. */
public final class CheatList {
    public static final int CAPACITY = 16;
    private final CheatCode[] codes = new CheatCode[CAPACITY];
    private final String[] names = new String[CAPACITY];
    private final boolean[] enabled = new boolean[CAPACITY];
    private int size;
    public int size() { return size; }
    public String name(int i) { check(i); return names[i]; }
    public CheatCode code(int i) { check(i); return codes[i]; }
    public boolean enabled(int i) { check(i); return enabled[i]; }
    public void add(String name, CheatCode code) {
        if (name == null || name.length() == 0 || name.length() > 24 || code == null || size == CAPACITY) {
            throw new IllegalArgumentException("Use a name up to 24 characters; maximum 16 cheats");
        }
        names[size] = name; codes[size] = code; enabled[size++] = true;
    }
    public void toggle(int i) { check(i); enabled[i] = !enabled[i]; }
    public void remove(int i) {
        check(i); size--;
        for (var j = i; j < size; j++) { names[j] = names[j + 1]; codes[j] = codes[j + 1]; enabled[j] = enabled[j + 1]; }
        names[size] = null; codes[size] = null; enabled[size] = false;
    }
    public void apply(Memory memory) { for (var i = 0; i < size; i++) if (enabled[i]) codes[i].apply(memory); }
    public CheatList copy() {
        var result = new CheatList();
        for (var i = 0; i < size; i++) { result.add(names[i], codes[i]); result.enabled[i] = enabled[i]; }
        return result;
    }
    private void check(int i) { if (i < 0 || i >= size) throw new IllegalArgumentException("Invalid cheat index"); }
}

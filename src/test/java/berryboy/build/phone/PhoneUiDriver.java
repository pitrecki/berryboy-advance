package berryboy.build.phone;

import static org.assertj.core.api.Assertions.assertThat;

import berryboy.mobile.ui.PhoneScreen;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Commands and owned slot bytes only; never inspects the session's live machine. */
public final class PhoneUiDriver implements AutoCloseable {
    private final URLClassLoader loader;
    private final Path rom;
    private final int checksum;
    private final AtomicInteger presentations = new AtomicInteger();
    private Object ui;

    public PhoneUiDriver(Path classes, Path rom, int checksum) throws Exception {
        loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
        this.rom = rom; this.checksum = checksum; reopen();
    }
    public void reopen() throws Exception {
        if (ui != null) call("close", new Class<?>[0]);
        ui = loader.loadClass("berryboy.phone.UiHarness").getConstructor().newInstance();
        var canvas = ui.getClass().getField("canvas").get(ui);
        canvas.getClass().getField("presentationHook").set(canvas, (Runnable) presentations::incrementAndGet);
    }
    Object call(String name, Class<?>[] signature, Object... args) throws Exception {
        return ui.getClass().getMethod(name, signature).invoke(ui, args);
    }
    public void canvas(String method) throws Exception {
        var canvas = ui.getClass().getField("canvas").get(ui);
        canvas.getClass().getMethod(method).invoke(canvas);
    }
    public void press(int key) throws Exception { physical("press", key); }
    public void release(int key) throws Exception { physical("release", key); }
    private void physical(String method, int key) throws Exception {
        var canvas = ui.getClass().getField("canvas").get(ui);
        canvas.getClass().getMethod(method, int.class).invoke(canvas, key);
    }
    public void select(String text) throws Exception { call("select", new Class<?>[]{String.class}, text); }
    public void command(String text) throws Exception { call("command", new Class<?>[]{String.class}, text); }
    public void text(int index, String text) throws Exception { call("text", new Class<?>[]{int.class, String.class}, index, text); }
    public void choice(int item, int index) throws Exception { call("choice", new Class<?>[]{int.class, int.class}, item, index); }
    public void idle() throws Exception { call("awaitIdle", new Class<?>[0]); }
    public PhoneScreen screen() {
        try { return (PhoneScreen) call("screen", new Class<?>[0]); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    public String title() throws Exception {
        var display = ui.getClass().getField("display").get(ui);
        var current = display.getClass().getMethod("getCurrent").invoke(display);
        return (String) current.getClass().getMethod("getTitle").invoke(current);
    }
    public void message(String text) throws Exception { await(() -> screen().message().equals(text)); }
    public void frames(int count) throws Exception {
        var start = presentations.get(); await(() -> presentations.get() >= start + count);
    }
    public void pause() throws Exception { command("Pause / Menu"); idle(); }
    public byte[] slot(int slot) throws Exception {
        return (byte[]) call("readSlot", new Class<?>[]{String.class, int.class, int.class}, rom.toUri().toASCIIString(), checksum, slot);
    }
    public byte[] save(int slot) throws Exception {
        select("Save state"); idle(); select("Slot " + (slot + 1));
        if (title().startsWith("Overwrite slot")) command("Confirm");
        message("Saved slot " + (slot + 1)); idle();
        var bytes = slot(slot); command("Back"); return bytes;
    }
    public void load(int slot) throws Exception {
        select("Load state"); idle(); select("Slot " + (slot + 1));
        message("Loaded slot " + (slot + 1)); idle();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        var deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(2);
        assertThat(condition.getAsBoolean()).as("application command/presentation completed").isTrue();
    }
    public void close() throws Exception {
        try { if (ui != null) call("close", new Class<?>[0]); } finally { loader.close(); }
    }
}

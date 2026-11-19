package berryboy.build.phone;

import static org.assertj.core.api.Assertions.assertThat;

import berryboy.mobile.ui.PhoneScreen;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Controller commands, platform-double setup and owned snapshots; never reads the live machine. */
public final class PhoneUiDriver implements AutoCloseable {
    private final URLClassLoader loader;
    private final Path rom;
    private final int checksum;
    private final AtomicInteger presentations = new AtomicInteger();
    private final AtomicReference<int[]> firstFrame = new AtomicReference<>();
    private Object ui;

    public PhoneUiDriver(Path classes, Path rom, int checksum) throws Exception {
        loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
        this.rom = rom; this.checksum = checksum; reopen();
    }
    public void reopen() throws Exception {
        if (ui != null) call("close", new Class<?>[0]);
        ui = loader.loadClass("berryboy.phone.UiHarness").getConstructor().newInstance();
        var canvas = ui.getClass().getField("canvas").get(ui);
        resetFirstFrame();
        canvas.getClass().getField("presentationHook").set(canvas, (Runnable) () -> {
            var frame = screen().frame();
            if (frame != null && firstFrame.get() == null) firstFrame.compareAndSet(null, frame.clone());
            presentations.incrementAndGet();
        });
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
    public String text(int index) throws Exception { return (String) call("text", new Class<?>[]{int.class}, index); }
    public void choice(int item, int index) throws Exception { call("choice", new Class<?>[]{int.class, int.class}, item, index); }
    public void choose(String name) throws Exception { call("choose", new Class<?>[]{String.class}, name); }
    public String[] choices() throws Exception { return (String[]) call("choices", new Class<?>[0]); }
    public void storageRoots(String... names) throws Exception {
        loader.loadClass("javax.microedition.io.file.FileSystemRegistry").getField("roots").set(null, names.clone());
    }
    public void unreadableFile(Path file) throws Exception {
        loader.loadClass("javax.microedition.io.Connector").getField("unreadableUrl")
                .set(null, file == null ? null : file.toUri().toASCIIString());
    }
    public void failWriteAfter(int bytes) throws Exception {
        loader.loadClass("javax.microedition.io.Connector").getField("failAfterBytes").setInt(null, bytes);
    }
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
    public void messageContaining(String text) throws Exception { await(() -> screen().message().contains(text)); }
    public void awaitTitle(String text) throws Exception {
        await(() -> { try { return title().equals(text); } catch (Exception error) { throw new IllegalStateException(error); } });
    }
    public void resetFirstFrame() { firstFrame.set(null); }
    public int[] firstFrame() { var frame = firstFrame.get(); return frame == null ? null : frame.clone(); }
    public void frames(int count) throws Exception {
        var start = presentations.get(); await(() -> presentations.get() >= start + count);
    }
    public void pause() throws Exception { command("Pause / Menu"); idle(); }
    public byte[] slot(int slot) throws Exception {
        return (byte[]) call("readSlot", new Class<?>[]{String.class, int.class, int.class}, rom.toUri().toASCIIString(), checksum, slot);
    }
    public void replaceSlot(int slot, byte[] bytes) throws Exception {
        call("writeSlot", new Class<?>[]{String.class, int.class, int.class, byte[].class},
                rom.toUri().toASCIIString(), checksum, slot, bytes);
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

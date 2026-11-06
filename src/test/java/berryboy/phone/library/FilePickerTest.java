package berryboy.phone.library;

import berryboy.build.PhoneHarness;
import java.lang.reflect.Field;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Executes the native picker with local filesystem/UI doubles; SDK checks the real API. */
@Tag("build")
class FilePickerTest {
    @TempDir Path temporary;

    @Test
    void startsInTheConfiguredFolderAndReturnsAnEncodedRomUrl() throws Exception {
        var folder = Files.createDirectories(temporary.resolve("another location"));
        var rom = folder.resolve("Golden ż.gBA"); Files.write(rom, new byte[]{1});
        Files.write(folder.resolve("bios.bin"), new byte[]{2}); Files.createDirectory(folder.resolve("sub folder"));
        try (var h = harness()) {
            h.start(false, ".gba", folder.toUri().toASCIIString()); h.ready();
            assertThat(h.items()).containsExactlyInAnyOrder("..", "Golden ż.gBA", "sub folder/");
            h.select("Golden ż.gBA");
            assertThat(h.selected.get()).isEqualTo(rom.toUri().toASCIIString());
        }
    }

    @Test
    void choosesTheCurrentDirectoryWithoutSelectingAFile() throws Exception {
        var folder = Files.createDirectories(temporary.resolve("rom folder"));
        Files.write(folder.resolve("game.gba"), new byte[]{1}); Files.createDirectory(folder.resolve("nested"));
        try (var h = harness()) {
            h.start(true, "", folder.toUri().toASCIIString()); h.ready();
            assertThat(h.items()).containsExactlyInAnyOrder("..", "nested/");
            h.command("choose");
            assertThat(h.selected.get()).isEqualTo(folder.toUri().toASCIIString());
        }
    }

    @Test
    void navigatesStorageRootsAndRecognizesUppercaseBiosFiles() throws Exception {
        var folder = Files.createDirectories(temporary.resolve("storage"));
        var bios = folder.resolve("GBA_BIOS.BIN"); Files.write(bios, new byte[]{1});
        Files.write(folder.resolve("game.gba"), new byte[]{2});
        try (var h = harness()) {
            var rootName = folder.toString().substring(1) + "/";
            h.type("javax.microedition.io.file.FileSystemRegistry").getField("roots").set(null, new String[]{rootName});
            h.start(false, ".bin", "file:///"); h.ready();
            assertThat(h.items()).containsExactly(rootName);
            h.select(rootName); h.ready();
            assertThat(h.items()).containsExactlyInAnyOrder("..", "GBA_BIOS.BIN");
            h.select("GBA_BIOS.BIN");
            assertThat(h.selected.get()).isEqualTo(bios.toUri().toASCIIString());
        }
    }

    @Test
    void cancellationSuppressesALateFolderReadFailure() throws Exception {
        var folder = Files.createDirectories(temporary.resolve("storage"));
        try (var h = harness()) {
            var connector = h.type("javax.microedition.io.Connector");
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            connector.getField("listingEntered").set(null, entered);
            connector.getField("releaseListing").set(null, release);
            connector.getField("failList").setBoolean(null, true);
            h.start(false, ".gba", folder.toUri().toASCIIString());
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                h.command("back");
            } finally { release.countDown(); }
            await(() -> !h.busy());
            assertThat(h.cancelled.get()).isEqualTo(1);
            assertThat(h.selected.get()).isNull();
            assertThat(h.failed.get()).isNull();
        }
    }

    private Harness harness() throws Exception {
        var root = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        var classes = temporary.resolve("classes");
        assertThat(PhoneHarness.compile(root, classes, false)).isZero();
        return new Harness(new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader()));
    }

    private static final class Harness implements AutoCloseable {
        final URLClassLoader loader;
        final AtomicReference<String> selected = new AtomicReference<>(), failed = new AtomicReference<>();
        final AtomicInteger cancelled = new AtomicInteger();
        Object display, picker;
        Harness(URLClassLoader loader) { this.loader = loader; }
        Class<?> type(String name) throws ClassNotFoundException { return loader.loadClass(name); }
        void start(boolean directories, String extension, String initial) throws Exception {
            var displayType = type("javax.microedition.lcdui.Display"); display = displayType.getConstructor().newInstance();
            var callback = type("berryboy.phone.library.FilePicker$Selection");
            var listener = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, args) -> {
                if (method.getName().equals("selected")) selected.set((String) args[0]);
                else if (method.getName().equals("failed")) failed.set((String) args[0]);
                else if (method.getName().equals("cancelled")) cancelled.incrementAndGet();
                return null;
            });
            picker = type("berryboy.phone.library.FilePicker").getConstructor(displayType, callback, boolean.class, String.class, String.class)
                    .newInstance(display, listener, directories, extension, initial);
        }
        Object list() throws Exception { return display.getClass().getMethod("getCurrent").invoke(display); }
        boolean busy() { try { return field("busy").getBoolean(picker); } catch (Exception e) { throw new IllegalStateException(e); } }
        void ready() throws Exception { await(() -> !busy()); assertThat(failed.get()).isNull(); }
        java.util.List<String> items() throws Exception {
            var list = list(); var result = new ArrayList<String>();
            var size = (int) list.getClass().getMethod("size").invoke(list);
            for (var i = 0; i < size; i++) result.add((String) list.getClass().getMethod("getString", int.class).invoke(list, i));
            return result;
        }
        void select(String name) throws Exception {
            var list = list(); var index = items().indexOf(name); assertThat(index).isGreaterThanOrEqualTo(0);
            list.getClass().getMethod("setSelectedIndex", int.class, boolean.class).invoke(list, index, true);
            action(type("javax.microedition.lcdui.List").getField("SELECT_COMMAND").get(null));
        }
        void command(String name) throws Exception { action(field(name).get(picker)); }
        void action(Object command) throws Exception {
            picker.getClass().getMethod("commandAction", type("javax.microedition.lcdui.Command"), type("javax.microedition.lcdui.Displayable"))
                    .invoke(picker, command, list());
        }
        Field field(String name) throws Exception { var f = picker.getClass().getDeclaredField(name); f.setAccessible(true); return f; }
        public void close() throws Exception { loader.close(); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).as("picker operation completed").isTrue();
    }
}

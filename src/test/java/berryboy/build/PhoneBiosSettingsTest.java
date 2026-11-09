package berryboy.build;

import static org.assertj.core.api.Assertions.assertThat;
import berryboy.mobile.library.LibraryProfile;
import berryboy.mobile.ui.PhoneScreen;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual native controller/session, with filesystem, RMS and LCDUI host doubles. */
@Tag("build")
class PhoneBiosSettingsTest {
    @TempDir Path temporary;

    @Test
    void validatesAndPersistsBiosWithoutARomThenPrefillsTheNextLaunch() throws Exception {
        var bios = temporary.resolve("user BIOS.bin"); Files.write(bios, new byte[16384]);
        try (var h = harness()) {
            h.select("Display & audio"); h.select("BIOS file");
            assertThat(h.title()).isEqualTo("GBA BIOS");
            h.text(0, bios.toUri().toASCIIString()); h.command("Load BIOS");
            h.awaitMessage("BIOS saved");
            assertThat(h.library().bios()).isEqualTo(bios.toUri().toASCIIString());
            assertThat(h.screen().value(8)).isEqualTo("Selected");
            h.reopen();
            h.select("Open game");
            assertThat(h.text(1)).isEqualTo(bios.toUri().toASCIIString());
            assertThat(Files.readAllBytes(bios)).hasSize(16384).containsOnly((byte) 0);
        }
    }

    @Test
    void rejectsMissingTruncatedOversizedAndDirectoryInputsWithoutReplacingTheSavedBios() throws Exception {
        var good = temporary.resolve("good.bin"); Files.write(good, new byte[16384]);
        var shortFile = temporary.resolve("short.bin"); Files.write(shortFile, new byte[16383]);
        var large = temporary.resolve("large.bin"); Files.write(large, new byte[16385]);
        var zero = temporary.resolve("empty.bin"); Files.write(zero, new byte[0]);
        try (var h = harness()) {
            h.select("Display & audio"); h.select("BIOS file"); h.text(0, good.toUri().toASCIIString());
            h.command("Load BIOS"); h.awaitMessage("BIOS saved");
            for (var path : new Path[]{shortFile, large, zero, temporary.resolve("missing.bin"), temporary}) {
                h.select("BIOS file"); h.text(0, path.toUri().toASCIIString()); h.command("Load BIOS");
                await(() -> h.screen().message().contains("IOException"));
                assertThat(h.screen().title()).isEqualTo("Display & audio");
                assertThat(h.library().bios()).isEqualTo(good.toUri().toASCIIString());
            }
            h.select("BIOS file"); h.text(0, ""); h.command("Load BIOS");
            h.awaitMessage("Select a GBA BIOS");
            assertThat(h.library().bios()).isEqualTo(good.toUri().toASCIIString());
        }
    }

    @Test
    void cancellationAndRecordFailureKeepThePreviousPreference() throws Exception {
        var good = temporary.resolve("good.bin"); Files.write(good, new byte[16384]);
        try (var h = harness()) {
            h.select("Display & audio"); h.select("BIOS file"); h.text(0, good.toUri().toASCIIString());
            h.command("Back");
            assertThat(h.library().bios()).isEmpty();
            h.select("BIOS file"); h.text(0, good.toUri().toASCIIString());
            h.type("javax.microedition.rms.RecordStore").getField("failCommit").setBoolean(null, true);
            h.command("Load BIOS"); h.awaitMessage("BIOS path not saved");
            assertThat(h.library().bios()).isEmpty();
            h.reopen(); h.select("Open game"); assertThat(h.text(1)).isEmpty();
        }
    }

    @Test
    void browsingUsesTheRealPickerAndRequiresLoadBeforePersisting() throws Exception {
        var folder = Files.createDirectory(temporary.resolve("storage"));
        var bios = folder.resolve("gba_bios.bin"); Files.write(bios, new byte[16384]);
        Files.write(folder.resolve("game.gba"), new byte[512]);
        try (var h = harness()) {
            h.type("javax.microedition.io.file.FileSystemRegistry").getField("roots")
                    .set(null, new String[]{folder.toString().substring(1) + "/"});
            h.select("Display & audio"); h.select("BIOS file"); h.command("Browse BIOS");
            h.choose(folder.toString().substring(1) + "/"); h.choose("gba_bios.bin");
            assertThat(h.title()).isEqualTo("GBA BIOS");
            assertThat(h.text(0)).isEqualTo(bios.toUri().toASCIIString());
            assertThat(h.library().bios()).isEmpty();
            h.command("Load BIOS"); h.awaitMessage("BIOS saved");
            assertThat(h.library().bios()).isEqualTo(bios.toUri().toASCIIString());
        }
    }

    private Harness harness() throws Exception {
        var root = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        var classes = temporary.resolve("classes");
        assertThat(PhoneHarness.compileUi(root, classes)).isZero();
        return new Harness(new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader()));
    }
    private static final class Harness implements AutoCloseable {
        final URLClassLoader loader;
        Object ui;
        Harness(URLClassLoader loader) throws Exception { this.loader = loader; reopen(); }
        Class<?> type(String name) throws Exception { return loader.loadClass(name); }
        Object call(String name, Class<?>[] signature, Object... args) throws Exception { return ui.getClass().getMethod(name, signature).invoke(ui, args); }
        void reopen() throws Exception { if (ui != null) call("close", new Class<?>[0]); ui = type("berryboy.phone.UiHarness").getConstructor().newInstance(); }
        void select(String label) throws Exception { call("select", new Class<?>[]{String.class}, label); }
        void choose(String label) throws Exception { call("choose", new Class<?>[]{String.class}, label); }
        void command(String label) throws Exception { call("command", new Class<?>[]{String.class}, label); }
        void text(int index, String value) throws Exception { call("text", new Class<?>[]{int.class, String.class}, index, value); }
        String text(int index) throws Exception { return (String) call("text", new Class<?>[]{int.class}, index); }
        PhoneScreen screen() { try { return (PhoneScreen) call("screen", new Class<?>[0]); } catch (Exception e) { throw new IllegalStateException(e); } }
        String title() throws Exception {
            var display = ui.getClass().getField("display").get(ui);
            var current = display.getClass().getMethod("getCurrent").invoke(display);
            return (String) current.getClass().getMethod("getTitle").invoke(current);
        }
        LibraryProfile library() throws Exception {
            var canvas = ui.getClass().getField("canvas").get(ui);
            var field = canvas.getClass().getDeclaredField("library"); field.setAccessible(true);
            synchronized (canvas) { return ((LibraryProfile) field.get(canvas)).copy(); }
        }
        void awaitMessage(String text) throws Exception { await(() -> screen().message().contains(text)); }
        public void close() throws Exception { call("close", new Class<?>[0]); loader.close(); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).as("native controller callback completed").isTrue();
    }
}

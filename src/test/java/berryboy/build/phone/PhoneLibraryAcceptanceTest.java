package berryboy.build.phone;

import static org.assertj.core.api.Assertions.assertThat;

import berryboy.DemoRom;
import berryboy.MachineState;
import berryboy.build.PhoneHarness;
import berryboy.cartridge.Cartridge;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File picker, persisted paths and startup through the production controller and session. */
@Tag("build")
class PhoneLibraryAcceptanceTest {
    @TempDir Path temporary;

    @Test
    void browsesAnotherFolderAndBiosThenPreservesThePausedGameAcrossCancellationAndReadFailures() throws Exception {
        var storage = Files.createDirectory(temporary.resolve("storage"));
        var first = Files.createDirectory(storage.resolve("Old games"));
        var folder = Files.createDirectory(storage.resolve("New games"));
        var firmware = Files.createDirectory(storage.resolve("Firmware"));
        var rom = DemoRom.create(); var bios = directBios();
        var game = folder.resolve("Demo game.GBA"); Files.write(game, rom);
        Files.write(folder.resolve("Archive.zip"), new byte[32]);
        var biosFile = firmware.resolve("External BIOS.BIN"); Files.write(biosFile, bios);
        var alternate = bios.clone(); alternate[16383] = 1;
        var nextBios = firmware.resolve("Next BIOS.bin"); Files.write(nextBios, alternate);
        var shortBios = firmware.resolve("Short.bin"); Files.write(shortBios, new byte[16383]);
        var largeBios = firmware.resolve("Large.bin"); Files.write(largeBios, new byte[16385]);
        var denied = firmware.resolve("Unreadable.bin"); Files.write(denied, bios);
        var badRom = folder.resolve("Broken.gba"); Files.write(badRom, new byte[16]);
        var rootName = storage.toString().substring(1) + "/";
        try (var h = harness(game, rom)) {
            h.storageRoots(rootName);
            h.select("ROM folder"); h.text(0, first.toUri().toASCIIString()); h.command("Save folder");
            h.reopen(); h.select("ROM folder");
            assertThat(h.text(0)).isEqualTo(first.toUri().toASCIIString());
            h.command("Browse folders"); h.choose(".."); h.choose("New games/");
            h.awaitTitle("Choose ROM folder");
            h.command("Use this folder");
            assertThat(h.text(0)).isEqualTo(folder.toUri().toASCIIString());
            h.command("Back"); h.reopen(); h.select("ROM folder");
            assertThat(h.text(0)).as("cancelled folder draft is not persisted").isEqualTo(first.toUri().toASCIIString());
            h.command("Browse folders"); h.awaitTitle("Choose ROM folder");
            h.choose(".."); h.choose("New games/"); h.awaitTitle("Choose ROM folder");
            h.command("Use this folder"); h.command("Save folder");

            h.reopen(); h.select("Open game");
            assertThat(h.text(0)).isEqualTo(folder.toUri().toASCIIString());
            h.command("Browse ROM"); h.awaitTitle("Choose GBA ROM");
            assertThat(h.choices()).contains("Demo game.GBA").doesNotContain("Archive.zip");
            h.choose("Demo game.GBA");
            assertThat(h.text(0)).isEqualTo(game.toUri().toASCIIString());
            h.command("Browse BIOS"); h.choose(rootName); h.choose("Firmware/");
            h.choose("External BIOS.BIN");
            assertThat(h.text(1)).isEqualTo(biosFile.toUri().toASCIIString());
            h.command("Start game"); h.frames(3); h.pause();
            var before = h.save(0);

            h.select("Home"); h.select("Open game"); h.command("Browse ROM"); h.command("Back");
            assertThat(h.text(0)).isEqualTo(game.toUri().toASCIIString());
            h.command("Back"); h.select("Display & audio"); h.command("Back");
            assertThat(h.save(1)).as("cancelled browsing preserves every guest field").isEqualTo(before);

            h.unreadableFile(denied);
            for (var file : new Path[]{shortBios, largeBios, denied, firmware.resolve("Missing.bin"), firmware}) {
                h.select("Display & audio"); h.select("BIOS file");
                h.text(0, file.toUri().toASCIIString()); h.command("Load BIOS");
                h.messageContaining("IOException"); h.idle(); h.command("Back");
                assertThat(h.save(1)).as("failed BIOS selection: %s", file.getFileName()).isEqualTo(before);
            }
            h.unreadableFile(null);

            h.select("Display & audio"); h.select("BIOS file"); h.command("Browse BIOS"); h.command("Back");
            h.command("Back"); h.command("Back");
            assertThat(h.save(1)).isEqualTo(before);
            h.select("Display & audio"); h.select("BIOS file");
            h.text(0, nextBios.toUri().toASCIIString()); h.command("Load BIOS");
            h.messageContaining("BIOS saved"); h.idle(); h.command("Back");
            assertThat(h.save(1)).as("a new preference leaves the running BIOS and guest unchanged").isEqualTo(before);

            h.select("Home"); h.select("Open game");
            h.text(0, badRom.toUri().toASCIIString()); h.command("Start game");
            h.messageContaining("GBA ROM must"); h.idle();
            assertThat(h.save(1)).as("failed ROM launch preserves the previous session").isEqualTo(before);
            h.select("Home"); h.select("Open game");
            h.text(1, shortBios.toUri().toASCIIString()); h.command("Start game");
            h.messageContaining("exactly 16 KiB"); h.idle();
            assertThat(h.save(1)).as("failed launch BIOS preserves the previous session").isEqualTo(before);
            assertThat(h.slot(0)).isEqualTo(before);

            h.select("Resume game"); h.frames(3); h.pause();
            assertThat(MachineState.load(rom, bios, h.save(2)).clock.now())
                    .isGreaterThan(MachineState.load(rom, bios, before).clock.now());
            h.reopen(); h.select("Open game");
            assertThat(h.text(0)).isEqualTo(game.toUri().toASCIIString());
            assertThat(h.text(1)).isEqualTo(nextBios.toUri().toASCIIString());
            h.command("Start game"); h.frames(3); h.pause();
            assertThat(MachineState.load(rom, alternate, h.save(3)).memory.hasBios()).isTrue();
        }
    }

    @Test
    void persistsShowSkipStartupAndUsesTheSelectedPolicyOnConfirmedRestart() throws Exception {
        var rom = DemoRom.create(); var bios = delayedRedBios();
        var game = temporary.resolve("startup.gba"); var biosFile = temporary.resolve("startup.bin");
        Files.write(game, rom); Files.write(biosFile, bios);
        try (var h = harness(game, rom)) {
            h.select("Open game"); h.text(0, game.toUri().toASCIIString()); h.text(1, biosFile.toUri().toASCIIString());
            h.command("Start game"); h.frames(3); h.pause();
            assertThat(h.firstFrame()[10]).as("Show presents BIOS pixels after early scanline register setup").isEqualTo(0xff0000);
            h.select("Display & audio"); h.select("GBA boot animation");
            assertThat(h.screen().value(6)).isEqualTo("Skip");
            h.reopen(); h.select("Display & audio");
            assertThat(h.screen().value(6)).isEqualTo("Skip");
            h.command("Back"); h.select("Open game"); h.command("Start game"); h.frames(3); h.pause();
            assertThat(h.firstFrame()[0]).as("Skip still executes BIOS but first presents cartridge pixels").isZero();
            var afterSkip = h.save(0);
            assertThat(MachineState.load(rom, bios, afterSkip).clock.now()).isGreaterThan(3 * 280896L);
            h.select("Display & audio"); h.select("GBA boot animation"); h.command("Back"); h.idle();
            h.select("Restart game"); h.command("Confirm"); h.message("Game restarted"); h.idle();
            h.resetFirstFrame(); h.select("Resume game"); h.frames(3); h.pause();
            assertThat(h.firstFrame()[10]).as("confirmed restart applies Show again").isEqualTo(0xff0000);
            h.reopen(); h.select("Display & audio");
            assertThat(h.screen().value(6)).isEqualTo("Show");
        }
    }

    private PhoneUiDriver harness(Path game, byte[] rom) throws Exception {
        var root = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        var classes = temporary.resolve("classes");
        assertThat(PhoneHarness.compileUi(root, classes)).isZero();
        return new PhoneUiDriver(classes, game, new Cartridge(rom).checksum());
    }
    private static byte[] directBios() {
        var bytes = new byte[16384]; word(bytes, 0, 0xe59ff018); word(bytes, 0x20, 0x08000000); return bytes;
    }
    private static byte[] delayedRedBios() {
        var bytes = new byte[16384];
        word(bytes, 0, 0xe59f0078); word(bytes, 0x80, 0x04000000);
        word(bytes, 4, 0xe3a01b01); word(bytes, 8, 0xe2811003); word(bytes, 12, 0xe1c010b0);
        word(bytes, 0x10, 0xe59f006c); word(bytes, 0x84, 0x06000000);
        word(bytes, 0x14, 0xe3a0101f); word(bytes, 0x18, 0xe1c010b0);
        word(bytes, 0x1c, 0xe59f2064); word(bytes, 0x88, 100000);
        word(bytes, 0x20, 0xe2522001); word(bytes, 0x24, 0x1afffffd);
        word(bytes, 0x28, 0xe59ff05c); word(bytes, 0x8c, 0x08000000);
        return bytes;
    }
    private static void word(byte[] bytes, int at, int value) {
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (value >>> (i * 8));
    }
}

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

/** Actual controller/session/storage with host platform adapters, using committed snapshots only. */
@Tag("build")
class PhoneCheatsAcceptanceTest {
    @TempDir Path temporary;

    @Test
    void editsRawAndBothCodeBreakerTypesAndPersistsProfilesWithoutRestoringThemFromSlots() throws Exception {
        var rom = DemoRom.create();
        var bios = new byte[16384];
        word(bios, 0, 0xe59ff018); word(bios, 0x20, 0x08000000);
        var file = temporary.resolve("cheats.gba"); var biosFile = temporary.resolve("bios.bin");
        Files.write(file, rom); Files.write(biosFile, bios);
        var classes = temporary.resolve("classes");
        var root = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        assertThat(PhoneHarness.compileUi(root, classes)).isZero();
        try (var h = new PhoneUiDriver(classes, file, new Cartridge(rom).checksum())) {
            h.select("Open game");
            h.text(0, file.toUri().toASCIIString()); h.text(1, biosFile.toUri().toASCIIString());
            h.command("Start game"); h.frames(3); h.pause();
            var clean = h.save(0);
            h.select("Cheats");

            h.select("Add cheat"); h.text(0, "Cancelled draft"); h.command("Back");
            assertThat(h.screen().count()).isEqualTo(1);
            h.select("Add cheat"); h.text(0, "Invalid draft");
            h.text(2, "not a code"); h.command("Add");
            assertThat(h.title()).contains("Use ADDRESS");
            h.choice(1, 1); h.text(2, "D2000000 0000"); h.command("Add");
            assertThat(h.title()).contains("Supported CodeBreaker types");
            h.choice(1, 0); h.text(2, "08000000:16:1234"); h.command("Add");
            assertThat(h.title()).contains("EWRAM/IWRAM");
            h.command("Back");
            assertThat(h.screen().count()).isEqualTo(1);
            assertThat(h.slot(0)).isEqualTo(clean);

            var names = new String[]{"Raw halfword", "CodeBreaker byte", "CodeBreaker halfword"};
            var sources = new String[]{"02000100:16:1234", "32000100 00AB", "82000100 BEEF"};
            var values = new int[]{0x1234, 0xab, 0xbeef};
            for (int index = 0; index < names.length; index++) {
                var name = names[index];
                h.select("Add cheat"); h.text(0, name); h.choice(1, index == 0 ? 0 : 1);
                h.text(2, sources[index]); h.command("Add"); h.idle();
                assertThat(h.screen().count()).isEqualTo(2);
                assertThat(h.screen().value(1)).isEqualTo("On");
                h.command("Back");
                h.load(0); h.select("Resume game"); h.frames(3); h.pause();
                assertValue(h.save(1), rom, bios, values[index]);

                h.select("Cheats"); h.select(name); h.idle();
                assertThat(h.screen().value(1)).isEqualTo("Off");
                h.command("Back");
                // Disabling a RAM write is not an undo. Restore clean RAM to observe non-application.
                h.load(0); h.select("Resume game"); h.frames(3); h.pause();
                assertValue(h.save(2), rom, bios, 0);

                h.reopen(); h.select("Open game"); h.command("Start game"); h.frames(3); h.pause();
                h.select("Cheats");
                assertThat(h.screen().label(1)).isEqualTo(name);
                assertThat(h.screen().value(1)).isEqualTo("Off");
                h.command("Back");
                h.load(0); h.select("Resume game"); h.frames(3); h.pause();
                assertValue(h.save(2), rom, bios, 0);

                h.select("Cheats"); h.select(name); h.idle(); h.command("Back");
                h.reopen(); h.select("Open game"); h.command("Start game"); h.frames(3); h.pause();
                h.select("Cheats");
                assertThat(h.screen().label(1)).isEqualTo(name);
                assertThat(h.screen().value(1)).isEqualTo("On");
                h.command("Back");
                // The clean slot contains no RAM effect, while the separate active profile remains enabled.
                h.load(0); h.select("Resume game"); h.frames(3); h.pause();
                assertValue(h.save(3), rom, bios, values[index]);

                h.select("Cheats"); h.press('k'); h.release('k'); h.command("Delete cheat"); h.idle();
                assertThat(h.screen().count()).isEqualTo(1);
                h.command("Back");
                h.load(0); h.select("Resume game"); h.frames(3); h.pause();
                assertValue(h.save(4), rom, bios, 0);
                h.select("Cheats");
            }
            h.reopen(); h.select("Open game"); h.command("Start game"); h.frames(3); h.pause();
            h.select("Cheats"); assertThat(h.screen().count()).isEqualTo(1);
            assertThat(h.slot(0)).isEqualTo(clean);
        }
    }

    private static void assertValue(byte[] snapshot, byte[] rom, byte[] bios, int expected) {
        var owned = MachineState.load(rom, bios, snapshot);
        assertThat(owned.memory.peek16(0x02000100)).isEqualTo(expected);
        assertThat(owned.memory.peek16(0x02000102)).as("adjacent guest bytes remain untouched").isZero();
    }
    private static void word(byte[] bytes, int at, int value) {
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (value >>> (i * 8));
    }
}

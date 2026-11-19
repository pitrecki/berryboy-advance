package berryboy.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import berryboy.DemoRom;
import berryboy.build.phone.PhoneUiDriver;
import berryboy.MachineState;
import berryboy.cartridge.Cartridge;
import berryboy.input.Keypad;
import berryboy.mobile.ui.PhoneMenus;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Production controller journey with host platform adapters and guest-observed input. */
@Tag("build")
class PhoneControlsAcceptanceTest {
    @TempDir Path temporary;

    @Test
    void remapsEveryButtonCombinesShoulderLocksAndClearsInputAcrossPauseFocusAndSlotReload() throws Exception {
        var rom = inputCartridge();
        var bios = new byte[16384];
        word(bios, 0, 0xe59ff018); // Reset vector loads the synthetic cartridge entry.
        word(bios, 0x20, 0x08000000);
        var file = temporary.resolve("input.gba");
        var biosFile = temporary.resolve("bios.bin");
        Files.write(file, rom); Files.write(biosFile, bios);
        var root = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        var classes = temporary.resolve("classes");
        assertThat(PhoneHarness.compileUi(root, classes)).isZero();
        try (var h = new PhoneUiDriver(classes, file, new Cartridge(rom).checksum())) {
            h.select("Controls");
            h.select("L/R assist");
            for (int button = 0; button < 10; button++) {
                h.select(PhoneMenus.buttonName(button));
                h.press(key(button)); h.release(key(button));
            }
            h.command("Back");
            h.select("Open game");
            h.text(0, file.toUri().toASCIIString()); h.text(1, biosFile.toUri().toASCIIString());
            h.command("Start game"); h.frames(3); h.pause();

            // Exercise every remapping at the guest's KEYINPUT register, through all five slots.
            for (int button = 0; button < 10; button++) {
                h.select("Resume game");
                h.press(key(button));
                if (button >= 8) h.release(key(button)); // Shoulder stays locked after physical release.
                h.frames(3); h.pause();
                var state = h.save(button % 5);
                assertThat(MachineState.load(rom, bios, state).memory.peek16(0x02000000))
                        .as("guest observed remapped %s", PhoneMenus.buttonName(button))
                        .isEqualTo(Keypad.ALL_BUTTONS ^ (1 << button));
                h.release(key(button));
                h.select("Resume game"); h.frames(3); h.pause();
                assertNeutral(h.save(button % 5), rom, bios);
            }

            h.select("Resume game");
            h.press(key(8)); h.release(key(8)); h.press(key(9)); h.release(key(9));
            h.press(key(0)); h.press(key(1)); h.press(key(4));
            h.frames(3);
            assertThat(h.screen().shoulderLocks()).isEqualTo(Keypad.L | Keypad.R);
            h.pause();
            var combined = h.save(0);
            assertThat(MachineState.load(rom, bios, combined).memory.peek16(0x02000000))
                    .isEqualTo(Keypad.ALL_BUTTONS ^ (Keypad.L | Keypad.R | Keypad.A | Keypad.B | Keypad.RIGHT));
            assertThat(h.screen().shoulderLocks()).isZero();

            // Loading restores progress, while the next guest frame must receive released keys.
            h.select("Load state"); h.idle(); h.select("Slot 1"); h.message("Loaded slot 1"); h.idle();
            h.select("Resume game"); h.frames(3); h.pause();
            var neutral = h.save(1);
            assertNeutral(neutral, rom, bios);

            h.select("Resume game");
            h.press(key(8)); h.release(key(8)); h.press(key(4)); h.frames(3);
            h.canvas("suspend"); h.idle();
            assertThat(h.screen().shoulderLocks()).isZero();
            h.canvas("open"); h.select("Resume game"); h.frames(3); h.pause();
            assertNeutral(h.save(2), rom, bios);

            // Cancelling an overwrite must leave the committed bytes intact.
            var before = h.slot(0);
            h.select("Save state"); h.idle(); h.select("Slot 1");
            assertThat(h.title()).isEqualTo("Overwrite slot 1?");
            h.command("Back"); h.command("Back");
            assertThat(h.slot(0)).isEqualTo(before);

            h.reopen(); h.select("Controls");
            assertSoftly(softly -> {
                softly.assertThat(h.screen().value(0)).isEqualTo("Tap to lock");
                for (int button = 0; button < 10; button++) {
                    softly.assertThat(h.screen().value(button + 1)).isEqualTo("" + (char) key(button));
                }
                softly.assertThat(h.screen().shoulderLocks()).isZero();
            });
            h.command("Back"); h.select("Open game");
            h.command("Start game"); h.frames(3); h.pause();
            h.select("Load state"); h.idle();
            for (int slot = 0; slot < 5; slot++) {
                assertThat(h.screen().enabled(slot)).as("committed slot %s survives reopen", slot + 1).isTrue();
            }
            h.select("Slot 2"); h.message("Loaded slot 2"); h.idle();
            h.select("Resume game"); h.frames(3); h.pause();
            assertNeutral(h.save(4), rom, bios);

            h.select("Resume game");
            h.press(key(8)); h.release(key(8)); h.press(key(9)); h.release(key(9));
            h.press(key(0)); h.press(key(4)); h.frames(3); h.pause();
            h.select("Restart game"); h.command("Confirm"); h.message("Game restarted"); h.idle();
            h.select("Resume game"); h.release(key(0)); h.release(key(4)); h.frames(12); h.pause();
            assertThat(h.screen().shoulderLocks()).isZero();
            assertNeutral(h.save(3), rom, bios);

            var previousSlot = h.slot(3);
            var otherGame = temporary.resolve("other-input.gba"); Files.write(otherGame, rom);
            h.select("Resume game");
            h.press(key(8)); h.release(key(8)); h.press(key(0)); h.press(key(4)); h.frames(3); h.pause();
            h.select("Home"); h.select("Open game"); h.text(0, otherGame.toUri().toASCIIString());
            h.command("Start game"); h.frames(12); h.release(key(0)); h.release(key(4)); h.frames(3); h.pause();
            h.select("Save state"); h.idle(); h.select("Slot 1"); h.message("Saved slot 1"); h.idle();
            assertThat(h.screen().shoulderLocks()).isZero();
            assertNeutral(h.slot(otherGame, new Cartridge(rom).checksum(), 0), rom, bios);
            assertThat(h.slot(3)).as("changing games leaves the previous game's committed slots intact").isEqualTo(previousSlot);
        }
    }

    private static void assertNeutral(byte[] state, byte[] rom, byte[] bios) {
        assertThat(MachineState.load(rom, bios, state).memory.peek16(0x02000000))
                .as("the running guest sees every key released").isEqualTo(Keypad.ALL_BUTTONS);
    }

    private static int key(int button) { return button == 9 ? '0' : '1' + button; }

    private static byte[] inputCartridge() {
        var rom = DemoRom.create();
        half(rom, 0xfa, 0xe009); // Continue from the demo's completed frame into the input sampler.
        half(rom, 0x110, 0x4803); // LDR r0, KEYINPUT address
        half(rom, 0x112, 0x8801); // LDRH r1, [r0]
        half(rom, 0x114, 0x4a03); // LDR r2, EWRAM destination
        half(rom, 0x116, 0x8011); // STRH r1, [r2]
        half(rom, 0x118, 0xe7fa); // B sampler
        word(rom, 0x120, 0x04000130); word(rom, 0x124, 0x02000000);
        return rom;
    }
    private static void half(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value; bytes[at + 1] = (byte) (value >>> 8);
    }
    private static void word(byte[] bytes, int at, int value) {
        half(bytes, at, value); half(bytes, at + 2, value >>> 16);
    }

}

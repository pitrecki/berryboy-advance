package berryboy.build.phone;

import static org.assertj.core.api.Assertions.assertThat;

import berryboy.DemoRom;
import berryboy.Machine;
import berryboy.MachineState;
import berryboy.build.PhoneHarness;
import berryboy.cartridge.Cartridge;
import berryboy.state.StateBuffer;
import berryboy.state.StateChecksum;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** All five slots, rejected external payloads and interrupted writes through real UI commands. */
@Tag("build")
class PhoneSlotsAcceptanceTest {
    @TempDir Path temporary;

    @Test
    void reloadsAllSlotsRejectsIncompatiblePayloadsAndRecoversFromFailedWritesAndLegacyFormats() throws Exception {
        var rom = DemoRom.create(); var bios = new byte[16384];
        word(bios, 0, 0xe59ff018); word(bios, 0x20, 0x08000000);
        var game = temporary.resolve("slots.gba"); var biosFile = temporary.resolve("bios.bin");
        Files.write(game, rom); Files.write(biosFile, bios);
        var checksum = new Cartridge(rom).checksum();
        var classes = temporary.resolve("classes");
        var root = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        assertThat(PhoneHarness.compileUi(root, classes)).isZero();
        try (var h = new PhoneUiDriver(classes, game, checksum)) {
            h.select("Open game"); h.text(0, game.toUri().toASCIIString()); h.text(1, biosFile.toUri().toASCIIString());
            h.command("Start game"); h.frames(3); h.pause();
            var slots = new byte[5][];
            for (int slot = 0; slot < 5; slot++) slots[slot] = h.save(slot);
            for (int slot = 0; slot < 5; slot++) {
                h.load(slot); h.select("Resume game"); h.frames(slot + 1); h.pause();
                var later = h.save(slot);
                assertThat(MachineState.load(rom, bios, later).clock.now())
                        .isGreaterThan(MachineState.load(rom, bios, slots[slot]).clock.now());
                slots[slot] = later;
            }
            h.reopen(); h.select("Open game"); h.command("Start game"); h.frames(3); h.pause();
            for (int slot = 0; slot < 5; slot++) {
                h.load(slot);
                assertThat(h.save(slot)).as("complete slot %s survives controller reopen and immediate resave", slot + 1)
                        .isEqualTo(slots[slot]);
            }

            var before = h.save(2); var originalSlot = h.slot(0);
            var otherRom = rom.clone(); otherRom[511] ^= 1;
            var otherBios = bios.clone(); otherBios[16383] ^= 1;
            var damaged = before.clone(); damaged[100] ^= 1;
            var unknown = before.clone(); word(unknown, 4, 4);
            word(unknown, unknown.length - 4, StateChecksum.bytes(unknown, unknown.length - 4));
            var rejected = new byte[][]{MachineState.save(new Machine(otherRom, bios)),
                    MachineState.save(new Machine(rom, otherBios)), damaged, new byte[28], unknown};
            var messages = new String[]{"another ROM", "another BIOS", "checksum mismatch", "Invalid state size", "format/version"};
            for (int index = 0; index < rejected.length; index++) {
                h.replaceSlot(3, rejected[index]);
                h.select("Load state"); h.idle(); h.select("Slot 4");
                h.messageContaining(messages[index]); h.idle();
                assertThat(h.save(2)).as("rejected load keeps every field of the paused game").isEqualTo(before);
                assertThat(h.slot(0)).as("another committed slot remains intact").isEqualTo(originalSlot);
            }

            h.replaceSlot(3, before);
            for (int generation = 0; generation < 2; generation++) {
                var file = Path.of(game + ".berryboy-" + Integer.toHexString(checksum) + "-st3-" + generation);
                if (Files.exists(file)) {
                    var bytes = Files.readAllBytes(file); bytes[bytes.length - 1] ^= 1; Files.write(file, bytes);
                }
            }
            h.select("Load state"); h.idle(); h.select("Slot 4");
            h.messageContaining("Incomplete save generation"); h.idle();
            assertThat(h.save(2)).isEqualTo(before);
            h.replaceSlot(3, before); h.load(3);

            h.select("Save state"); h.idle(); h.select("Slot 1");
            h.failWriteAfter(13); h.command("Confirm");
            h.messageContaining("Injected partial write"); h.idle();
            assertThat(h.slot(0)).as("failed overwrite never publishes its partial generation").isEqualTo(originalSlot);
            assertThat(h.save(2)).isEqualTo(before);
            assertThat(h.save(0)).as("the same overwrite can be retried").isEqualTo(before);
            h.reopen(); h.select("Open game"); h.command("Start game"); h.frames(3); h.pause();
            h.load(0); assertThat(h.save(2)).isEqualTo(before);

            for (int version : new int[]{1, 2}) {
                var legacy = legacyState(MachineState.load(rom, bios, before), version);
                var unchanged = legacy.clone();
                var expected = MachineState.save(MachineState.load(rom, bios, legacy));
                h.replaceSlot(3, legacy); h.load(3);
                var migrated = h.save(3);
                assertThat(migrated[4]).as("current-format save after loading version %s", version).isEqualTo((byte) 3);
                assertThat(migrated).isEqualTo(expected);
                assertThat(legacy).isEqualTo(unchanged);
                h.select("Resume game"); h.frames(3); h.pause();
                assertThat(MachineState.load(rom, bios, h.save(4)).clock.now())
                        .isGreaterThan(MachineState.load(rom, bios, expected).clock.now());
            }
        }
    }

    /** Published old writers, independent of the current MachineState.save version/header. */
    private static byte[] legacyState(Machine owned, int version) {
        var state = new StateBuffer(); state.value(0x53594242); state.value(version);
        state.value(owned.cartridge.size()); state.value(owned.cartridge.checksum());
        state.flag(owned.memory.hasBios()); state.value(owned.memory.biosChecksum());
        state.value(owned.memory.backup().type()); owned.memory.transferState(state); owned.cpu.transferState(state);
        if (version == 2) owned.cpu.transferPipelineState(state, false);
        var payload = state.finish(); state.value(StateChecksum.bytes(payload, payload.length));
        return state.finish();
    }
    private static void word(byte[] bytes, int at, int value) {
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (value >>> (i * 8));
    }
}

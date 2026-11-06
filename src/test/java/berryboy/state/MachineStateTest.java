package berryboy.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import berryboy.DemoRom;
import berryboy.Machine;
import berryboy.MachineState;
import berryboy.cartridge.CartridgeBackup;
import berryboy.video.LcdTiming;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("core")
class MachineStateTest {
    @ParameterizedTest
    @CsvSource({"0xe890000e,0", "0xe890000e,1", "0xe890000e,3", "0xea000000,0", "0xea000000,1", "0xea000000,5"})
    void restoresDataAndBranchRefillsSuspendedAtHblankDma(long opcode, int extraSteps) {
        var rom = DemoRom.create(); var m = new Machine(rom);
        m.cpu.setPc(0x03000000); m.cpu.setRegister(0, 0x02000000);
        m.memory.write32(0x03000000, (int) opcode);
        m.memory.write32(0x03000004, 0xeafffffe); m.memory.write32(0x03000008, 0xeafffffe);
        m.memory.write32(0x02000000, 0x12345678);
        m.memory.write32(0x040000d4, 0x02030000); m.memory.write32(0x040000d8, 0x03001000);
        m.memory.write16(0x040000dc, 16); m.memory.write16(0x040000de, 0xa400);
        m.clock.advanceTo(959); m.cpu.step();
        assertThat(m.cpu.instructionInFlight()).isTrue();
        for (var i = 0; i < extraSteps; i++) m.cpu.step();
        var restored = MachineState.load(rom, null, MachineState.save(m));
        m.runCycles(2000); restored.runCycles(2000);
        assertThat(MachineState.save(restored)).isEqualTo(MachineState.save(m));
    }

    @Test
    void restoresAllRegisterBanks() {
        var rom = DemoRom.create(); var m = new Machine(rom);
        var modes = new int[]{0x10, 0x11, 0x12, 0x13, 0x17, 0x1b, 0x1f};
        for (var mode : modes) {
            m.cpu.setCpsr(mode | 0xc0);
            for (var r = 8; r < 15; r++) m.cpu.setRegister(r, mode * 100 + r);
        }
        var copy = MachineState.load(rom, null, MachineState.save(m));
        for (var mode : modes) {
            m.cpu.setCpsr(mode | 0xc0); copy.cpu.setCpsr(mode | 0xc0);
            for (var r = 8; r < 15; r++) assertThat(copy.cpu.register(r)).isEqualTo(m.cpu.register(r));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 7, 15, 31, 65, 99})
    void resumesDmaAndPendingCpuWorkExactly(int steps) {
        var rom = DemoRom.create();
        var original = new Machine(rom);
        original.memory.clockedAudio().setCapture(true);
        original.memory.write32(0x040000d4, 0x08000000);
        original.memory.write32(0x040000d8, 0x02000100);
        original.memory.write16(0x040000dc, 40);
        original.memory.write16(0x040000de, 0x8400);
        original.memory.write16(0x04000100, 0xff80);
        original.memory.write16(0x04000102, 0xc0);
        for (var i = 0; i < steps; i++) original.cpu.step();
        var before = original.clock.now();
        var image = MachineState.save(original);
        var restored = MachineState.load(rom, null, image);
        assertSoftly(s -> {
            s.assertThat(original.clock.now()).isEqualTo(before);
            s.assertThat(MachineState.save(restored)).isEqualTo(image);
            s.assertThat(restored.cpu.fetchInFlight()).isEqualTo(original.cpu.fetchInFlight());
            s.assertThat(restored.cpu.instructionInFlight()).isEqualTo(original.cpu.instructionInFlight());
        });
        original.runCycles(18000);
        restored.runCycles(18000);
        assertThat(MachineState.save(restored)).isEqualTo(MachineState.save(original));
    }

    @Test
    void restoresRasterSoundTimersAndInputAcrossSeveralFrames() {
        var rom = DemoRom.create();
        var original = new Machine(rom);
        original.runInstructions(DemoRom.RUN_BUDGET);
        original.memory.clockedAudio().setCapture(true);
        original.memory.write16(0x04000084, 0x80);
        original.memory.write16(0x04000080, 0x2277);
        original.memory.write16(0x04000082, 2);
        original.memory.write16(0x04000068, 0xf080);
        original.memory.write16(0x0400006c, 0x8700);
        original.memory.setPressedKeys(0x101);
        original.runCycles(12345);
        var restored = MachineState.load(rom, null, MachineState.save(original));
        for (var frame = 0; frame < 3; frame++) {
            original.runCycles(LcdTiming.FRAME_CYCLES);
            restored.runCycles(LcdTiming.FRAME_CYCLES);
            var left = new short[1100]; var right = new short[1100];
            var count = original.memory.clockedAudio().drain(left);
            assertThat(restored.memory.clockedAudio().drain(right)).isEqualTo(count);
            assertSoftly(s -> {
                s.assertThat(right).isEqualTo(left);
                s.assertThat(restored.memory.raster().completedFrame()).isEqualTo(original.memory.raster().completedFrame());
                s.assertThat(MachineState.save(restored)).isEqualTo(MachineState.save(original));
            });
        }
    }

    @Test
    void restoresFlashCommandInProgressAndOwnsItsBytes() {
        var rom = DemoRom.create();
        var original = new Machine(rom);
        original.memory.setBackup(new CartridgeBackup(CartridgeBackup.FLASH_128K));
        original.memory.write8(0x0e005555, 0xaa);
        original.memory.write8(0x0e002aaa, 0x55);
        original.memory.write8(0x0e005555, 0xa0);
        var image = MachineState.save(original);
        var restored = MachineState.load(rom, null, image);
        Arrays.fill(image, (byte) 0);
        restored.memory.write8(0x0e000123, 0x42);
        assertSoftly(s -> {
            s.assertThat(restored.memory.read8(0x0e000123)).isEqualTo(0x42);
            s.assertThat(original.memory.read8(0x0e000123)).isEqualTo(0xff);
            s.assertThat(restored.memory.backup().dirty()).isTrue();
        });
    }

    @Test
    void rejectsBadFilesAndWrongAssetsWithoutChangingTheOriginal() {
        var rom = DemoRom.create(); var bios = new byte[16384];
        var original = new Machine(rom, bios);
        var state = MachineState.save(original);
        var corrupt = state.clone(); corrupt[60] ^= 1;
        var otherRom = rom.clone(); otherRom[0xa0] ^= 1;
        var otherBios = bios.clone(); otherBios[100] ^= 1;
        assertThatThrownBy(() -> MachineState.load(rom, bios, corrupt)).hasMessageContaining("checksum");
        assertThatThrownBy(() -> MachineState.load(otherRom, bios, state)).hasMessageContaining("another ROM");
        assertThatThrownBy(() -> MachineState.load(rom, otherBios, state)).hasMessageContaining("another BIOS");
        assertThatThrownBy(() -> MachineState.load(rom, null, state)).hasMessageContaining("another BIOS");
        assertThatThrownBy(() -> MachineState.load(rom, bios, Arrays.copyOf(state, 20))).isInstanceOf(IllegalArgumentException.class);
        assertThat(MachineState.save(original)).isEqualTo(state);
    }

    @Test
    void rejectsExternalCallbacksAndDoesNotAdvanceOrCancelThem() {
        var m = new Machine(DemoRom.create());
        var fired = new boolean[1];
        m.clock.schedule(() -> fired[0] = true, 10);
        assertThatThrownBy(() -> MachineState.save(m)).hasMessageContaining("external clock callback");
        assertThat(m.clock.now()).isZero();
        m.clock.advance(10);
        assertThat(fired[0]).isTrue();
    }

    @Test
    void checksumMatchesTheStandardCrc32Implementation() {
        var bytes = "123456789".getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32(); crc.update(bytes);
        assertThat(StateChecksum.bytes(bytes, bytes.length)).isEqualTo((int) crc.getValue()).isEqualTo(0xcbf43926);
    }
}

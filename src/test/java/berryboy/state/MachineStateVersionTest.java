package berryboy.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import berryboy.Machine;
import berryboy.MachineState;
import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("core")
class MachineStateVersionTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 7, 15, 31})
    void loadsFormatOneIncludingPendingDmaAndCpuWork(int steps) {
        var rom = new byte[512]; var machine = new Machine(rom);
        machine.memory.write32(0x03000100, 0xeafffffe);
        machine.cpu.setPc(0x03000100);
        machine.memory.write32(0x040000d4, 0x02000100); machine.memory.write32(0x040000d8, 0x03001000);
        machine.memory.write16(0x040000dc, 16); machine.memory.write16(0x040000de, 0x8400);
        for (var i = 0; i < steps; i++) machine.cpu.step();
        var old = legacySave(machine); var original = old.clone();
        var restored = MachineState.load(rom, null, old);
        var migrated = MachineState.save(restored);
        assertThat(migrated[4]).isEqualTo((byte) 3);
        assertThat(migrated.length).isEqualTo(old.length + 20);
        // The original format-1 field layout is preserved exactly; only its header/CRC change.
        assertThat(legacySave(restored)).isEqualTo(old);
        var copy = MachineState.load(rom, null, migrated);
        restored.runCycles(2000); copy.runCycles(2000);
        assertThat(MachineState.save(copy)).isEqualTo(MachineState.save(restored));
        assertThat(old).isEqualTo(original);
    }

    @Test
    void legacyStatesUseRamWhenTheOldFormatHasNoCapturedOpcode() {
        var rom = new byte[512]; var machine = new Machine(rom);
        machine.memory.write32(0x03000100, 0xe5821000); // STR r1,[r2].
        machine.memory.write32(0x03000104, 0xe3b00001); // MOVS r0,#1.
        machine.cpu.setRegister(1, 0xe3b00009); machine.cpu.setRegister(2, 0x03000104);
        machine.cpu.setPc(0x03000100); machine.cpu.step();
        var legacy = MachineState.load(rom, null, legacySave(machine));
        var current = MachineState.load(rom, null, MachineState.save(machine));
        legacy.cpu.step(); current.cpu.step();
        assertThat(legacy.cpu.register(0)).isEqualTo(9); // Historical format-1 execution policy.
        assertThat(current.cpu.register(0)).isEqualTo(1); // Format 2 retains the fetched opcode.
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, -1})
    void rejectsUnknownVersionsWithOtherwiseValidChecksums(int version) {
        var machine = new Machine(new byte[512]); var bytes = MachineState.save(machine);
        put(bytes, 4, version); checksum(bytes);
        assertThatThrownBy(() -> MachineState.load(new byte[512], null, bytes)).hasMessageContaining("version");
    }

    @ParameterizedTest
    @CsvSource({"0,1", "4,1", "8,-1", "8,3", "12,1", "16,1"})
    void rejectsMalformedPipelineFieldsWithoutMutatingTheRunningMachine(int offset, int value) {
        var machine = new Machine(new byte[512]); var original = MachineState.save(machine);
        var bytes = original.clone(); put(bytes, bytes.length - 24 + offset, value); checksum(bytes);
        assertThatThrownBy(() -> MachineState.load(new byte[512], null, bytes)).hasMessageContaining("opcode pipeline");
        assertThat(MachineState.save(machine)).isEqualTo(original);
    }

    @Test
    void rejectsTruncatedOrTrailingExtensionsEvenWithRecomputedChecksums() {
        var machine = new Machine(new byte[512]); var bytes = MachineState.save(machine);
        var truncated = Arrays.copyOf(bytes, bytes.length - 4); checksum(truncated);
        var trailing = Arrays.copyOf(bytes, bytes.length + 4); checksum(trailing);
        assertThatThrownBy(() -> MachineState.load(new byte[512], null, truncated)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MachineState.load(new byte[512], null, trailing)).hasMessageContaining("trailing");
    }

    @ParameterizedTest
    @CsvSource({"false,0x02000100", "true,0x02000100", "false,0x03000100", "true,0x03000100"})
    void formatTwoRetainsFetchedWramInstructionsAndAllOriginalFields(boolean thumb, long codeAddress) {
        var code = (int) codeAddress; var rom = new byte[512]; var machine = new Machine(rom);
        var width = thumb ? 2 : 4;
        machine.cpu.setCpsr(0xdf | (thumb ? 0x20 : 0)); machine.cpu.setPc(code);
        if (thumb) {
            machine.memory.write16(code, 0x8011); machine.memory.write16(code + width, 0x2001);
        } else {
            machine.memory.write32(code, 0xe5821000); machine.memory.write32(code + width, 0xe3b00001);
        }
        machine.cpu.setRegister(1, thumb ? 0x2009 : 0xe3b00009); machine.cpu.setRegister(2, code + width);
        machine.cpu.step();
        var old = formatTwoSave(machine); var before = old.clone();
        var restored = MachineState.load(rom, null, old);
        assertThat(formatTwoSave(restored)).isEqualTo(old);
        var canonical = MachineState.save(restored);
        assertThat(canonical[4]).isEqualTo((byte) 3);
        assertThat(canonical.length).isEqualTo(old.length);
        var current = MachineState.load(rom, null, canonical);
        restored.cpu.step(); current.cpu.step();
        assertThat(restored.cpu.register(0)).isEqualTo(1);
        assertThat(MachineState.save(restored)).isEqualTo(MachineState.save(current));
        assertThat(old).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"false,0x05000100", "true,0x05000100", "false,0x06000100", "true,0x06000100",
            "false,0x07000100", "true,0x07000100"})
    void rejectsVideoSlotsLabelledAsTheOlderWramOnlyFormat(boolean thumb, long codeAddress) {
        var code = (int) codeAddress; var rom = new byte[512]; var machine = new Machine(rom);
        machine.cpu.setCpsr(0xdf | (thumb ? 0x20 : 0)); machine.cpu.setPc(code);
        if (thumb) machine.memory.write16(code, 0x46c0); else machine.memory.write32(code, 0xe1a00000);
        machine.cpu.step();
        var before = MachineState.save(machine); var wrongVersion = before.clone();
        put(wrongVersion, 4, 2); checksum(wrongVersion);
        assertThatThrownBy(() -> MachineState.load(rom, null, wrongVersion)).hasMessageContaining("opcode pipeline");
        assertThat(MachineState.save(machine)).isEqualTo(before);
    }

    private static byte[] formatTwoSave(Machine machine) {
        var state = new StateBuffer();
        state.value(0x53594242); state.value(2);
        state.value(machine.cartridge.size()); state.value(machine.cartridge.checksum());
        state.flag(machine.memory.hasBios()); state.value(machine.memory.biosChecksum());
        state.value(machine.memory.backup().type());
        machine.memory.transferState(state); machine.cpu.transferState(state);
        machine.cpu.transferPipelineState(state, false);
        var payload = state.finish(); state.value(StateChecksum.bytes(payload, payload.length));
        return state.finish();
    }

    /** The published format-1 writer, kept explicit so migration cannot depend on the new extension. */
    private static byte[] legacySave(Machine machine) {
        var state = new StateBuffer();
        state.value(0x53594242); state.value(1);
        state.value(machine.cartridge.size()); state.value(machine.cartridge.checksum());
        state.flag(machine.memory.hasBios()); state.value(machine.memory.biosChecksum());
        state.value(machine.memory.backup().type());
        machine.memory.transferState(state); machine.cpu.transferState(state);
        var payload = state.finish(); state.value(StateChecksum.bytes(payload, payload.length));
        return state.finish();
    }
    private static void put(byte[] bytes, int offset, int value) {
        for (var i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (i * 8));
    }
    private static void checksum(byte[] bytes) { put(bytes, bytes.length - 4, StateChecksum.bytes(bytes, bytes.length - 4)); }
}

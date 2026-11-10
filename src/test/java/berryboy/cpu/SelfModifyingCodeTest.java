package berryboy.cpu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import berryboy.Machine;
import berryboy.MachineState;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("core")
class SelfModifyingCodeTest {
    @ParameterizedTest
    @CsvFileSource(resources = "/berryboy/cpu/self-modify.csv", numLinesToSkip = 1)
    void retainsFetchedInstructionsUntilBranchFlush(String mode, long code, int store, int distance,
            int stage, long cycles, long pc, long cpsr, int r0, long patched) {
        var machine = program(mode.equals("thumb"), (int) code, store, distance);
        var start = machine.clock.now();
        retire(machine, stage + 1);
        assertSoftly(s -> {
            s.assertThat(machine.clock.now() - start).isEqualTo(cycles);
            s.assertThat(machine.cpu.pc()).isEqualTo((int) pc);
            s.assertThat(machine.cpu.cpsr()).isEqualTo((int) cpsr);
            s.assertThat(machine.cpu.register(0)).isEqualTo(r0);
            s.assertThat(opcode(machine, (int) code + distance * (mode.equals("thumb") ? 2 : 4), mode.equals("thumb")))
                    .isEqualTo((int) patched);
            s.assertThat(machine.cpu.instructionInFlight()).isFalse();
            s.assertThat(machine.cpu.fetchInFlight()).isFalse();
            s.assertThat(machine.memory.busTiming().refilling()).isFalse();
        });
    }

    @ParameterizedTest
    @MethodSource("patchedStreams")
    void snapshotsRetainOpcodesWhichNowDifferFromRam(boolean thumb, int code, int store, int distance) {
        var machine = program(thumb, code, store, distance);
        retire(machine, 2); // Store retired; both prefetched instructions must retain their old values.
        assertThat(opcode(machine, code + distance * (thumb ? 2 : 4), thumb)).isEqualTo(thumb ? 0x2009 : 0xe3b00009);
        var bytes = MachineState.save(machine);
        var restored = MachineState.load(new byte[512], null, bytes);
        assertThat(MachineState.save(restored)).isEqualTo(bytes);
        for (var step = 0; step < 32 && machine.cpu.instructions() < 8; step++) {
            machine.cpu.step(); restored.cpu.step();
            assertThat(MachineState.save(restored)).isEqualTo(MachineState.save(machine));
            if (machine.cpu.instructions() == distance + 2) assertThat(machine.cpu.register(0)).isEqualTo(distance);
        }
        assertThat(machine.cpu.instructions()).isEqualTo(8);
        assertThat(machine.cpu.register(0)).isEqualTo(9);
    }

    static Stream<Arguments> patchedStreams() {
        var cases = new ArrayList<Arguments>();
        for (var thumb : new boolean[]{false, true})
            for (var code : new int[]{0x02000100, 0x03000100})
                for (var store : new int[]{1, 2, 4})
                    for (var distance : new int[]{1, 2}) cases.add(Arguments.of(thumb, code, store, distance));
        return cases.stream();
    }

    @ParameterizedTest
    @CsvSource({"false,0x02000100", "false,0x03000100", "true,0x02000100", "true,0x03000100"})
    void capturesEachRefillReadOnItsOwnSideOfDma(boolean thumb, long codeAddress) {
        var code = (int) codeAddress; var width = thumb ? 2 : 4;
        var machine = new Machine(new byte[512]);
        machine.memory.write32(0x03000080, 0xe12fff14);
        for (var i = 0; i < 4; i++) put(machine, code + i * width, thumb ? 0x2001 + i : 0xe3b00001 + i, thumb);
        machine.memory.write32(0x03001000, thumb ? 0x200a2009 : 0xe3b00009);
        machine.memory.write32(0x03001004, thumb ? 0x200c200b : 0xe3b0000a);
        machine.memory.write32(0x040000d4, 0x03001000); machine.memory.write32(0x040000d8, code);
        machine.memory.write16(0x040000dc, 2); machine.memory.write16(0x040000de, 0xa400);
        machine.cpu.setRegister(4, code | (thumb ? 1 : 0)); machine.cpu.setPc(0x03000080);
        var firstReadCycles = code >>> 24 == 2 ? (thumb ? 3 : 6) : 1;
        machine.clock.schedule(() -> machine.memory.dma().request(2), machine.clock.now() + 1 + firstReadCycles);
        machine.cpu.step(); // First refill captured; DMA has changed its RAM word before the second read.
        assertSoftly(s -> {
            s.assertThat(machine.cpu.instructionInFlight()).isTrue();
            s.assertThat(machine.memory.busTiming().refilling()).isTrue();
            s.assertThat(opcode(machine, code, thumb)).isEqualTo(thumb ? 0x2009 : 0xe3b00009);
        });
        var bytes = MachineState.save(machine);
        var restored = MachineState.load(new byte[512], null, bytes);
        assertThat(MachineState.save(restored)).isEqualTo(bytes);
        for (var step = 0; step < 32 && machine.cpu.instructions() < 3; step++) {
            machine.cpu.step(); restored.cpu.step();
            assertThat(MachineState.save(restored)).isEqualTo(MachineState.save(machine));
            if (machine.cpu.instructions() == 2) assertThat(machine.cpu.register(0)).isEqualTo(1);
        }
        assertThat(machine.cpu.instructions()).isEqualTo(3);
        assertThat(machine.cpu.register(0)).isEqualTo(10);
    }

    private static Machine program(boolean thumb, int code, int store, int distance) {
        var machine = new Machine(new byte[512]); var width = thumb ? 2 : 4;
        var target = code + distance * width;
        var operation = thumb ? (store == 1 ? 0x7011 : store == 2 ? 0x8011 : 0x6011)
                : (store == 1 ? 0xe5c21000 : store == 2 ? 0xe1c210b0 : 0xe5821000);
        machine.memory.write32(0x03000080, 0xe12fff14);
        put(machine, code, operation, thumb);
        for (var i = 1; i <= 4; i++) put(machine, code + i * width, thumb ? 0x2000 | i : 0xe3b00000 | i, thumb);
        put(machine, code + 5 * width, thumb ? 0x4718 : 0xe12fff13, thumb);
        var patch = thumb ? 0x2009 : 0xe3b00009;
        if (thumb && store == 4) patch = (target & 2) != 0
                ? 0x20090000 | (distance == 1 ? operation : 0x2000 | (distance - 1))
                : patch | (distance == 4 ? 0x4718 : 0x2000 | (distance + 1)) << 16;
        machine.cpu.setCpsr(Cpu.SYSTEM | 0xc0);
        machine.cpu.setRegister(1, patch); machine.cpu.setRegister(2, store == 4 ? target & ~3 : target);
        machine.cpu.setRegister(3, target | (thumb ? 1 : 0)); machine.cpu.setRegister(4, code | (thumb ? 1 : 0));
        machine.cpu.setPc(0x03000080); retire(machine, 1);
        assertThat(machine.cpu.pc()).isEqualTo(code);
        return machine;
    }
    private static int opcode(Machine machine, int address, boolean thumb) {
        return thumb ? machine.memory.read16(address) : machine.memory.read32(address);
    }
    private static void put(Machine machine, int address, int value, boolean thumb) {
        if (thumb) machine.memory.write16(address, value); else machine.memory.write32(address, value);
    }
    private static void retire(Machine machine, int count) {
        var limit = machine.clock.now() + 1000;
        while (machine.cpu.instructions() < count) {
            if (machine.clock.now() >= limit) throw new IllegalStateException("Retirement stalled");
            machine.cpu.step();
        }
    }
}

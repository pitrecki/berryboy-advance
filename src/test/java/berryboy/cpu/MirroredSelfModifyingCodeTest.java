package berryboy.cpu;

import static berryboy.cpu.SelfModifyingCodeFixture.opcode;
import static berryboy.cpu.SelfModifyingCodeFixture.program;
import static berryboy.cpu.SelfModifyingCodeFixture.retire;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import berryboy.MachineState;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("core")
class MirroredSelfModifyingCodeTest {
    @ParameterizedTest
    @CsvFileSource(resources = "/berryboy/cpu/self-modify-memory.csv", numLinesToSkip = 1)
    void matchesIndependentMirroredMemoryObservations(String mode, long code, long patchBase, int store, int distance,
            int stage, long cycles, long pc, long cpsr, int r0, long patched) {
        var thumb = mode.equals("thumb");
        var machine = program(thumb, (int) code, (int) patchBase, store, distance);
        var start = machine.clock.now();
        retire(machine, stage + 1);
        assertSoftly(s -> {
            s.assertThat(machine.clock.now() - start).isEqualTo(cycles);
            s.assertThat(machine.cpu.pc()).isEqualTo((int) pc);
            s.assertThat(machine.cpu.cpsr()).isEqualTo((int) cpsr);
            s.assertThat(machine.cpu.register(0)).isEqualTo(r0);
            s.assertThat(opcode(machine, (int) code + distance * (thumb ? 2 : 4), thumb)).isEqualTo((int) patched);
            s.assertThat(machine.cpu.instructionInFlight()).isFalse();
            s.assertThat(machine.cpu.fetchInFlight()).isFalse();
            s.assertThat(machine.memory.busTiming().refilling()).isFalse();
        });
    }

    @ParameterizedTest
    @MethodSource("videoStreams")
    void snapshotsRetainVideoInstructionsAfterStoresThroughTheirAliases(boolean thumb, int code, int patchBase, int store, int distance) {
        var machine = program(thumb, code, patchBase, store, distance);
        retire(machine, 2);
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

    static Stream<Arguments> videoStreams() {
        var cases = new ArrayList<Arguments>();
        for (var thumb : new boolean[]{false, true})
            for (var pair : new int[][]{{0x05000100, 0x05000500}, {0x05000500, 0x05000100},
                    {0x06000100, 0x06020100}, {0x06020100, 0x06000100},
                    {0x06010100, 0x06018100}, {0x06018100, 0x06010100},
                    {0x07000100, 0x07000500}, {0x07000500, 0x07000100}})
                for (var store : new int[]{2, 4})
                    for (var distance : new int[]{1, 2}) cases.add(Arguments.of(thumb, pair[0], pair[1], store, distance));
        return cases.stream();
    }
}

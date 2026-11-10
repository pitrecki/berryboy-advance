package berryboy.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static berryboy.bus.IrqReturnFixture.retire;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import berryboy.MachineState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.CsvSource;

/** NBA and mGBA agree on these observations after the IRQ return has retired. */
@Tag("core")
class IrqReturnFetchTest {
    @ParameterizedTest
    @CsvFileSource(resources = "/berryboy/bus/irq-return-fetch.csv", numLinesToSkip = 1)
    void timesThePhysicalFetchStreamAfterReturningToTheCartridge(String mode, long code, int prefetch, int idle,
            int add, long cycles, long pc, long status, long link, int r0) {
        var fixture = IrqReturnFixture.createAfterReturn(mode.equals("thumb"), (int) code, prefetch, idle);
        var machine = fixture.machine;
        var returned = machine.clock.now();
        retire(machine, 3 + add);
        assertSoftly(s -> {
            s.assertThat(machine.clock.now() - returned).isEqualTo(cycles);
            s.assertThat(machine.cpu.pc()).isEqualTo((int) pc);
            s.assertThat(machine.cpu.cpsr()).isEqualTo((int) status);
            s.assertThat(machine.cpu.register(14)).isEqualTo((int) link);
            s.assertThat(machine.cpu.register(0)).isEqualTo(r0);
            s.assertThat(machine.cpu.instructions()).isEqualTo(3 + add);
            s.assertThat(machine.cpu.fetchInFlight()).isFalse();
            s.assertThat(machine.cpu.instructionInFlight()).isFalse();
            s.assertThat(machine.memory.busTiming().refilling()).isFalse();
        });
    }

    @ParameterizedTest
    @CsvSource({"false,0", "false,1", "true,0", "true,1"})
    void stateLoadingRetainsTheReturnStreamAndFutureMachineState(boolean thumb, int prefetch) {
        var fixture = IrqReturnFixture.createAfterReturn(thumb, 0x08020000 - (thumb ? 2 : 4), prefetch, 0);
        var machine = fixture.machine;
        var bytes = MachineState.save(machine);
        var restored = MachineState.load(fixture.rom, fixture.bios, bytes);
        assertThat(MachineState.save(restored)).isEqualTo(bytes);
        for (var instructions = 4; instructions <= 6; instructions++) {
            retire(machine, instructions); retire(restored, instructions);
            assertThat(MachineState.save(restored)).isEqualTo(MachineState.save(machine));
        }
    }

}

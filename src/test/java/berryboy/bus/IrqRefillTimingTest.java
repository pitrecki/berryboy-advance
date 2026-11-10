package berryboy.bus;

import static berryboy.bus.IrqReturnFixture.retire;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import berryboy.MachineState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.CsvSource;

/** The fixture contains only complete NBA/mGBA agreements; disputed rows stay in the diagnosis. */
@Tag("core")
class IrqRefillTimingTest {
    @ParameterizedTest
    @CsvFileSource(resources = "/berryboy/bus/irq-refill-shared.csv", numLinesToSkip = 1)
    void chargesTheSharedReturnAndRefillObservations(String mode, long code, int prefetch, int idle,
            int stage, long cycles, long pc, long status, long link, int r0) {
        var fixture = IrqReturnFixture.createAtHandler(mode.equals("thumb"), (int) code, prefetch, idle);
        var machine = fixture.machine; var handler = machine.clock.now();
        retire(machine, stage + 1);
        assertSoftly(s -> {
            s.assertThat(machine.clock.now() - handler).isEqualTo(cycles);
            s.assertThat(machine.cpu.pc()).isEqualTo((int) pc);
            s.assertThat(machine.cpu.cpsr()).isEqualTo((int) status);
            s.assertThat(machine.cpu.register(14)).isEqualTo((int) link);
            s.assertThat(machine.cpu.register(0)).isEqualTo(r0);
            s.assertThat(machine.cpu.instructions()).isEqualTo(stage + 1);
            s.assertThat(machine.cpu.instructionInFlight()).isFalse();
            s.assertThat(machine.cpu.fetchInFlight()).isFalse();
            s.assertThat(machine.memory.busTiming().refilling()).isFalse();
        });
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void restoresARefillInterruptedByOffCartridgeOrCartridgeDma(boolean thumb, boolean cartridgeDma) {
        var target = 0x08020000 - (thumb ? 2 : 4);
        var fixture = IrqReturnFixture.createAtHandler(thumb, target, 1, 0);
        var machine = fixture.machine;
        machine.memory.write32(0x03000100, 0x04030201);
        machine.memory.write32(0x040000d4, cartridgeDma ? 0x08000100 : 0x03000100);
        machine.memory.write32(0x040000d8, 0x03001000);
        machine.memory.write16(0x040000dc, 1); machine.memory.write16(0x040000de, 0xa400);
        // The BIOS fetch takes one cycle. Readiness at +2 falls inside the first ROM refill read.
        machine.clock.schedule(() -> machine.memory.dma().request(2), machine.clock.now() + 2);
        machine.cpu.step();
        assertSoftly(s -> {
            s.assertThat(machine.cpu.instructions()).isEqualTo(2);
            s.assertThat(machine.cpu.instructionInFlight()).isTrue();
            s.assertThat(machine.memory.busTiming().refilling()).isTrue();
            s.assertThat(machine.cpu.pc()).isEqualTo(target);
            s.assertThat(machine.memory.read32(0x03001000)).isEqualTo(cartridgeDma ? (thumb ? 0x30013001 : 0xe2800001) : 0x04030201);
        });
        var bytes = MachineState.save(machine);
        var restored = MachineState.load(fixture.rom, fixture.bios, bytes);
        assertThat(MachineState.save(restored)).isEqualTo(bytes);
        for (var step = 0; step < 16 && machine.cpu.instructions() < 5; step++) {
            machine.cpu.step(); restored.cpu.step();
            assertThat(MachineState.save(restored)).isEqualTo(MachineState.save(machine));
        }
        assertThat(machine.cpu.instructions()).isEqualTo(5);
        assertThat(machine.cpu.register(0)).isEqualTo(2);
    }
}

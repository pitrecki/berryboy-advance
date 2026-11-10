package berryboy.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import berryboy.EmulatorFixtures;
import berryboy.Machine;
import berryboy.MachineState;
import berryboy.cpu.Cpu;
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
        var fixture = prepare(mode.equals("thumb"), (int) code, prefetch, idle);
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
        var fixture = prepare(thumb, 0x08020000 - (thumb ? 2 : 4), prefetch, 0);
        var machine = fixture.machine;
        var bytes = MachineState.save(machine);
        var restored = MachineState.load(fixture.rom, fixture.bios, bytes);
        assertThat(MachineState.save(restored)).isEqualTo(bytes);
        for (var instructions = 4; instructions <= 6; instructions++) {
            retire(machine, instructions); retire(restored, instructions);
            assertThat(MachineState.save(restored)).isEqualTo(MachineState.save(machine));
        }
    }

    private static Fixture prepare(boolean thumb, int pc, int prefetch, int idle) {
        var width = thumb ? 2 : 4;
        var rom = new byte[0x20040]; var opcode = thumb ? 0x3001 : 0xe2800001;
        for (var i = 0; i < rom.length; i++) rom[i] = (byte) (opcode >>> ((i % width) * 8));
        var bios = new byte[0x4000];
        EmulatorFixtures.writeWord(bios, 0x18, 0xe1a00000);
        EmulatorFixtures.writeWord(bios, 0x1c, 0xe25ef004); // SUBS pc,lr,#4.
        var machine = new Machine(rom); machine.memory.setBios(bios);
        machine.memory.write16(0x04000204, prefetch == 0 ? 0 : 0x4000);
        var originalStatus = Cpu.SYSTEM | 0x40 | Cpu.N | Cpu.C | (thumb ? Cpu.T : 0);
        machine.cpu.setCpsr(originalStatus); machine.cpu.setPc(pc - width);
        retire(machine, 1);
        machine.cpu.setRegister(0, 0); machine.cpu.setCpsr(originalStatus);
        machine.memory.busTiming().internal(idle, false);
        machine.memory.write16(0x04000200, 8); machine.memory.write16(0x04000208, 1);
        machine.memory.interrupts().request(8);
        retire(machine, 2);
        assertSoftly(s -> {
            s.assertThat(machine.cpu.pc()).isEqualTo(0x1c);
            s.assertThat(machine.cpu.register(14)).isEqualTo(pc + 4);
        });
        machine.memory.write16(0x04000202, 8);
        retire(machine, 3);
        assertSoftly(s -> {
            s.assertThat(machine.cpu.pc()).isEqualTo(pc);
            s.assertThat(machine.cpu.cpsr()).isEqualTo(originalStatus);
            s.assertThat(machine.cpu.register(14)).isZero();
        });
        return new Fixture(machine, rom, bios);
    }
    private static void retire(Machine machine, int count) {
        var deadline = machine.clock.now() + 1000;
        while (machine.cpu.instructions() < count) {
            if (machine.clock.now() >= deadline) throw new IllegalStateException("Retirement stalled");
            machine.cpu.step();
        }
    }
    private record Fixture(Machine machine, byte[] rom, byte[] bios) { }
}

package berryboy.bus;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import berryboy.EmulatorFixtures;
import berryboy.Machine;
import berryboy.cpu.Cpu;

/** Owned synthetic program/setup shared by the two IRQ timing observation windows. */
final class IrqReturnFixture {
    final Machine machine;
    final byte[] rom, bios;
    private IrqReturnFixture(Machine machine, byte[] rom, byte[] bios) {
        this.machine = machine; this.rom = rom; this.bios = bios;
    }
    static IrqReturnFixture createAtHandler(boolean thumb, int pc, int prefetch, int idle) {
        var width = thumb ? 2 : 4;
        var rom = new byte[0x20040]; var opcode = thumb ? 0x3001 : 0xe2800001;
        for (var i = 0; i < rom.length; i++) rom[i] = (byte) (opcode >>> ((i % width) * 8));
        var bios = new byte[0x4000];
        EmulatorFixtures.writeWord(bios, 0x18, 0xe1a00000);
        EmulatorFixtures.writeWord(bios, 0x1c, 0xe25ef004);
        var machine = new Machine(rom); machine.memory.setBios(bios);
        machine.memory.write16(0x04000204, prefetch == 0 ? 0 : 0x4000);
        var originalStatus = status(thumb);
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
        return new IrqReturnFixture(machine, rom, bios);
    }
    static IrqReturnFixture createAfterReturn(boolean thumb, int pc, int prefetch, int idle) {
        var result = createAtHandler(thumb, pc, prefetch, idle); var machine = result.machine;
        retire(machine, 3);
        assertSoftly(s -> {
            s.assertThat(machine.cpu.pc()).isEqualTo(pc);
            s.assertThat(machine.cpu.cpsr()).isEqualTo(status(thumb));
            s.assertThat(machine.cpu.register(14)).isZero();
        });
        return result;
    }
    private static int status(boolean thumb) { return Cpu.SYSTEM | 0x40 | Cpu.N | Cpu.C | (thumb ? Cpu.T : 0); }
    static void retire(Machine machine, int count) {
        var deadline = machine.clock.now() + 1000;
        while (machine.cpu.instructions() < count) {
            if (machine.clock.now() >= deadline) throw new IllegalStateException("Retirement stalled");
            machine.cpu.step();
        }
    }
}

package berryboy.cpu;

import static org.assertj.core.api.Assertions.assertThat;
import berryboy.Machine;

/** Owned instruction stream shared by direct-memory and mirror/latching regressions. */
final class SelfModifyingCodeFixture {
    private SelfModifyingCodeFixture() { }
    static Machine program(boolean thumb, int code, int store, int distance) {
        return program(thumb, code, code, store, distance);
    }
    static Machine program(boolean thumb, int code, int patchBase, int store, int distance) {
        var machine = new Machine(new byte[512]); var width = thumb ? 2 : 4;
        var target = code + distance * width; var destination = patchBase + distance * width;
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
        machine.cpu.setRegister(1, patch); machine.cpu.setRegister(2, store == 4 ? destination & ~3 : destination);
        machine.cpu.setRegister(3, target | (thumb ? 1 : 0)); machine.cpu.setRegister(4, code | (thumb ? 1 : 0));
        machine.cpu.setPc(0x03000080); retire(machine, 1);
        assertThat(machine.cpu.pc()).isEqualTo(code);
        return machine;
    }
    static int opcode(Machine machine, int address, boolean thumb) {
        return thumb ? machine.memory.read16(address) : machine.memory.read32(address);
    }
    static void put(Machine machine, int address, int value, boolean thumb) {
        if (thumb) machine.memory.write16(address, value); else machine.memory.write32(address, value);
    }
    static void retire(Machine machine, int count) {
        var limit = machine.clock.now() + 1000;
        while (machine.cpu.instructions() < count) {
            if (machine.clock.now() >= limit) throw new IllegalStateException("Retirement stalled");
            machine.cpu.step();
        }
    }
}

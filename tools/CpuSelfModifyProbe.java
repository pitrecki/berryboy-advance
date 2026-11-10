package berryboy.cpu;

import berryboy.Machine;

/** Asset-free stores into an instruction stream, observed before and after BX flushes it. */
public final class CpuSelfModifyProbe {
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Usage: berryboy.cpu.CpuSelfModifyProbe");
        System.out.println("mode,code,store_width,distance,stage,cycles,pc,cpsr,r0,patched");
        for (var thumb : new boolean[]{false, true})
            for (var region : new int[]{0x02000000, 0x03000000})
                for (var store : new int[]{1, 2, 4})
                    for (var distance = 1; distance <= 4; distance++) capture(thumb, region + 0x100, store, distance);
        if (System.out.checkError()) throw new IllegalStateException("Cannot write self-modifying observations");
    }

    private static void capture(boolean thumb, int code, int store, int distance) {
        var machine = new Machine(new byte[512]);
        var width = thumb ? 2 : 4;
        var target = code + distance * width;
        machine.memory.write32(0x03000080, 0xe12fff14); // BX r4 enters a fully refilled stream.
        var operation = thumb ? (store == 1 ? 0x7011 : store == 2 ? 0x8011 : 0x6011)
                : (store == 1 ? 0xe5c21000 : store == 2 ? 0xe1c210b0 : 0xe5821000);
        put(machine, code, operation, width);
        for (var i = 1; i <= 4; i++) put(machine, code + i * width, thumb ? 0x2000 | i : 0xe3b00000 | i, width);
        put(machine, code + 5 * width, thumb ? 0x4718 : 0xe12fff13, width); // BX r3 refetches the patch.
        var patch = thumb ? 0x2009 : 0xe3b00009;
        if (thumb && store == 4) patch = (target & 2) != 0
                ? 0x20090000 | (distance == 1 ? operation : 0x2000 | (distance - 1))
                : patch | (distance == 4 ? 0x4718 : 0x2000 | (distance + 1)) << 16;
        machine.cpu.setCpsr(Cpu.SYSTEM | 0xc0);
        machine.cpu.setRegister(1, patch); machine.cpu.setRegister(2, store == 4 ? target & ~3 : target);
        machine.cpu.setRegister(3, target | (thumb ? 1 : 0));
        machine.cpu.setRegister(4, code | (thumb ? 1 : 0)); machine.cpu.setPc(0x03000080);
        retire(machine, 1);
        if (machine.cpu.pc() != code || machine.cpu.isThumb() != thumb) throw new IllegalStateException("Incorrect priming");
        var start = machine.clock.now();
        for (var stage = 1; stage <= 7; stage++) {
            retire(machine, stage + 1);
            var patched = thumb ? machine.memory.read16(target) : machine.memory.read32(target);
            System.out.printf("%s,0x%08x,%d,%d,%d,%d,0x%08x,0x%08x,%d,0x%08x%n",
                    thumb ? "thumb" : "arm", code, store, distance, stage, machine.clock.now() - start,
                    machine.cpu.pc(), machine.cpu.cpsr(), machine.cpu.register(0), patched);
        }
    }

    private static void put(Machine machine, int address, int opcode, int width) {
        if (width == 2) machine.memory.write16(address, opcode); else machine.memory.write32(address, opcode);
    }
    private static void retire(Machine machine, int count) {
        var deadline = machine.clock.now() + 1000;
        while (machine.cpu.instructions() < count) {
            if (machine.clock.now() >= deadline) throw new IllegalStateException("Retirement stalled");
            machine.cpu.step();
        }
    }
}

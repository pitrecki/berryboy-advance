package berryboy.cpu;

import berryboy.Machine;

/** Asset-free stores into an instruction stream, observed before and after BX flushes it. */
public final class CpuSelfModifyProbe {
    public static void main(String[] args) {
        var extended = args.length == 1 && args[0].equals("--extended-memory");
        if (args.length != 0 && !extended) throw new IllegalArgumentException("Usage: berryboy.cpu.CpuSelfModifyProbe [--extended-memory]");
        System.out.println("mode,code," + (extended ? "patch_base," : "") + "store_width,distance,stage,cycles,pc,cpsr,r0,patched");
        var locations = new int[][]{
            {0x02000100, 0x02000100}, {0x03000100, 0x03000100},
            {0x02000100, 0x02040100}, {0x02040100, 0x02000100}, {0x02fc0100, 0x02000100},
            {0x03000100, 0x03008100}, {0x03008100, 0x03000100}, {0x03ff8100, 0x03000100},
            {0x06000100, 0x06020100}, {0x06020100, 0x06000100},
            {0x06010100, 0x06018100}, {0x06018100, 0x06010100},
            {0x05000100, 0x05000500}, {0x05000500, 0x05000100},
            {0x07000100, 0x07000500}, {0x07000500, 0x07000100}
        };
        for (var thumb : new boolean[]{false, true})
            for (var index = 0; index < (extended ? locations.length : 2); index++)
                for (var store : new int[]{1, 2, 4}) {
                    // Video byte stores repeat lanes or are ignored; this matrix isolates opcode latching.
                    if ((locations[index][0] >>> 24) >= 5 && store == 1) continue;
                    for (var distance = 1; distance <= 4; distance++)
                        capture(thumb, locations[index][0], locations[index][1], store, distance, extended);
                }
        if (System.out.checkError()) throw new IllegalStateException("Cannot write self-modifying observations");
    }

    private static void capture(boolean thumb, int code, int patchBase, int store, int distance, boolean extended) {
        var machine = new Machine(new byte[512]);
        var width = thumb ? 2 : 4;
        var target = code + distance * width;
        var destination = patchBase + distance * width;
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
        machine.cpu.setRegister(1, patch); machine.cpu.setRegister(2, store == 4 ? destination & ~3 : destination);
        machine.cpu.setRegister(3, target | (thumb ? 1 : 0));
        machine.cpu.setRegister(4, code | (thumb ? 1 : 0)); machine.cpu.setPc(0x03000080);
        retire(machine, 1);
        if (machine.cpu.pc() != code || machine.cpu.isThumb() != thumb) throw new IllegalStateException("Incorrect priming");
        var start = machine.clock.now();
        for (var stage = 1; stage <= 7; stage++) {
            retire(machine, stage + 1);
            var patched = thumb ? machine.memory.read16(target) : machine.memory.read32(target);
            System.out.printf("%s,0x%08x,", thumb ? "thumb" : "arm", code);
            if (extended) System.out.printf("0x%08x,", patchBase);
            System.out.printf("%d,%d,%d,%d,0x%08x,0x%08x,%d,0x%08x%n",
                    store, distance, stage, machine.clock.now() - start,
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

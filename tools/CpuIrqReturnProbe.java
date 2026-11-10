package berryboy.bus;

import berryboy.Machine;
import berryboy.cpu.Cpu;

/** Asset-free fetch observations timed from retired IRQ return; --full-trace retains entry/refill differences. */
public final class CpuIrqReturnProbe {
    public static void main(String[] args) {
        var full = args.length == 1 && args[0].equals("--full-trace");
        if (args.length != 0 && !full) throw new IllegalArgumentException("Usage: berryboy.bus.CpuIrqReturnProbe [--full-trace]");
        System.out.println("mode,code,prefetch,idle," + (full ? "stage,cycle" : "add,cycles_since_return") + ",pc,cpsr,lr,r0");
        for (var thumb : new boolean[]{false, true})
            for (var bank : new int[]{0x08000000, 0x0a000000, 0x0c000000})
                for (var offset : new int[]{0x20, 0x20000 - (thumb ? 2 : 4), 0x20000})
                    for (var setup = 0; setup < 3; setup++) capture(thumb, bank + offset, setup, full);
        if (System.out.checkError()) throw new IllegalStateException("Cannot write IRQ return observations");
    }
    private static void capture(boolean thumb, int pc, int setup, boolean full) {
        var width = thumb ? 2 : 4;
        var image = new byte[0x20040];
        var add = thumb ? 0x3001 : 0xe2800001;
        for (var i = 0; i < image.length; i++) image[i] = (byte) (add >>> ((i % width) * 8));
        var m = new Machine(image);
        var bios = new byte[0x4000];
        word(bios, 0x18, 0xe1a00000); // NOP, then architectural IRQ return.
        word(bios, 0x1c, 0xe25ef004); // SUBS pc, lr, #4 restores CPSR, ISA and banked LR.
        m.memory.setBios(bios);
        m.memory.write16(0x04000204, setup == 0 ? 0 : 0x4000);
        m.cpu.setCpsr(Cpu.SYSTEM | 0x40 | Cpu.N | Cpu.C | (thumb ? Cpu.T : 0));
        m.cpu.setPc(pc - width);
        m.cpu.step();
        if (m.cpu.pc() != pc || m.cpu.instructions() != 1) throw new IllegalStateException("Incorrect primer");
        m.cpu.setRegister(0, 0);
        m.cpu.setCpsr(Cpu.SYSTEM | 0x40 | Cpu.N | Cpu.C | (thumb ? Cpu.T : 0));
        var idle = setup == 2 ? 24 : 0;
        m.memory.busTiming().internal(idle, false);
        var start = m.clock.now();
        m.memory.write16(0x04000200, 8); m.memory.write16(0x04000208, 1);
        m.memory.interrupts().request(8);
        long returned = 0;
        for (var stage = 1; stage <= 5; stage++) {
            while (m.cpu.instructions() < stage + 1) {
                if (m.clock.now() - start > 1000) throw new IllegalStateException("IRQ return stalled");
                m.cpu.step();
            }
            if (m.cpu.instructionInFlight() || m.cpu.fetchInFlight() || m.memory.busTiming().refilling())
                throw new IllegalStateException("Observation is not retired state");
            if (stage == 2) returned = m.clock.now();
            if (full || stage > 2) System.out.printf("%s,0x%08x,%d,%d,%d,%d,0x%08x,0x%08x,0x%08x,%d%n", thumb ? "thumb" : "arm", pc,
                    setup == 0 ? 0 : 1, idle, full ? stage : stage - 2, m.clock.now() - (full ? start : returned), m.cpu.pc(), m.cpu.cpsr(), m.cpu.register(14), m.cpu.register(0));
            if (stage == 1) m.memory.write16(0x04000202, 8); // Acknowledge before returning, without timed CPU IO.
        }
    }
    private static void word(byte[] bytes, int offset, int value) {
        for (var i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
    }
}

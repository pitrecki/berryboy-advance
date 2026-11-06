package berryboy.bus;

import berryboy.Machine;
import berryboy.cpu.Cpu;

/** Asset-free adapter for nba-cpu-irq-prefetch-probe.cc. */
public final class CpuIrqPrefetchProbe {
    public static void main(String[] args) {
        var cartridge = args.length == 1 && args[0].equals("--cartridge-dma");
        if (args.length != 0 && !cartridge) throw new IllegalArgumentException("Usage: berryboy.bus.CpuIrqPrefetchProbe [--cartridge-dma]");
        System.out.println("mode,code,prefetch,idle,dma_ready,cycle,pc,cpsr,lr,r0,dma_complete" + (cartridge ? ",source" : ""));
        for (var thumb : new boolean[]{false, true})
            for (var bank : new int[]{0x08000000, 0x0a000000, 0x0c000000})
                for (var offset : new int[]{0x20, 0x20000 - (thumb ? 4 : 8), 0x20000 - (thumb ? 2 : 4), 0x20000})
                    for (var setup : new int[]{0, 1, 2})
                        for (var ready : new int[]{-1, 0, 1}) {
                            if (cartridge && ready < 0) continue;
                            capture(thumb, bank + offset, setup, ready, cartridge);
                        }
        if (System.out.checkError()) throw new IllegalStateException("Cannot write IRQ prefetch observations");
    }

    private static void capture(boolean thumb, int pc, int setup, int ready, boolean cartridge) {
        var width = thumb ? 2 : 4;
        var image = new byte[0x20040];
        for (var i = 0; i < image.length; i++) image[i] = (byte) ((thumb ? 0x3001 : 0xe2800001) >>> ((i % width) * 8));
        var m = new Machine(image);
        var bios = new byte[0x4000];
        for (var b = 0; b < 4; b++) bios[0x18 + b] = (byte) (0xe1a00000 >>> (b * 8));
        m.memory.setBios(bios);
        m.memory.write16(0x04000204, setup == 0 ? 0 : 0x4000);
        for (var i = 0; i < 16; i++) m.memory.write8(0x03000100 + i, i + 1);
        m.cpu.setCpsr(Cpu.SYSTEM | 0x40 | (thumb ? Cpu.T : 0));
        m.cpu.setPc(pc - width);
        m.cpu.setRegister(1, pc | 1);
        m.cpu.step(); // A preceding ADD establishes the logical sequential bus stream.
        if (m.cpu.pc() != pc || m.cpu.instructions() != 1) throw new IllegalStateException("Incorrect primer");
        m.cpu.setRegister(0, 0);
        var idle = setup == 2 ? 24 : 0;
        m.memory.busTiming().internal(idle, false);
        var start = m.clock.now();
        m.memory.write16(0x04000200, 8);
        m.memory.write16(0x04000208, 1);
        m.memory.interrupts().request(8);
        var source = cartridge ? 0x08001000 : 0x03000100;
        if (ready >= 0) {
            m.memory.write32(0x040000d4, source);
            m.memory.write32(0x040000d8, 0x03001000);
            m.memory.write16(0x040000dc, 1);
            m.memory.write16(0x040000de, 0xa400);
            m.clock.schedule(() -> m.memory.dma().request(2), start + ready);
        }
        m.clock.advance(0);
        while (m.cpu.instructions() == 1) {
            if (m.clock.now() - start > 1000) throw new IllegalStateException("CPU did not resume");
            m.cpu.step();
        }
        if (m.cpu.pc() != 0x1c || m.cpu.register(14) != pc + 4 || m.cpu.register(0) != 0)
            throw new IllegalStateException("Unexpected IRQ handler state");
        var expected = cartridge ? (thumb ? 0x30013001 : 0xe2800001) : 0x04030201;
        System.out.printf("%s,0x%08x,%d,%d,%d,%d,0x%08x,0x%08x,0x%08x,%d,%d", thumb ? "thumb" : "arm", pc,
                setup == 0 ? 0 : 1, idle, ready, m.clock.now() - start, m.cpu.pc(), m.cpu.cpsr(), m.cpu.register(14),
                m.cpu.register(0), m.memory.read32(0x03001000) == expected ? 1 : 0);
        if (cartridge) System.out.printf(",0x%08x", source);
        System.out.println();
    }
}

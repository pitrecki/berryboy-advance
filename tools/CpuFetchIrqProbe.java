package berryboy.bus;

import berryboy.Machine;
import berryboy.cpu.Cpu;

/** Asset-free adapter for nba-cpu-fetch-irq-probe.cc. */
public final class CpuFetchIrqProbe {
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Usage: berryboy.bus.CpuFetchIrqProbe");
        System.out.println("mode,source,change,edge,retired,cycle,r0,pc,cpsr,lr,dma_complete");
        for (var thumb : new boolean[]{false, true})
            for (var source : new int[]{0x03000000, 0x02000000, 0x08000000})
                for (var drop : new boolean[]{false, true}) {
                    var duration = source == 0x03000000 ? 4 : source == 0x02000000 ? 9 : 11;
                    for (var edge : new int[]{0, 1, duration - 1, duration, duration + 1}) capture(thumb, source, drop, edge);
                }
        if (System.out.checkError()) throw new IllegalStateException("Cannot write IRQ observations");
    }

    private static void capture(boolean thumb, int source, boolean drop, int edge) {
        var image = new byte[512];
        for (var i = 0; i < 16; i++) image[0x100 + i] = (byte) (i + 1);
        var m = new Machine(image);
        var bios = new byte[0x4000];
        for (var i = 0; i < 3; i++)
            for (var b = 0; b < 4; b++) bios[0x18 + i * 4 + b] = (byte) (0xe1a00000 >>> (b * 8));
        m.memory.setBios(bios);
        for (var i = 0; i < 16; i++) {
            m.memory.write8(0x03000100 + i, i + 1);
            m.memory.write8(0x02000100 + i, i + 1);
        }
        for (var i = 0; i < 32; i++) {
            if (thumb) m.memory.write16(0x03000000 + i * 2, 0x3001);
            else m.memory.write32(0x03000000 + i * 4, 0xe2800001);
        }
        m.cpu.setPc(0x03000000);
        m.cpu.setCpsr(Cpu.SYSTEM | 0x40 | (thumb ? Cpu.T : 0));
        m.cpu.setRegister(1, 0x03000001);
        m.memory.write16(0x04000200, 8);
        m.memory.write16(0x04000208, 1);
        if (drop) m.memory.interrupts().request(8);
        m.memory.write32(0x040000d4, source + 0x100);
        m.memory.write32(0x040000d8, 0x03001000);
        m.memory.write16(0x040000dc, 1);
        m.memory.write16(0x040000de, 0xa400);
        m.clock.advance(thumb ? 5 : 2);
        var start = m.clock.now();
        m.clock.schedule(() -> m.memory.dma().request(2), start);
        m.clock.schedule(() -> {
            if (drop) m.memory.write16(0x04000202, 8);
            else m.memory.interrupts().request(8);
        }, start + edge);
        m.clock.advance(0);
        for (var retired = 1; retired <= 2; retired++) {
            while (m.cpu.instructions() < retired) {
                if (m.clock.now() - start > 1000) throw new IllegalStateException("CPU did not resume");
                m.cpu.step();
            }
            System.out.printf("%s,0x%08x,%s,%d,%d,%d,%d,0x%08x,0x%08x,0x%08x,%d%n",
                    thumb ? "thumb" : "arm", source, drop ? "deassert" : "assert", edge, retired,
                    m.clock.now() - start, m.cpu.register(0), m.cpu.pc(), m.cpu.cpsr(), m.cpu.register(14),
                    m.memory.read32(0x03001000) == 0x04030201 ? 1 : 0);
        }
    }
}

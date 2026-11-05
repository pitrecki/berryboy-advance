package berryboy.bus;

import berryboy.Machine;
import berryboy.cpu.Cpu;

/** Asset-free adapter for nba-cpu-fetch-handoff-probe.cc. */
public final class CpuFetchHandoffProbe {
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Usage: berryboy.bus.CpuFetchHandoffProbe");
        System.out.println("mode,irq,next_ready,retired,cycle,r0,pc,cpsr,lr,second_dma_complete");
        for (var thumb : new boolean[]{false, true})
            for (var irq : new boolean[]{false, true})
                for (var ready : new int[]{4, 5}) capture(thumb, irq, ready);
        if (System.out.checkError()) throw new IllegalStateException("Cannot write fetch observations");
    }

    private static void capture(boolean thumb, boolean irq, int ready) {
        var m = new Machine(new byte[512]);
        var bios = new byte[0x4000];
        for (var i = 0; i < 3; i++)
            for (var b = 0; b < 4; b++) bios[0x18 + i * 4 + b] = (byte) (0xe1a00000 >>> (b * 8));
        m.memory.setBios(bios);
        for (var i = 0; i < 16; i++) m.memory.write8(0x03000100 + i, i + 1);
        for (var i = 0; i < 32; i++) {
            if (thumb) m.memory.write16(0x03000000 + i * 2, 0x3001);
            else m.memory.write32(0x03000000 + i * 4, 0xe2800001);
        }
        m.cpu.setPc(0x03000000);
        m.cpu.setCpsr(Cpu.SYSTEM | (irq ? 0x40 : 0xc0) | (thumb ? Cpu.T : 0));
        m.cpu.setRegister(1, 0x03000001);
        if (irq) {
            m.memory.write16(0x04000200, 8);
            m.memory.write16(0x04000208, 1);
        }
        for (var channel : new int[]{3, 1}) {
            var base = 0x040000b0 + channel * 12;
            m.memory.write32(base, 0x03000100);
            m.memory.write32(base + 4, 0x03001000 + (channel == 1 ? 0x100 : 0));
            m.memory.write16(base + 8, 1);
            m.memory.write16(base + 10, channel == 3 ? 0xa400 : 0x9400);
        }
        m.clock.advance(thumb ? 5 : 2);
        var start = m.clock.now();
        m.clock.schedule(() -> m.memory.dma().request(2), start);
        m.clock.schedule(() -> m.memory.dma().request(1), start + ready);
        if (irq) m.clock.schedule(() -> m.memory.interrupts().request(8), start + ready);
        m.clock.advance(0);
        for (var retired = 1; retired <= 2; retired++) {
            while (m.cpu.instructions() < retired) {
                if (m.clock.now() - start > 1000) throw new IllegalStateException("CPU did not resume");
                m.cpu.step();
            }
            System.out.printf("%s,%d,%d,%d,%d,%d,0x%08x,0x%08x,0x%08x,%d%n", thumb ? "thumb" : "arm", irq ? 1 : 0,
                    ready, retired, m.clock.now() - start, m.cpu.register(0), m.cpu.pc(), m.cpu.cpsr(),
                    m.cpu.register(14), m.memory.read32(0x03001100) == 0x04030201 ? 1 : 0);
        }
    }
}

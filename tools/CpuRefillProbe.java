package berryboy.bus;

import berryboy.Machine;
import berryboy.cpu.Cpu;

/** Asset-free same-mode BX adapter matching nba-cpu-refill-probe.cc. */
public final class CpuRefillProbe {
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Usage: berryboy.bus.CpuRefillProbe");
        System.out.println("mode,target,source,ready,cycle,pc,dma_complete");
        for (var thumb : new boolean[]{false, true})
            for (var target : new int[]{0x03000400, 0x08000100})
                for (var source : new int[]{0x03000000, 0x02000000, 0x08000000})
                    for (var ready : new int[]{0, 1, 2}) capture(thumb, target, source, ready);
        if (System.out.checkError()) throw new IllegalStateException("Cannot write refill observations");
    }

    private static void capture(boolean thumb, int target, int source, int ready) {
        var image = new byte[512];
        for (var i = 0; i < 16; i++) image[0x100 + i] = (byte) (i + 1);
        var m = new Machine(image);
        for (var i = 0; i < 16; i++) {
            m.memory.write8(0x03000100 + i, i + 1);
            m.memory.write8(0x02000100 + i, i + 1);
        }
        if (thumb) m.memory.write16(0x03000000, 0x4710);
        else m.memory.write32(0x03000000, 0xe12fff12);
        m.cpu.setCpsr(Cpu.SYSTEM | 0xc0 | (thumb ? Cpu.T : 0));
        m.cpu.setPc(0x03000000);
        m.cpu.setRegister(2, target | (thumb ? 1 : 0));
        m.clock.advance((thumb ? 5 : 2) + (ready == 0 ? 0 : 2 - ready));
        var start = m.clock.now();
        if (ready != 0) {
            m.memory.write32(0x040000d4, source + 0x100);
            m.memory.write32(0x040000d8, 0x03000200);
            m.memory.write16(0x040000dc, 2);
            m.memory.write16(0x040000de, 0xa400);
            m.clock.schedule(() -> m.memory.dma().request(2), start + ready);
        }
        while (m.cpu.instructions() == 0) {
            if (m.clock.now() - start > 1000) throw new IllegalStateException("CPU did not resume");
            m.cpu.step();
        }
        if (m.cpu.pc() != target || m.cpu.isThumb() != thumb) throw new IllegalStateException("Unexpected branch result");
        System.out.printf("%s,0x%08x,0x%08x,%d,%d,0x%08x,%d%n", thumb ? "thumb" : "arm",
                target, source, ready, m.clock.now() - start, m.cpu.pc(), m.memory.read32(0x03000204) == 0x08070605 ? 1 : 0);
    }
}

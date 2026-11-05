package berryboy.bus;

import berryboy.Machine;
import berryboy.cpu.Cpu;

/** Asset-free data-bus adapter matching nba-dma-data-probe.cc. */
public final class DmaDataProbe {
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Usage: berryboy.bus.DmaDataProbe");
        System.out.println("mode,program,data,source,ready,cycle,r0,r3,r2,pc");
        for (var thumb : new boolean[]{false, true})
            for (var multiple : new boolean[]{false, true})
              for (var data : new int[]{0x03000200, 0x08000100})
                for (var source : new int[]{0x03000000, 0x02000000, 0x08000000})
                    for (var ready : new int[]{1, 2}) capture(thumb, multiple, data, source, ready);
        if (System.out.checkError()) throw new IllegalStateException("Cannot write data-bus observations");
    }

    private static void capture(boolean thumb, boolean multiple, int data, int source, int ready) {
        var image = new byte[512];
        for (var i = 0; i < 16; i++) image[0x100 + i] = (byte) (i + 1);
        var m = new Machine(image);
        for (var i = 0; i < 16; i++) {
            m.memory.write8(0x03000100 + i, i + 1);
            m.memory.write8(0x02000100 + i, i + 1);
        }
        if (thumb) m.memory.write16(0x03000000, multiple ? 0xca09 : 0x6810);
        else m.memory.write32(0x03000000, multiple ? 0xe8b20009 : 0xe5920000);
        m.cpu.setCpsr(Cpu.SYSTEM | 0xc0 | (thumb ? Cpu.T : 0));
        m.cpu.setPc(0x03000000);
        m.cpu.setRegister(2, data);
        m.memory.write32(0x03000200, 0x12345678);
        m.memory.write32(0x03000204, 0x12345679);
        m.clock.advance((thumb ? 5 : 2) + 2 - ready);
        var start = m.clock.now();
        m.memory.write32(0x040000d4, source + 0x100);
        m.memory.write32(0x040000d8, 0x03000200);
        m.memory.write16(0x040000dc, 2);
        m.memory.write16(0x040000de, 0xa400); // HBlank, raw readiness injected below.
        m.clock.schedule(() -> m.memory.dma().request(2), start + ready);
        while (m.cpu.instructions() == 0) {
            if (m.clock.now() - start > 1000) throw new IllegalStateException("CPU did not resume");
            m.cpu.step();
        }
        System.out.printf("%s,%s,0x%08x,0x%08x,%d,%d,%d,%d,0x%08x,0x%08x%n",
                thumb ? "thumb" : "arm", multiple ? "ldm" : "ldr", data, source, ready,
                m.clock.now() - start, m.cpu.register(0), m.cpu.register(3), m.cpu.register(2), m.cpu.pc());
    }
}

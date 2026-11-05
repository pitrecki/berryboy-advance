package berryboy.bus;

import berryboy.Machine;
import berryboy.cpu.Cpu;

/** Original CPU retirement adapter matching nba-dma-activation-probe.cc. */
public final class DmaActivationProbe {
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Usage: berryboy.bus.DmaActivationProbe");
        System.out.println("mode,program,source,channels,retired,cycle,r0,pc");
        for (var thumb : new boolean[]{false, true})
            for (var load : new boolean[]{false, true})
            for (var source : new int[]{0x03000000, 0x02000000, 0x08000000})
                for (var channels = 1; channels <= 2; channels++) capture(thumb, load, source, channels);
        if (System.out.checkError()) throw new IllegalStateException("Cannot write activation observations");
    }

    private static void capture(boolean thumb, boolean load, int source, int channels) {
        var image = new byte[512];
        for (var i = 0; i < 16; i++) image[0x100 + i] = (byte) (i + 1);
        var m = new Machine(image);
        for (var i = 0; i < 16; i++) {
            m.memory.write8(0x03000100 + i, i + 1);
            m.memory.write8(0x02000100 + i, i + 1);
        }
        for (var i = 0; i < 32; i++) {
            if (thumb) m.memory.write16(0x03000000 + i * 2, load ? 0x6810 : 0x3001);
            else m.memory.write32(0x03000000 + i * 4, load ? 0xe5920000 : 0xe2800001);
        }
        m.cpu.setCpsr(Cpu.SYSTEM | 0xc0 | (thumb ? Cpu.T : 0));
        m.cpu.setPc(0x03000000);
        m.cpu.setRegister(1, 0x03000001);
        m.cpu.setRegister(2, 0x03000200);
        m.memory.write32(0x03000200, 0x12345678);
        m.memory.write16(0x04000084, 0x80);
        m.memory.write16(0x04000082, 0x210c);
        m.clock.advance(thumb ? 5 : 2); // NBA priming: two ARM fetches, plus BX/Thumb refill.
        var start = m.clock.now();
        for (var c = 1; c <= channels; c++) {
            var base = 0x040000b0 + c * 12;
            m.memory.write32(base, source + 0x100);
            m.memory.write32(base + 4, 0x040000a0 + (c - 1) * 4);
            m.memory.write16(base + 8, 4);
            m.memory.write16(base + 10, 0xb640);
            m.memory.dma().requestFifo(0x040000a0 + (c - 1) * 4);
        }
        for (var retired = 1; retired <= 4; retired++) {
            while (m.cpu.instructions() < retired) {
                if (m.clock.now() - start > 1000) throw new IllegalStateException("CPU did not resume");
                m.cpu.step();
            }
            if (m.cpu.register(0) != (load ? 0x12345678 : retired)
                    || m.cpu.pc() != 0x03000000 + retired * (thumb ? 2 : 4)) {
                throw new IllegalStateException("Unexpected instruction sequence");
            }
            System.out.printf("%s,%s,0x%08x,%d,%d,%d,%d,0x%08x%n", thumb ? "thumb" : "arm", load ? "load" : "add", source, channels,
                    retired, m.clock.now() - start, m.cpu.register(0), m.cpu.pc());
        }
    }
}

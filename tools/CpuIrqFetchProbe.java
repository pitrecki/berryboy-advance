package berryboy.bus;

import berryboy.Machine;
import berryboy.cpu.Cpu;

/** Asset-free adapter for nba-cpu-irq-fetch-probe.cc. */
public final class CpuIrqFetchProbe {
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Usage: berryboy.bus.CpuIrqFetchProbe");
        System.out.println("mode,code,dma_ready,cycle,pc,cpsr,lr,r0,dma_complete");
        for (var thumb : new boolean[]{false, true})
            for (var code : new int[]{0x03000000, 0x02000000, 0x08000000})
                for (var ready : new int[]{-1, 0, 1}) capture(thumb, code, ready);
        if (System.out.checkError()) throw new IllegalStateException("Cannot write IRQ fetch observations");
    }

    private static void capture(boolean thumb, int code, int ready) {
        var image = new byte[512];
        var width = thumb ? 2 : 4;
        for (var i = 0; i < 32; i++)
            for (var b = 0; b < width; b++) image[i * width + b] = (byte) ((thumb ? 0x3001 : 0xe2800001) >>> (b * 8));
        var m = new Machine(image);
        var bios = new byte[0x4000];
        for (var b = 0; b < 4; b++) bios[0x18 + b] = (byte) (0xe1a00000 >>> (b * 8));
        m.memory.setBios(bios);
        if (code != 0x08000000)
            for (var i = 0; i < 32 * width; i++) m.memory.write8(code + i, image[i]);
        for (var i = 0; i < 16; i++) m.memory.write8(0x03000100 + i, i + 1);
        var observedPc = code + 0x20;
        m.cpu.setCpsr(Cpu.SYSTEM | 0x40 | (thumb ? Cpu.T : 0));
        m.cpu.setPc(observedPc - width);
        m.cpu.setRegister(1, observedPc | 1);
        // Execute one preceding ADD to establish a real sequential CPU bus
        // stream. Its register effect and retirement are outside observation.
        m.cpu.step();
        if (m.cpu.pc() != observedPc || m.cpu.instructions() != 1) throw new IllegalStateException("Incorrect primer");
        m.cpu.setRegister(0, 0);
        var warmup = thumb ? (code == 0x03000000 ? 5 : code == 0x02000000 ? 9 : 11)
                : (code == 0x03000000 ? 2 : code == 0x02000000 ? 12 : 14);
        m.clock.advanceTo(warmup); // Match asserted reference priming without changing bus sequencing.
        var start = m.clock.now();
        m.memory.write16(0x04000200, 8);
        m.memory.write16(0x04000208, 1);
        m.memory.interrupts().request(8);
        if (ready >= 0) {
            m.memory.write32(0x040000d4, 0x03000100);
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
        if (m.cpu.pc() != 0x1c || m.cpu.register(14) != observedPc + 4 || m.cpu.register(0) != 0)
            throw new IllegalStateException("Unexpected IRQ handler state");
        System.out.printf("%s,0x%08x,%d,%d,0x%08x,0x%08x,0x%08x,%d,%d%n", thumb ? "thumb" : "arm",
                observedPc, ready, m.clock.now() - start, m.cpu.pc(), m.cpu.cpsr(), m.cpu.register(14),
                m.cpu.register(0), m.memory.read32(0x03001000) == 0x04030201 ? 1 : 0);
    }
}

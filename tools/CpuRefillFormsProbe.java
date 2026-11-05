package berryboy.bus;

import berryboy.Machine;
import berryboy.cpu.Cpu;

/** Asset-free adapter for nba-cpu-refill-forms-probe.cc. */
public final class CpuRefillFormsProbe {
    private static final String[] NAMES = {"arm-b", "arm-bl", "arm-bx-thumb", "thumb-bx-arm",
            "arm-ldr-pc", "arm-ldm-pc", "thumb-b", "thumb-pop-pc", "arm-swi", "thumb-swi", "arm-irq", "thumb-irq"};
    private static final int[] OPCODES = {0xea0000fe, 0xeb0000fe, 0xe12fff12, 0x4710, 0xe592f000, 0xe8b28000,
            0xe1fe, 0xbd00, 0xef000000, 0xdf00, 0xe1a00000, 0x46c0};
    private static final int[] KINDS = {0, 0, 1, 1, 2, 2, 0, 2, 3, 3, 4, 4};

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Usage: berryboy.bus.CpuRefillFormsProbe");
        System.out.println("program,source,ready,cycle,pc,cpsr,lr,r2,sp,dma_complete");
        for (var form = 0; form < NAMES.length; form++)
            for (var source : new int[]{0x03000000, 0x02000000, 0x08000000})
                for (var ready : new int[]{0, 1, 2, 3}) capture(form, source, ready);
        if (System.out.checkError()) throw new IllegalStateException("Cannot write refill observations");
    }

    private static void capture(int form, int source, int ready) {
        var image = new byte[512];
        for (var i = 0; i < 16; i++) image[0x100 + i] = (byte) (i + 1);
        var m = new Machine(image);
        var bios = new byte[0x4000];
        for (var b = 0; b < 4; b++) bios[0x18 + b] = (byte) (0xe1a00000 >>> (b * 8));
        m.memory.setBios(bios);
        for (var i = 0; i < 16; i++) {
            m.memory.write8(0x03000100 + i, i + 1);
            m.memory.write8(0x02000100 + i, i + 1);
        }
        var thumb = NAMES[form].startsWith("thumb");
        var irq = KINDS[form] == 4;
        var targetThumb = KINDS[form] == 1 ? !thumb : thumb;
        if (thumb) m.memory.write16(0x03000000, OPCODES[form]);
        else m.memory.write32(0x03000000, OPCODES[form]);
        m.cpu.setPc(0x03000000);
        m.cpu.setCpsr(Cpu.SYSTEM | (irq ? 0x40 : 0xc0) | (thumb ? Cpu.T : 0));
        m.cpu.setRegister(2, KINDS[form] == 2 ? 0x03000200 : 0x03000400 | (targetThumb ? 1 : 0));
        m.cpu.setRegister(13, 0x03000200);
        m.memory.write32(0x03000200, 0x03000400 | (targetThumb ? 1 : 0));
        m.clock.advance(thumb ? 5 : 2);
        var start = m.clock.now();
        if (ready != 0) {
            m.memory.write32(0x040000d4, source + 0x100);
            m.memory.write32(0x040000d8, 0x03001000);
            m.memory.write16(0x040000dc, 2);
            m.memory.write16(0x040000de, 0xa400);
            m.clock.schedule(() -> m.memory.dma().request(2), start + ready);
        }
        if (irq) {
            m.memory.write16(0x04000200, 8);
            m.memory.write16(0x04000208, 1);
            m.memory.interrupts().request(8);
        }
        while (m.cpu.instructions() == 0) {
            if (m.clock.now() - start > 1000) throw new IllegalStateException("CPU did not resume");
            m.cpu.step();
        }
        if (m.cpu.pc() != (irq ? 0x1c : KINDS[form] == 3 ? 8 : 0x03000400))
            throw new IllegalStateException("Unexpected refill result");
        System.out.printf("%s,0x%08x,%d,%d,0x%08x,0x%08x,0x%08x,0x%08x,0x%08x,%d%n",
                NAMES[form], source, ready, m.clock.now() - start, m.cpu.pc(), m.cpu.cpsr(),
                m.cpu.register(14), m.cpu.register(2), m.cpu.register(13), m.memory.read32(0x03001004) == 0x08070605 ? 1 : 0);
    }
}

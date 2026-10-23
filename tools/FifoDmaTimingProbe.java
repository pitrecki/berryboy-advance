package berryboy.bus;

import berryboy.Machine;
import berryboy.audio.AudioUnit;
import berryboy.cpu.Cpu;

/** Original FIFO requests matching mgba-fifo-dma-probe.c, without external assets. */
public final class FifoDmaTimingProbe {
    private static final int DMA_BASE = 0x040000b0;
    private static final int FIFO_A = 0x040000a0;
    private static final int CODE = 0x03000000;
    private static final int SOURCE_OFFSET = 0x100;

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Usage: berryboy.bus.FifoDmaTimingProbe");
        System.out.println("source,channels,count,late,after,r0");
        for (var region : new int[]{0x03000000, 0x02000000, 0x08000000}) {
            for (var channels = 1; channels <= 2; channels++) {
                for (var count : new int[]{0, 1, 2, 3, 4, 5, 8, 0x3fff}) {
                    for (var late = 0; late < 4; late++) capture(region, channels, count, late);
                }
            }
        }
        if (System.out.checkError()) throw new IllegalStateException("Cannot write probe output");
    }

    private static void capture(int region, int channels, int count, int late) {
        var image = new byte[512];
        for (var i = 0; i < 16; i++) image[SOURCE_OFFSET + i] = (byte) (i + 1);
        var machine = new Machine(image);
        for (var offset = 0; offset < 16; offset += 4) {
            machine.memory.write32(0x03000100 + offset, 0x11110000 + offset);
            machine.memory.write32(0x02000100 + offset, 0x22220000 + offset);
        }
        machine.memory.write16(CODE, 0x3001); // ADD r0,#1.
        machine.cpu.setCpsr(Cpu.SYSTEM | Cpu.T);
        machine.cpu.setPc(CODE);
        machine.memory.write16(AudioUnit.MASTER_CONTROL, AudioUnit.MASTER_ENABLE);
        machine.memory.write16(AudioUnit.MIX_CONTROL, 0x210c); // FIFO A right, B left, full volume.
        for (var channel = 1; channel <= channels; channel++) {
            var base = DMA_BASE + channel * 12;
            machine.memory.write32(base, region + SOURCE_OFFSET);
            machine.memory.write32(base + 4, FIFO_A + (channel - 1) * 4);
            machine.memory.write16(base + 8, count);
            machine.memory.write16(base + 10, 0xb640);
        }
        // Device request at zero; CPU ownership becomes available 0..3 cycles later.
        machine.clock.schedule(() -> {
            for (var channel = 1; channel <= channels; channel++) {
                machine.memory.dma().requestFifo(FIFO_A + (channel - 1) * 4);
            }
        }, 0);
        machine.clock.advance(late);
        for (var word = 0; word < channels * 4; word++) machine.cpu.step();
        machine.cpu.step();
        if (machine.cpu.instructions() != 1 || machine.cpu.register(0) != 1 || machine.cpu.pc() != CODE + 2) {
            throw new IllegalStateException("DMA did not yield to exactly one ADD instruction");
        }
        System.out.printf("0x%08x,%d,%d,%d,%d,%d%n", region, channels, count, late,
                machine.clock.now(), machine.cpu.register(0));
    }
}

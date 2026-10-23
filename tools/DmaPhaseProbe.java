package berryboy.bus;

import berryboy.Machine;
import berryboy.timing.CycleEvent;

/** Original asset-free adapter matching nba-dma-phase-probe.cc. */
public final class DmaPhaseProbe {
    private static final int SAMPLES = 200;

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Usage: berryboy.bus.DmaPhaseProbe");
        System.out.println("source,destination,width,channels,count,service_delay,cycle,first1,last1,first2,last2,enabled,irq");
        for (var source : new int[]{0x03000000, 0x02000000, 0x08000000})
            for (var destination : new int[]{0x03001000, 0x02001000})
                for (var width : new int[]{2, 4})
                    for (var channels : new int[]{1, 2})
                        for (var count : new int[]{1, 2, 4})
                            for (var delay : new int[]{2, 3}) capture(source, destination, width, channels, count, delay);
        if (System.out.checkError()) throw new IllegalStateException("Cannot write probe output");
    }

    private static void capture(int source, int destination, int width, int channels, int count, int delay) {
        var image = new byte[512];
        for (var i = 0; i < 16; i++) image[0x100 + i] = (byte) (i + 1);
        var machine = new Machine(image);
        for (var i = 0; i < 16; i++) {
            machine.memory.write8(0x03000100 + i, i + 1);
            machine.memory.write8(0x02000100 + i, i + 1);
        }
        for (var c = 1; c <= channels; c++) {
            var base = 0x040000b0 + c * 12;
            machine.memory.write32(base, source + 0x100);
            machine.memory.write32(base + 4, destination + (c - 1) * 0x100);
            machine.memory.write16(base + 8, count);
            machine.memory.write16(base + 10, width == 4 ? 0xe400 : 0xe000);
        }
        machine.memory.dma().request(2);
        machine.clock.advance(delay); // Isolate ownership after NBA's request activation.
        var start = machine.clock.now();
        var observations = new long[SAMPLES][6];
        // Refresh after EVERY queued callback, including IRQ publication. This
        // matches NBA's late-priority observer at equal deadlines; bus writes
        // finish only after advance() returns, after these observations.
        machine.clock.setObserver((event, before) -> {
            var cycle = (int) (machine.clock.now() - start);
            if (!before && cycle > 0 && cycle <= SAMPLES) {
                var row = observations[cycle - 1];
                row[0] = read(machine, destination, width);
                row[1] = read(machine, destination + (count - 1) * width, width);
                row[2] = channels == 2 ? read(machine, destination + 0x100, width) : 0;
                row[3] = channels == 2 ? read(machine, destination + 0x100 + (count - 1) * width, width) : 0;
                row[4] = 0;
                for (var c = 1; c <= channels; c++) {
                    if ((machine.memory.read16(0x040000ba + c * 12) & 0x8000) != 0) row[4] |= 1 << c;
                }
                row[5] = machine.memory.read16(0x04000202);
            }
        });
        CycleEvent sample = new CycleEvent() {
            public void fire() {
                if (machine.clock.now() - start < SAMPLES) machine.clock.schedule(this, machine.clock.now() + 1);
            }
        };
        machine.clock.schedule(sample, start + 1);
        var transfers = 0;
        while (machine.memory.dma().runPending()) {
            if (++transfers > channels * count + 1) throw new IllegalStateException("DMA did not finish");
        }
        machine.clock.advanceTo(start + SAMPLES);
        for (var cycle = 1; cycle <= SAMPLES; cycle++) {
            var row = observations[cycle - 1];
            System.out.printf("0x%08x,0x%08x,%d,%d,%d,%d,%d,0x%08x,0x%08x,0x%08x,0x%08x,%d,%d%n",
                    source, destination, width, channels, count, delay, cycle,
                    row[0], row[1], row[2], row[3], row[4], row[5]);
        }
    }

    private static long read(Machine machine, int address, int width) {
        return Integer.toUnsignedLong(width == 4 ? machine.memory.read32(address) : machine.memory.read16(address));
    }
}

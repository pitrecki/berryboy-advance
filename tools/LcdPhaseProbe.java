package berryboy.desktop;

import berryboy.Machine;
import berryboy.MachineState;
import berryboy.timing.CycleEvent;
import java.util.Arrays;

/** Asset-free current-model counterpart to the independent natural LCD probes. */
public final class LcdPhaseProbe {
    private static final int DURATION = 1248;
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Usage: berryboy.desktop.LcdPhaseProbe");
        System.out.println("line,forced_blank,cycle,vcount,dispstat,irq,ime,irq_pending,dma_enabled,destination");
        for (var line : new int[]{0, 159, 160, 227}) for (var blank : new int[]{0, 1}) {
            var control = run(line, blank, false);
            if (!Arrays.equals(control, run(line, blank, true))) throw new IllegalStateException("Observer changed complete machine state");
        }
        if (System.out.checkError()) throw new IllegalStateException("Cannot write probe output");
    }
    private static byte[] run(int line, int blank, boolean trace) {
        var m = new Machine(new byte[512]);
        while (m.memory.read16(0x04000006) != line || (m.memory.read16(0x04000004) & 2) != 0) {
            m.clock.advance(1);
            if (m.clock.now() > 2 * 280896) throw new IllegalStateException("Could not find scanline origin");
        }
        var start = m.clock.now();
        m.memory.write16(0x04000000, blank == 0 ? 0 : 0x80);
        m.memory.write8(0x04000005, (line + 1) % 228); m.memory.write8(0x04000004, 0x38);
        m.memory.write16(0x04000200, 0x0207); m.memory.write16(0x04000208, 1);
        m.memory.write16(0x03000100, 0x0201);
        m.memory.write32(0x040000bc, 0x03000100); m.memory.write32(0x040000c0, 0x03001000);
        m.memory.write16(0x040000c4, 1); m.memory.write16(0x040000c6, 0xe000);
        var rows = new int[DURATION + 1][];
        if (trace) {
            m.clock.setObserver((event, before) -> {
                var cycle = (int)(m.clock.now() - start);
                // Refresh after all same-time callbacks. DMA writes become
                // visible only after the timed bus operation returns.
                if (!before && cycle >= 0 && cycle <= DURATION) rows[cycle] = values(m);
            });
            CycleEvent sample = new CycleEvent() {
                public void fire() { if (m.clock.now() < start + DURATION) m.clock.schedule(this, m.clock.now() + 1); }
            };
            m.clock.schedule(sample, start); m.clock.advance(0);
        }
        while (m.clock.now() < start + DURATION) if (!m.memory.dma().runPending()) m.clock.advance(1);
        m.clock.setObserver(null);
        var result = values(m); var visible = line < 160;
        if (result[0] != (line + 1) % 228 || result[6] != (visible ? 0x0201 : 0) || result[5] != (visible ? 0 : 1)) {
            throw new IllegalStateException("Natural LCD/DMA completion invariant failed");
        }
        if (trace) for (var cycle = 0; cycle <= DURATION; cycle++) {
            if (rows[cycle] == null) throw new IllegalStateException("Missing observer sample");
            System.out.printf("%d,%d,%d", line, blank, cycle);
            for (var value : rows[cycle]) System.out.print("," + value);
            System.out.println();
        }
        return MachineState.save(m);
    }
    private static int[] values(Machine m) {
        return new int[]{m.memory.read16(0x04000006), m.memory.read16(0x04000004),
            m.memory.read16(0x04000202), m.memory.read16(0x04000208), m.memory.interrupts().irqPending() ? 1 : 0,
            (m.memory.read16(0x040000c6) >>> 15) & 1, m.memory.read16(0x03001000)};
    }
}

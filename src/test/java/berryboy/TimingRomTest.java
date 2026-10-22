package berryboy;

import static berryboy.EmulatorFixtures.arm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Original ARM program: arm a timer and HBlank DMA, poll IF, write a signature. */
@Tag("core")
class TimingRomTest {
    @Test
    void lcdTimerDmaAndIrqRequestsProduceDeterministicGuestCheckpoints() {
        var first = runRom();
        assertThat(first).isEqualTo(runRom());
        assertThat(first).isNotEmpty();
    }

    private List<String> runRom() {
        var m = arm(0xe1c010b0, 0xe1c230b0, 0xe1d450b0, 0xe3150c01,
                0x0afffffc, 0xe5865000, 0xeafffffe);
        m.cpu.setRegister(0, 0x04000102);
        m.cpu.setRegister(1, 0xc0);
        m.cpu.setRegister(2, 0x040000ba);
        m.cpu.setRegister(3, 0xe000); // DMA0: HBlank, IRQ, halfword.
        m.cpu.setRegister(4, 0x04000202);
        m.cpu.setRegister(6, 0x03000010);
        m.memory.write16(0x04000100, 0xff00);
        m.memory.write16(0x04000004, 0x10);
        m.memory.write32(0x040000b0, 0x02000000);
        m.memory.write32(0x040000b4, 0x03000000);
        m.memory.write16(0x040000b8, 1);
        m.memory.write16(0x02000000, 0xbeef);
        var trace = new ArrayList<String>();
        long[] hblank = {-1};
        m.clock.schedule(() -> hblank[0] = m.clock.now(), 960);
        for (int steps = 0; steps < 2000 && m.cpu.pc() != Memory.ROM_BASE + 24; steps++) {
            m.cpu.step();
            trace.add(m.clock.now() + ":" + m.cpu.pc() + ":" + m.memory.read16(0x04000202));
        }
        assertSoftly(s -> {
            s.assertThat(m.cpu.pc()).isEqualTo(Memory.ROM_BASE + 24);
            s.assertThat(m.memory.read16(0x03000000)).isEqualTo(0xbeef);
            s.assertThat(m.memory.read32(0x03000010) & 0x10a).isEqualTo(0x10a);
            s.assertThat(hblank[0]).isEqualTo(960);
            s.assertThat(m.clock.now()).isBetween(960L, 1232L);
        });
        return trace;
    }
}

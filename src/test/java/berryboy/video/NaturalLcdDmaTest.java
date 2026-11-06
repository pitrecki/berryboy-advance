package berryboy.video;

import berryboy.Machine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/** Common terminal results from both independent models, without freezing disputed phase edges. */
@Tag("core")
class NaturalLcdDmaTest {
    @ParameterizedTest
    @CsvFileSource(resources = "/berryboy/video/lcd-natural-terminal.csv", numLinesToSkip = 1)
    void naturalBlankingGatesDmaAndRaisesInterruptsEvenWithForcedBlank(int line, int blank, int vcount,
            int dispstat, int irq, int ime, int dmaEnabled, int destination) {
        var m = new Machine(new byte[512]);
        while ((m.memory.read16(0x04000006) != line || (m.memory.read16(0x04000004) & 2) != 0)
                && m.clock.now() < 2L * LcdTiming.FRAME_CYCLES) m.clock.advance(1);
        assertThat(m.memory.read16(0x04000006)).isEqualTo(line);
        assertThat(m.memory.read16(0x04000004) & 2).isZero();
        var start = m.clock.now();
        m.memory.write16(0x04000000, blank == 0 ? 0 : 0x80);
        m.memory.write8(0x04000005, (line + 1) % 228); m.memory.write8(0x04000004, 0x38);
        m.memory.write16(0x04000200, 0x0207); m.memory.write16(0x04000208, 1);
        m.memory.write16(0x03000100, 0x0201);
        m.memory.write32(0x040000bc, 0x03000100); m.memory.write32(0x040000c0, 0x03001000);
        m.memory.write16(0x040000c4, 1); m.memory.write16(0x040000c6, 0xe000);
        // End after the next line's status/IRQ updates in both references.
        while (m.clock.now() < start + 1248) if (!m.memory.dma().runPending()) m.clock.advance(1);
        assertSoftly(s -> {
            s.assertThat(m.memory.read16(0x04000006)).isEqualTo(vcount);
            s.assertThat(m.memory.read16(0x04000004)).isEqualTo(dispstat);
            s.assertThat(m.memory.read16(0x04000202)).isEqualTo(irq);
            s.assertThat(m.memory.read16(0x04000208)).isEqualTo(ime);
            s.assertThat((m.memory.read16(0x040000c6) >>> 15) & 1).isEqualTo(dmaEnabled);
            s.assertThat(m.memory.read16(0x03001000)).isEqualTo(destination);
            s.assertThat(m.memory.read16(0x03000100)).isEqualTo(0x0201);
            s.assertThat(m.cpu.instructions()).isZero();
        });
    }
}

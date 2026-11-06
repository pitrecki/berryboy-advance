package berryboy.bus;

import berryboy.EmulatorFixtures;
import berryboy.Machine;
import berryboy.cpu.Cpu;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

/** CPU arbitration at known readiness; production request activation is separate. */
@Tag("core")
class DmaActivationReadinessTest {
    @ParameterizedTest
    @CsvFileSource(resources = "/berryboy/bus/dma-activation.csv", numLinesToSkip = 1)
    void retiresInstructionsAtIndependentReadinessBoundaries(String mode, String program, long source,
            int channels, int retired, long cycle, int r0, long pc) {
        var thumb = mode.equals("thumb");
        var load = program.equals("load");
        var m = new Machine(EmulatorFixtures.blankRom());
        for (var i = 0; i < 32; i++) {
            if (thumb) m.memory.write16(0x03000000 + i * 2, load ? 0x6810 : 0x3001);
            else m.memory.write32(0x03000000 + i * 4, load ? 0xe5920000 : 0xe2800001);
        }
        m.cpu.setPc(0x03000000);
        m.cpu.setCpsr(Cpu.SYSTEM | 0xc0 | (thumb ? Cpu.T : 0));
        m.cpu.setRegister(2, 0x03000200);
        m.memory.write32(0x03000200, 0x12345678);
        m.clock.advance(thumb ? 5 : 2); // Same excluded pipeline warmup as the NBA adapter.
        var start = m.clock.now();
        for (var channel = 1; channel <= channels; channel++) {
            var base = 0x040000b0 + channel * 12;
            m.memory.write32(base, (int) source + 0x100);
            m.memory.write32(base + 4, 0x040000a0 + (channel - 1) * 4);
            m.memory.write16(base + 8, 4);
            m.memory.write16(base + 10, 0xb640);
        }
        m.clock.schedule(() -> {
            for (var channel = 1; channel <= channels; channel++) {
                m.memory.dma().requestFifo(0x040000a0 + (channel - 1) * 4);
            }
        }, start + 2);
        while (m.cpu.instructions() < retired) {
            if (m.clock.now() - start > 1000) throw new IllegalStateException("CPU did not resume");
            m.cpu.step();
        }
        assertSoftly(s -> {
            s.assertThat(m.clock.now() - start).isEqualTo(cycle);
            s.assertThat(m.cpu.register(0)).isEqualTo(r0);
            s.assertThat(m.cpu.pc()).isEqualTo((int) pc);
            s.assertThat(m.cpu.instructions()).isEqualTo(retired);
        });
    }
}

package berryboy.bus;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import berryboy.EmulatorFixtures;
import berryboy.Machine;
import berryboy.cpu.Cpu;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/** Expected observations were captured from the pinned, unmodified NBA CPU. */
@Tag("core")
class IrqFetchTimingTest {
    @ParameterizedTest
    @CsvFileSource(resources = "/berryboy/bus/irq-fetch.csv", numLinesToSkip = 1)
    void chargesTheOriginalModeAndSourceWaits(String mode, long pc, int ready, long cycles,
            long finalPc, long status, long link, int r0, int dmaComplete) {
        compare(mode.equals("thumb"), (int) pc, 0, 0, ready, 0x03000100, cycles, finalPc, status, link, r0, dmaComplete);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/berryboy/bus/irq-prefetch.csv", numLinesToSkip = 1)
    void preservesPrefetchAndPhysicalPageTiming(String mode, long pc, int prefetch, int idle, int ready,
            long cycles, long finalPc, long status, long link, int r0, int dmaComplete) {
        compare(mode.equals("thumb"), (int) pc, prefetch, idle, ready, 0x03000100, cycles, finalPc, status, link, r0, dmaComplete);
    }

    @ParameterizedTest
    @CsvFileSource(resources = "/berryboy/bus/irq-cartridge-dma.csv", numLinesToSkip = 1)
    void arbitratesCartridgeDmaAgainstIrqDiscardedFetches(String mode, long pc, int prefetch, int idle, int ready,
            long cycles, long finalPc, long status, long link, int r0, int dmaComplete, long source) {
        compare(mode.equals("thumb"), (int) pc, prefetch, idle, ready, (int)source, cycles, finalPc, status, link, r0, dmaComplete);
    }

    private static void compare(boolean thumb, int pc, int prefetch, int idle, int ready, int source, long cycles,
            long finalPc, long status, long link, int r0, int dmaComplete) {
        var width = thumb ? 2 : 4;
        var image = new byte[0x20040];
        for (var i = 0; i < image.length; i++) image[i] = (byte) ((thumb ? 0x3001 : 0xe2800001) >>> ((i % width) * 8));
        var m = new Machine(image);
        var bios = new byte[0x4000];
        EmulatorFixtures.writeWord(bios, 0x18, 0xe1a00000);
        m.memory.setBios(bios);
        m.memory.write16(0x04000204, prefetch == 0 ? 0 : 0x4000);
        if ((pc >>> 24) < 8) {
            if (thumb) m.memory.write16(pc - width, 0x3001);
            else m.memory.write32(pc - width, 0xe2800001);
        }
        m.memory.write32(0x03000100, 0x04030201);
        m.cpu.setCpsr(Cpu.SYSTEM | 0x40 | (thumb ? Cpu.T : 0));
        m.cpu.setPc(pc - width);
        m.cpu.step(); // Establish a real sequential bus stream before accepting IRQ.
        m.cpu.setRegister(0, 0);
        m.memory.busTiming().internal(idle, false);
        var start = m.clock.now();
        m.memory.write16(0x04000200, 8);
        m.memory.write16(0x04000208, 1);
        m.memory.interrupts().request(8);
        if (ready >= 0) {
            m.memory.write32(0x040000d4, source);
            m.memory.write32(0x040000d8, 0x03001000);
            m.memory.write16(0x040000dc, 1);
            m.memory.write16(0x040000de, 0xa400);
            m.clock.schedule(() -> m.memory.dma().request(2), start + ready);
        }
        m.clock.advance(0);
        while (m.cpu.instructions() == 1) {
            if (m.clock.now() - start > 1000) throw new IllegalStateException("IRQ did not complete");
            m.cpu.step();
        }
        var data = source == 0x03000100 ? 0x04030201 : thumb ? 0x30013001 : 0xe2800001;
        assertSoftly(s -> {
            s.assertThat(m.clock.now() - start).isEqualTo(cycles);
            s.assertThat(m.cpu.pc()).isEqualTo((int) finalPc);
            s.assertThat(m.cpu.cpsr()).isEqualTo((int) status);
            s.assertThat(m.cpu.register(14)).isEqualTo((int) link);
            s.assertThat(m.cpu.register(0)).isEqualTo(r0);
            s.assertThat(m.memory.read32(0x03001000)).isEqualTo(dmaComplete == 0 ? 0 : data);
            s.assertThat(m.cpu.instructions()).isEqualTo(2);
            s.assertThat(m.cpu.fetchInFlight()).isFalse();
            s.assertThat(m.memory.busTiming().refilling()).isFalse();
        });
    }
}

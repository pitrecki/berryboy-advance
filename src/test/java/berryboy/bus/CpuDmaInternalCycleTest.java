package berryboy.bus;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import berryboy.EmulatorFixtures;
import berryboy.Machine;
import berryboy.cpu.Cpu;
import berryboy.video.LcdTiming;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("core")
class CpuDmaInternalCycleTest {
    static Stream<Arguments> transfers() {
        return Stream.of(false, true).flatMap(thumb -> Stream.of(0x03000000, 0x02000000, 0x08000000)
                .flatMap(source -> Stream.of(1, 2).map(channels -> Arguments.of(thumb, source, channels))));
    }

    @ParameterizedTest
    @MethodSource("transfers")
    void readyFifoDmaOverlapsLoadInternalCycleBeforeRetirement(boolean thumb, int source, int channels) {
        var m = load(thumb);
        configureFifos(m, source, channels);
        m.clock.schedule(() -> requestFifos(m, channels), 2); // NBA readiness, activation excluded.
        m.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(m.cpu.instructions()).isZero();
            softly.assertThat(m.cpu.instructionInFlight()).isTrue();
            softly.assertThat(m.clock.now()).isLessThan(2 + ownership(source, channels));
        });
        while (m.cpu.instructions() == 0) m.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(m.clock.now()).isEqualTo(2 + ownership(source, channels));
            softly.assertThat(m.cpu.instructions()).isEqualTo(1);
            softly.assertThat(m.cpu.instructionInFlight()).isFalse();
            softly.assertThat(m.cpu.register(0)).isEqualTo(0x12345678);
            softly.assertThat(m.cpu.pc()).isEqualTo(0x03000000 + (thumb ? 2 : 4));
        });
    }

    @Test
    void longDmaYieldsAcrossFrameBudgetAndDoesNotReexecuteTheLoadedInstruction() {
        var m = load(false);
        m.memory.clockedAudio().setCapture(true);
        m.memory.write32(0x02000100, 0x01020304);
        m.memory.write32(0x040000d4, 0x02000100);
        m.memory.write32(0x040000d8, 0x03001000);
        m.memory.write16(0x040000dc, 0); // 65536 words, wrapping over the whole IWRAM.
        m.memory.write16(0x040000de, 0xa500); // HBlank, word, fixed source.
        m.clock.schedule(() -> m.memory.dma().request(2), 2);
        m.runCycles(LcdTiming.FRAME_CYCLES);
        assertSoftly(softly -> {
            softly.assertThat(m.clock.now()).isBetween((long) LcdTiming.FRAME_CYCLES, LcdTiming.FRAME_CYCLES + 6L);
            softly.assertThat(m.memory.raster().completedFrames()).isEqualTo(1);
            softly.assertThat(m.cpu.instructions()).isZero();
            softly.assertThat(m.cpu.instructionInFlight()).isTrue();
            softly.assertThat(m.memory.clockedAudio().availableFrames()).isEqualTo(548);
        });
        var pcm = new short[548 * 2];
        assertSoftly(softly -> softly.assertThat(m.memory.clockedAudio().drain(pcm)).isEqualTo(548));
        m.runCycles(458756 - m.clock.now()); // Load bus work 2 + DMA (2 + 65536*7).
        assertSoftly(softly -> {
            softly.assertThat(m.clock.now()).isEqualTo(458756);
            softly.assertThat(m.cpu.instructions()).isEqualTo(1);
            softly.assertThat(m.cpu.instructionInFlight()).isFalse();
            softly.assertThat(m.cpu.register(0)).isEqualTo(0x12345678); // Latched before DMA overwrote both code/data.
            softly.assertThat(m.memory.read32(0x03000200)).isEqualTo(0x01020304);
            softly.assertThat(m.cpu.pc()).isEqualTo(0x03000004);
            softly.assertThat(m.memory.clockedAudio().producedFrames()).isEqualTo(896);
            softly.assertThat(m.memory.clockedAudio().availableFrames()).isEqualTo(348);
            softly.assertThat(m.memory.clockedAudio().overruns()).isZero();
            softly.assertThat(m.memory.clockedAudio().underruns()).isZero();
        });
    }

    @Test
    void shortDmaLeavesUncoveredMultiplyCyclesToResumeWithoutReexecution() {
        var m = load(false);
        m.memory.write32(0x03000000, 0xe0a32190); // UMLAL r2,r3,r0,r1: six internal cycles.
        m.cpu.setRegister(0, 3);
        m.cpu.setRegister(1, 0x01000000);
        m.cpu.setRegister(2, 7);
        m.memory.write32(0x040000d4, 0x03000100);
        m.memory.write32(0x040000d8, 0x03000200);
        m.memory.write16(0x040000dc, 1);
        m.memory.write16(0x040000de, 0xa000);
        m.clock.schedule(() -> m.memory.dma().request(2), 2);
        m.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(m.clock.now()).isEqualTo(6); // Fetch 1 + internal 1 + DMA 4.
            softly.assertThat(m.cpu.instructions()).isZero();
            softly.assertThat(m.cpu.instructionInFlight()).isTrue();
        });
        m.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(m.clock.now()).isEqualTo(7);
            softly.assertThat(m.cpu.instructions()).isEqualTo(1);
            softly.assertThat(m.cpu.instructionInFlight()).isFalse();
            softly.assertThat(m.cpu.register(2)).isEqualTo(0x03000007);
            softly.assertThat(m.cpu.register(3)).isZero();
        });
    }

    @Test
    void requestDuringReleaseDoesNotExtendTheCompletedInstructionIntoTheNextInterval() {
        var m = load(false);
        configureFifos(m, 0x03000000, 1);
        m.memory.write32(0x040000d4, 0x03000100);
        m.memory.write32(0x040000d8, 0x03001000);
        m.memory.write16(0x040000dc, 1);
        m.memory.write16(0x040000de, 0xa000);
        m.clock.schedule(() -> m.memory.dma().request(2), 2);
        m.clock.schedule(() -> requestFifos(m, 1), 6); // Release of the four-cycle primary interval.
        while (m.cpu.instructions() == 0) m.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(m.clock.now()).isEqualTo(6);
            softly.assertThat(m.cpu.instructions()).isEqualTo(1);
            softly.assertThat(m.cpu.instructionInFlight()).isFalse();
            softly.assertThat(m.memory.dma().ownsBus()).isFalse();
        });
        for (var word = 0; word < 4; word++) m.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(m.clock.now()).isEqualTo(16);
            softly.assertThat(m.cpu.instructions()).isEqualTo(1);
            softly.assertThat(m.cpu.pc()).isEqualTo(0x03000004);
        });
    }

    @Test
    void irqEntryWaitsUntilTheSuspendedInstructionRetires() {
        var m = load(false);
        m.memory.setBios(new byte[16384]);
        m.cpu.setCpsr(Cpu.SYSTEM);
        m.memory.write16(0x04000200, 0x8);
        m.memory.write16(0x04000208, 1);
        configureFifos(m, 0x03000000, 1);
        m.clock.schedule(() -> requestFifos(m, 1), 2);
        m.clock.schedule(() -> m.memory.interrupts().request(0x8), 6);
        while (m.cpu.instructions() == 0) m.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(m.clock.now()).isEqualTo(12);
            softly.assertThat(m.cpu.cpsr() & 31).isEqualTo(Cpu.SYSTEM);
            softly.assertThat(m.cpu.pc()).isEqualTo(0x03000004);
            softly.assertThat(m.cpu.register(0)).isEqualTo(0x12345678);
        });
        m.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(m.cpu.cpsr() & 31).isEqualTo(Cpu.IRQ);
            softly.assertThat(m.cpu.pc()).isEqualTo(0x18);
            softly.assertThat(m.cpu.instructions()).isEqualTo(1);
        });
    }

    @Test
    void readinessAtEndOfTheFinalInternalCycleDoesNotDelayRetirement() {
        var m = load(false);
        configureFifos(m, 0x03000000, 1);
        m.clock.schedule(() -> requestFifos(m, 1), 3);
        m.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(m.clock.now()).isEqualTo(3);
            softly.assertThat(m.cpu.instructions()).isEqualTo(1);
            softly.assertThat(m.cpu.instructionInFlight()).isFalse();
        });
        m.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(m.clock.now()).isGreaterThan(3);
            softly.assertThat(m.cpu.instructions()).isEqualTo(1);
        });
    }

    private static Machine load(boolean thumb) {
        var image = EmulatorFixtures.blankRom();
        for (var i = 0; i < 16; i++) image[0x100 + i] = (byte) (i + 1);
        var m = new Machine(image);
        for (var i = 0; i < 16; i++) {
            m.memory.write8(0x03000100 + i, i + 1);
            m.memory.write8(0x02000100 + i, i + 1);
        }
        if (thumb) m.memory.write16(0x03000000, 0x6810); else m.memory.write32(0x03000000, 0xe5920000);
        m.cpu.setCpsr(Cpu.SYSTEM | 0xc0 | (thumb ? Cpu.T : 0));
        m.cpu.setPc(0x03000000);
        m.cpu.setRegister(2, 0x03000200);
        m.memory.write32(0x03000200, 0x12345678);
        m.memory.write16(0x04000084, 0x80);
        m.memory.write16(0x04000082, 0x210c);
        return m;
    }

    private static void configureFifos(Machine m, int source, int channels) {
        for (var c = 1; c <= channels; c++) {
            var base = 0x040000b0 + c * 12;
            m.memory.write32(base, source + 0x100);
            m.memory.write32(base + 4, 0x040000a0 + (c - 1) * 4);
            m.memory.write16(base + 8, 4);
            m.memory.write16(base + 10, 0xb640);
        }
    }

    private static void requestFifos(Machine m, int channels) {
        for (var c = 1; c <= channels; c++) m.memory.dma().requestFifo(0x040000a0 + (c - 1) * 4);
    }

    private static int ownership(int source, int channels) {
        return source == 0x03000000 ? (channels == 1 ? 10 : 18)
                : source == 0x02000000 ? (channels == 1 ? 30 : 58) : (channels == 1 ? 32 : 62);
    }
}

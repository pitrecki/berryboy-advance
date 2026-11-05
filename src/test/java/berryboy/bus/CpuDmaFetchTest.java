package berryboy.bus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import berryboy.EmulationException;
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
class CpuDmaFetchTest {
    static Stream<Arguments> handoffs() {
        return Stream.of(false, true).flatMap(thumb -> Stream.of(false, true).flatMap(irq ->
                Stream.of(4, 5).flatMap(ready -> Stream.of(1, 2).map(retired -> Arguments.of(thumb, irq, ready, retired)))));
    }

    @ParameterizedTest(name = "thumb={0}, irq={1}, nextReady={2}, retired={3}")
    @MethodSource("handoffs")
    void waitingFetchPrecedesANewDmaIntervalAndLateIrq(boolean thumb, boolean irq, int ready, int retired) {
        // Selected original NBA observations: tools/nba-cpu-fetch-handoff-probe.cc.
        var m = create(thumb);
        if (!irq) m.cpu.setCpsr(Cpu.SYSTEM | 0xc0 | (thumb ? Cpu.T : 0));
        configure(m, 3, 0x03000100, 0x03001000, 1, 0xa400);
        configure(m, 1, 0x03000100, 0x03001100, 1, 0x9400);
        m.clock.schedule(() -> m.memory.dma().request(2), 0);
        m.clock.schedule(() -> m.memory.dma().request(1), ready);
        if (irq) m.clock.schedule(() -> m.memory.interrupts().request(8), ready);
        m.clock.advance(0);
        retireTo(m, retired);
        var handler = irq && retired == 2;
        var width = thumb ? 2 : 4;
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isEqualTo(retired == 1 ? 5 : irq ? 13 : 10);
            s.assertThat(m.cpu.register(0)).isEqualTo(handler ? 1 : retired);
            s.assertThat(m.cpu.pc()).isEqualTo(handler ? 0x1c : 0x03000000 + retired * width);
            s.assertThat(m.cpu.cpsr()).isEqualTo(handler ? 0xd2 : Cpu.SYSTEM | (irq ? 0x40 : 0xc0) | (thumb ? Cpu.T : 0));
            s.assertThat(m.cpu.register(14)).isEqualTo(handler ? 0x03000004 + width : 0);
            s.assertThat(m.memory.read32(0x03001100)).isEqualTo(retired == 1 ? 0 : 0x04030201);
            s.assertThat(m.cpu.instructions()).isEqualTo(retired);
        });
    }

    static Stream<Arguments> irqEdges() {
        return Stream.of(false, true).flatMap(thumb -> Stream.of(0x03000000, 0x02000000, 0x08000000).flatMap(source -> {
            var duration = duration(source);
            return Stream.of(false, true).flatMap(drop -> Stream.of(0, 1, duration - 1, duration, duration + 1)
                    .map(edge -> Arguments.of(thumb, source, drop, edge)));
        }));
    }

    @ParameterizedTest(name = "thumb={0}, source={1}, deassert={2}, edge={3}")
    @MethodSource("irqEdges")
    void irqDecisionSurvivesAssertionOrDeassertionDuringTheWaitingAccess(boolean thumb, int source, boolean drop, int edge) {
        // Two retirement states per scenario match tools/nba-cpu-fetch-irq-probe.cc.
        var m = create(thumb);
        if (drop) m.memory.interrupts().request(8);
        configure(m, 3, source + 0x100, 0x03001000, 1, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), 0);
        m.clock.schedule(() -> {
            if (drop) m.memory.write16(0x04000202, 8);
            else m.memory.interrupts().request(8);
        }, edge);
        m.clock.advance(0);
        var acceptedAtStart = drop ? edge != 0 : edge == 0;
        var lateIrq = !drop && edge != 0;
        for (var retired = 1; retired <= 2; retired++) {
            retireTo(m, retired);
            var sample = retired;
            var handler = acceptedAtStart || lateIrq && sample == 2;
            var cycles = acceptedAtStart ? duration(source) + 3 + sample
                    : lateIrq && sample == 2 ? duration(source) + 5 : duration(source) + sample;
            var width = thumb ? 2 : 4;
            assertSoftly(s -> {
                s.assertThat(m.clock.now()).isEqualTo(cycles);
                s.assertThat(m.cpu.register(0)).isEqualTo(acceptedAtStart ? 0 : handler ? 1 : sample);
                s.assertThat(m.cpu.pc()).isEqualTo(handler ? acceptedAtStart ? 0x18 + sample * 4 : 0x1c
                        : 0x03000000 + sample * width);
                s.assertThat(m.cpu.cpsr()).isEqualTo(handler ? 0xd2 : Cpu.SYSTEM | 0x40 | (thumb ? Cpu.T : 0));
                s.assertThat(m.cpu.register(14)).isEqualTo(handler ? acceptedAtStart ? 0x03000004 : 0x03000004 + width : 0);
                s.assertThat(m.memory.read32(0x03001000)).isEqualTo(0x04030201);
            });
        }
    }

    @Test
    void irqBanksStateOnlyAfterItsDiscardedFetchFinishes() {
        var m = create(false);
        m.memory.interrupts().request(8);
        configure(m, 3, 0x03000100, 0x03001000, 1, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), 0);
        var seen = new int[2];
        m.clock.schedule(() -> {
            seen[0] = m.cpu.cpsr();
            seen[1] = m.cpu.register(14);
        }, 5); // DMA 4 + discarded fetch 1, before accepted IRQ banks its state.
        m.clock.advance(0);
        retireTo(m, 1);
        assertSoftly(s -> {
            s.assertThat(seen).containsExactly(0x5f, 0);
            s.assertThat(m.cpu.cpsr()).isEqualTo(0xd2);
            s.assertThat(m.cpu.register(14)).isEqualTo(0x03000004);
        });
    }

    @Test
    void haltDoesNotCreateAWaitingFetchBeforeWakeup() {
        var m = create(false);
        m.cpu.setCpsr(Cpu.SYSTEM | 0x80);
        m.memory.write16(0x04000208, 0); // IE can wake HALT even with IME off.
        m.memory.write8(0x04000301, 0);
        configure(m, 3, 0x03000100, 0x03001000, 1, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), 0);
        m.clock.schedule(() -> m.memory.interrupts().request(8), 5);
        m.clock.advance(0);
        m.cpu.step();
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isEqualTo(4);
            s.assertThat(m.cpu.fetchInFlight()).isFalse();
            s.assertThat(m.memory.halted()).isTrue();
        });
        m.cpu.step(); // Sleep to the wake event, without starting an opcode fetch.
        m.cpu.step();
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isEqualTo(6);
            s.assertThat(m.cpu.instructions()).isEqualTo(1);
            s.assertThat(m.cpu.register(0)).isEqualTo(1);
            s.assertThat(m.memory.halted()).isFalse();
            s.assertThat(m.cpu.fetchInFlight()).isFalse();
        });
    }

    @Test
    void aDeferredFetchFaultClearsPendingStateAndCpuMemoryContext() {
        var m = create(false);
        m.cpu.setPc(0x01000000);
        configure(m, 3, 0x03000100, 0x03001000, 1, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), 0);
        m.clock.advance(0);
        m.cpu.step();
        assertThatThrownBy(m.cpu::step).isInstanceOf(EmulationException.class).hasMessageContaining("pc=0x1000000");
        assertSoftly(s -> {
            s.assertThat(m.cpu.fetchInFlight()).isFalse();
            s.assertThat(m.cpu.instructionInFlight()).isFalse();
            s.assertThat(m.cpu.instructions()).isZero();
            s.assertThat(m.cpu.pc()).isEqualTo(0x01000000);
        });
        assertThatThrownBy(() -> m.memory.read8(0x01000000)).isInstanceOf(EmulationException.class);
    }

    static Stream<Arguments> longTransfers() {
        return Stream.of(false, true).flatMap(thumb -> Stream.of(false, true).map(drop -> Arguments.of(thumb, drop)));
    }

    @ParameterizedTest
    @MethodSource("longTransfers")
    void waitingFetchAndIrqDecisionSurviveFrameBudgets(boolean thumb, boolean drop) {
        var m = create(thumb);
        if (drop) m.memory.interrupts().request(8);
        m.memory.write32(0x02000100, 0x01020304);
        m.memory.clockedAudio().setCapture(true);
        // 65536 EWRAM-to-EWRAM words; code in IWRAM is never overwritten.
        configure(m, 3, 0x02000100, 0x02001000, 0, 0xa500);
        m.clock.schedule(() -> m.memory.dma().request(2), 0);
        m.clock.schedule(() -> {
            if (drop) m.memory.write16(0x04000202, 8);
            else m.memory.interrupts().request(8);
        }, 100);
        m.clock.advance(0);
        m.runCycles(LcdTiming.FRAME_CYCLES);
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isBetween((long) LcdTiming.FRAME_CYCLES, LcdTiming.FRAME_CYCLES + 11L);
            s.assertThat(m.cpu.fetchInFlight()).isTrue();
            s.assertThat(m.cpu.instructionInFlight()).isFalse();
            s.assertThat(m.cpu.instructions()).isZero();
            s.assertThat(m.cpu.pc()).isEqualTo(0x03000000);
            s.assertThat(m.cpu.cpsr()).isEqualTo(Cpu.SYSTEM | 0x40 | (thumb ? Cpu.T : 0));
            s.assertThat(m.cpu.register(0)).isZero();
            s.assertThat(m.cpu.register(14)).isZero();
            s.assertThat(m.memory.raster().completedFrames()).isEqualTo(1);
            s.assertThat(m.memory.clockedAudio().drain(new short[548 * 2])).isEqualTo(548);
        });
        var end = drop ? 786437L : 786435L; // DMA 2 + 65536*12; opcode 1 or IRQ discarded fetch/refill 3.
        m.runCycles(end - m.clock.now());
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isEqualTo(end);
            s.assertThat(m.cpu.fetchInFlight()).isFalse();
            s.assertThat(m.cpu.instructions()).isEqualTo(drop ? 0 : 1);
            s.assertThat(m.cpu.pc()).isEqualTo(drop ? 0x18 : 0x03000000 + (thumb ? 2 : 4));
            s.assertThat(m.cpu.register(0)).isEqualTo(drop ? 0 : 1);
            s.assertThat(m.cpu.register(14)).isEqualTo(drop ? 0x03000004 : 0);
            s.assertThat(m.memory.clockedAudio().producedFrames()).isEqualTo(1536);
            s.assertThat(m.memory.clockedAudio().overruns()).isZero();
            s.assertThat(m.memory.clockedAudio().underruns()).isZero();
        });
        m.cpu.step();
        assertSoftly(s -> {
            s.assertThat(m.cpu.pc()).isEqualTo(drop ? 0x1c : 0x18);
            s.assertThat(m.cpu.instructions()).isEqualTo(1);
            s.assertThat(m.cpu.register(14)).isEqualTo(drop ? 0x03000004 : 0x03000004 + (thumb ? 2 : 4));
        });
    }

    private static Machine create(boolean thumb) {
        var image = EmulatorFixtures.blankRom();
        for (var i = 0; i < 16; i++) image[0x100 + i] = (byte) (i + 1);
        var m = new Machine(image);
        var bios = new byte[0x4000];
        for (var i = 0; i < 3; i++) EmulatorFixtures.writeWord(bios, 0x18 + i * 4, 0xe1a00000);
        m.memory.setBios(bios);
        for (var i = 0; i < 16; i++) {
            m.memory.write8(0x03000100 + i, i + 1);
            m.memory.write8(0x02000100 + i, i + 1);
        }
        for (var i = 0; i < 32; i++) {
            if (thumb) m.memory.write16(0x03000000 + i * 2, 0x3001);
            else m.memory.write32(0x03000000 + i * 4, 0xe2800001);
        }
        m.cpu.setPc(0x03000000);
        m.cpu.setCpsr(Cpu.SYSTEM | 0x40 | (thumb ? Cpu.T : 0));
        m.memory.write16(0x04000200, 8);
        m.memory.write16(0x04000208, 1);
        return m;
    }

    private static int duration(int source) { return source == 0x03000000 ? 4 : source == 0x02000000 ? 9 : 11; }

    private static void configure(Machine m, int channel, int source, int destination, int count, int control) {
        var base = 0x040000b0 + channel * 12;
        m.memory.write32(base, source);
        m.memory.write32(base + 4, destination);
        m.memory.write16(base + 8, count);
        m.memory.write16(base + 10, control);
    }

    private static void retireTo(Machine m, int count) {
        for (var step = 0; m.cpu.instructions() < count; step++) {
            if (step > 1000) throw new IllegalStateException("CPU did not retire");
            m.cpu.step();
        }
    }
}

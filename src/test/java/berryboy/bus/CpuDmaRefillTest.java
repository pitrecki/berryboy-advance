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
import org.junit.jupiter.params.provider.ValueSource;

@Tag("core")
class CpuDmaRefillTest {
    static Stream<Arguments> branches() {
        return Stream.of(false, true).flatMap(thumb -> Stream.of(0x03000400, 0x08000100)
                .flatMap(target -> Stream.of(0x03000000, 0x02000000, 0x08000000)
                        .flatMap(source -> Stream.of(0, 1, 2).map(ready -> Arguments.of(thumb, target, source, ready)))));
    }

    @ParameterizedTest(name = "thumb={0}, target={1}, source={2}, ready={3}")
    @MethodSource("branches")
    void arbitratesEachRefillAccessBeforeRetiringTheBranch(boolean thumb, int target, int source, int ready) {
        // Exact selected observations from tools/nba-cpu-refill-probe.cc.
        var m = branch(thumb, target);
        if (ready != 0) {
            configureDma(m, source + 0x100, 0x03000200, 2, 0xa400);
            m.clock.schedule(() -> m.memory.dma().request(2), ready);
        }
        retire(m);
        var base = target == 0x03000400 ? 3 : thumb ? 9 : 15;
        var ownership = source == 0x03000000 ? 6 : source == 0x02000000 ? 16 : 18;
        var restart = ready == 2 && target == 0x08000100 ? 2 : 0;
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isEqualTo(base + (ready == 0 ? 0 : ownership) + restart);
            s.assertThat(m.cpu.pc()).isEqualTo(target);
            s.assertThat(m.cpu.isThumb()).isEqualTo(thumb);
            s.assertThat(m.cpu.instructions()).isEqualTo(1);
            s.assertThat(m.cpu.instructionInFlight()).isFalse();
            s.assertThat(m.memory.busTiming().refilling()).isFalse();
            s.assertThat(m.memory.read32(0x03000204)).isEqualTo(ready == 0 ? 0 : 0x08070605);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void longDmaYieldsDuringRefillAndDoesNotExecuteTheBranchTwice(int ready) {
        var m = branch(false, 0x03000400);
        m.memory.write32(0x03000000, 0xeb0000fe); // BL 0x03000400; preserve LR while code is overwritten.
        m.memory.write32(0x02000100, 0x01020304);
        m.memory.clockedAudio().setCapture(true);
        configureDma(m, 0x02000100, 0x03001000, 0, 0xa500);
        m.clock.schedule(() -> m.memory.dma().request(2), ready);
        m.runCycles(LcdTiming.FRAME_CYCLES);
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isBetween((long) LcdTiming.FRAME_CYCLES, LcdTiming.FRAME_CYCLES + 6L);
            s.assertThat(m.cpu.instructions()).isZero();
            s.assertThat(m.cpu.instructionInFlight()).isTrue();
            s.assertThat(m.memory.busTiming().refilling()).isTrue();
            s.assertThat(m.cpu.pc()).isEqualTo(0x03000400);
            s.assertThat(m.cpu.register(14)).isEqualTo(0x03000004);
            s.assertThat(m.memory.raster().completedFrames()).isEqualTo(1);
            s.assertThat(m.memory.clockedAudio().drain(new short[548 * 2])).isEqualTo(548);
        });
        m.runCycles(458757 - m.clock.now()); // Fetch/refill 3 + DMA 2 + 65536*7.
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isEqualTo(458757);
            s.assertThat(m.cpu.instructions()).isEqualTo(1);
            s.assertThat(m.cpu.pc()).isEqualTo(0x03000400);
            s.assertThat(m.cpu.register(14)).isEqualTo(0x03000004);
            s.assertThat(m.cpu.instructionInFlight()).isFalse();
            s.assertThat(m.memory.busTiming().refilling()).isFalse();
            s.assertThat(m.memory.clockedAudio().producedFrames()).isEqualTo(896);
            s.assertThat(m.memory.clockedAudio().overruns()).isZero();
            s.assertThat(m.memory.clockedAudio().underruns()).isZero();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void pendingIrqWaitsForBranchRetirement(int ready) {
        var m = branch(false, 0x03000400);
        enableIrq(m);
        configureDma(m, 0x03000100, 0x03001000, 2, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), ready);
        m.clock.schedule(() -> m.memory.interrupts().request(8), 3);
        retire(m);
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isEqualTo(9);
            s.assertThat(m.cpu.cpsr() & 31).isEqualTo(Cpu.SYSTEM);
            s.assertThat(m.cpu.pc()).isEqualTo(0x03000400);
        });
        m.cpu.step();
        assertSoftly(s -> {
            s.assertThat(m.cpu.pc()).isEqualTo(0x18);
            s.assertThat(m.cpu.register(14)).isEqualTo(0x03000404);
            s.assertThat(m.cpu.instructions()).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void irqRefillResumesWithoutBankingTheLinkAgainOrExecutingTheHandlerEarly(int ready) {
        var m = branch(true, 0x03000400);
        enableIrq(m);
        m.cpu.setCpsr(Cpu.SYSTEM | Cpu.T);
        m.memory.interrupts().request(8);
        configureDma(m, 0x03000100, 0x03001000, 2, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), ready);
        m.cpu.step();
        assertSoftly(s -> {
            s.assertThat(m.cpu.instructions()).isZero();
            s.assertThat(m.cpu.instructionInFlight()).isFalse(); // IRQ entry is not an instruction.
            s.assertThat(m.memory.busTiming().refilling()).isTrue();
            s.assertThat(m.cpu.pc()).isEqualTo(0x18);
            s.assertThat(m.cpu.register(14)).isEqualTo(0x03000004);
        });
        while (m.memory.busTiming().refilling()) m.cpu.step();
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isEqualTo(9);
            s.assertThat(m.cpu.instructions()).isZero();
            s.assertThat(m.cpu.pc()).isEqualTo(0x18);
            s.assertThat(m.cpu.register(14)).isEqualTo(0x03000004);
            s.assertThat(m.cpu.cpsr() & 0xbf).isEqualTo(Cpu.IRQ | 0x80);
        });
    }

    @Test
    void internalDmaCompletionStartsRefillButDoesNotServiceASecondUnitInTheSameStep() {
        var m = branch(false, 0x08000100);
        m.memory.write32(0x03000000, 0xe592f000); // LDR pc,[r2].
        m.cpu.setRegister(2, 0x03000200);
        m.memory.write32(0x03000200, 0x08000100);
        configureDma(m, 0x03000100, 0x03001000, 1, 0xa400);
        m.memory.write32(0x040000bc, 0x03000100);
        m.memory.write32(0x040000c0, 0x03001100);
        m.memory.write16(0x040000c4, 1);
        m.memory.write16(0x040000c6, 0x9400);
        m.clock.schedule(() -> m.memory.dma().request(2), 2);
        m.clock.schedule(() -> m.memory.dma().request(1), 6);
        m.cpu.step();
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isEqualTo(6);
            s.assertThat(m.cpu.instructions()).isZero();
            s.assertThat(m.memory.busTiming().refilling()).isTrue();
            s.assertThat(m.memory.read32(0x03001000)).isEqualTo(0x04030201);
            s.assertThat(m.memory.read32(0x03001100)).isZero();
        });
        m.cpu.step();
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isEqualTo(10);
            s.assertThat(m.cpu.instructions()).isZero();
        });
        retire(m);
        assertSoftly(s -> s.assertThat(m.clock.now()).isEqualTo(24));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stateChangingBxUsesTheTargetWidthWhenDmaInterruptsRomRefill(boolean thumb) {
        var m = branch(thumb, 0x08000100);
        m.cpu.setRegister(2, 0x08000100 | (thumb ? 0 : 1));
        configureDma(m, 0x03000100, 0x03001000, 2, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), 2);
        retire(m);
        assertSoftly(s -> {
            s.assertThat(m.cpu.isThumb()).isEqualTo(!thumb);
            s.assertThat(m.cpu.pc()).isEqualTo(0x08000100);
            s.assertThat(m.clock.now()).isEqualTo(thumb ? 23 : 17);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void swiRefillResumesAfterBankingStatusAndLink(boolean thumb) {
        var m = branch(thumb, 0x03000400);
        m.memory.setBios(new byte[0x4000]);
        m.cpu.setRegister(13, 0x03000200);
        if (thumb) m.memory.write16(0x03000000, 0xdf00);
        else m.memory.write32(0x03000000, 0xef000000);
        configureDma(m, 0x03000100, 0x03001000, 2, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), 2);
        m.cpu.step();
        assertSoftly(s -> {
            s.assertThat(m.cpu.pc()).isEqualTo(8);
            s.assertThat(m.cpu.cpsr()).isEqualTo(0xd3);
            s.assertThat(m.cpu.register(14)).isEqualTo(0x03000000 + (thumb ? 2 : 4));
            s.assertThat(m.cpu.register(13)).isZero();
            s.assertThat(m.cpu.instructions()).isZero();
            s.assertThat(m.memory.busTiming().refilling()).isTrue();
        });
        retire(m);
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isEqualTo(9);
            s.assertThat(m.cpu.pc()).isEqualTo(8);
            s.assertThat(m.cpu.register(14)).isEqualTo(0x03000000 + (thumb ? 2 : 4));
        });
    }

    @Test
    void exceptionReturnRefillUsesRestoredThumbWidthAfterDataAndStatusComplete() {
        var m = branch(false, 0x03000400);
        m.cpu.setCpsr(Cpu.SUPERVISOR | 0xc0);
        m.cpu.setRegister(4, Cpu.SYSTEM | Cpu.T | 0xc0);
        m.cpu.setRegister(13, 0x03000200);
        m.memory.write32(0x03000000, 0xe16ff004); // MSR SPSR_fsxc,r4.
        m.memory.write32(0x03000004, 0xe8fd8000); // LDMIA sp!,{pc}^.
        m.memory.write32(0x03000200, 0x08000101);
        m.cpu.step();
        configureDma(m, 0x03000100, 0x03001000, 2, 0xa400);
        var start = m.clock.now();
        m.clock.schedule(() -> m.memory.dma().request(2), start + 4); // During first Thumb ROM refill read.
        do { m.cpu.step(); } while (m.cpu.instructions() == 1);
        assertSoftly(s -> {
            s.assertThat(m.clock.now() - start).isEqualTo(19); // Fetch/data/internal 3 + N 5 + DMA 6 + N 5.
            s.assertThat(m.cpu.cpsr()).isEqualTo(0xff);
            s.assertThat(m.cpu.pc()).isEqualTo(0x08000100);
            s.assertThat(m.cpu.instructions()).isEqualTo(2);
        });
        m.cpu.setCpsr(Cpu.SUPERVISOR | 0xc0);
        assertSoftly(s -> s.assertThat(m.cpu.register(13)).isEqualTo(0x03000204));
    }

    @Test
    void longIrqDmaYieldsBeforeTheFirstHandlerInstruction() {
        var m = branch(true, 0x03000400);
        enableIrq(m);
        m.cpu.setCpsr(Cpu.SYSTEM | Cpu.T);
        m.memory.interrupts().request(8);
        m.memory.write32(0x02000100, 0x01020304);
        m.memory.clockedAudio().setCapture(true);
        configureDma(m, 0x02000100, 0x03001000, 0, 0xa500);
        m.clock.schedule(() -> m.memory.dma().request(2), 2);
        m.runCycles(LcdTiming.FRAME_CYCLES);
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isBetween((long) LcdTiming.FRAME_CYCLES, LcdTiming.FRAME_CYCLES + 6L);
            s.assertThat(m.cpu.instructions()).isZero();
            s.assertThat(m.memory.busTiming().refilling()).isTrue();
            s.assertThat(m.cpu.pc()).isEqualTo(0x18);
            s.assertThat(m.cpu.register(14)).isEqualTo(0x03000004);
            s.assertThat(m.memory.clockedAudio().drain(new short[548 * 2])).isEqualTo(548);
        });
        m.runCycles(458757 - m.clock.now());
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isEqualTo(458757);
            s.assertThat(m.cpu.instructions()).isZero();
            s.assertThat(m.memory.busTiming().refilling()).isFalse();
            s.assertThat(m.cpu.pc()).isEqualTo(0x18);
            s.assertThat(m.cpu.register(14)).isEqualTo(0x03000004);
            s.assertThat(m.memory.clockedAudio().overruns()).isZero();
            s.assertThat(m.memory.clockedAudio().underruns()).isZero();
        });
        m.cpu.step();
        assertSoftly(s -> {
            s.assertThat(m.cpu.instructions()).isEqualTo(1);
            s.assertThat(m.cpu.pc()).isEqualTo(0x1c);
        });
    }

    @Test
    void releaseRequestGrantsOneRefillAccessBeforeArbitratingTheNewInterval() {
        var m = branch(false, 0x08000100);
        configureDma(m, 0x03000100, 0x03001000, 1, 0xa400);
        m.memory.write32(0x040000bc, 0x03000100);
        m.memory.write32(0x040000c0, 0x03001100);
        m.memory.write16(0x040000c4, 1);
        m.memory.write16(0x040000c6, 0x9400);
        m.clock.schedule(() -> m.memory.dma().request(2), 1);
        m.clock.schedule(() -> m.memory.dma().request(1), 5); // Final release of DMA3.
        m.cpu.step();
        m.cpu.step();
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isEqualTo(17); // Waiting ROM read 8, then second interval 4.
            s.assertThat(m.cpu.instructions()).isZero();
            s.assertThat(m.memory.read32(0x03001100)).isEqualTo(0x04030201);
        });
        retire(m);
        assertSoftly(s -> s.assertThat(m.clock.now()).isEqualTo(25)); // Second ROM read is N, not S.
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readinessAtTheEndOfTheLastRefillDoesNotExtendRetirement(boolean thumb) {
        var m = branch(thumb, 0x03000400);
        configureDma(m, 0x03000100, 0x03001000, 2, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), 3);
        m.cpu.step();
        assertSoftly(s -> {
            s.assertThat(m.clock.now()).isEqualTo(3);
            s.assertThat(m.cpu.instructions()).isEqualTo(1);
            s.assertThat(m.memory.busTiming().refilling()).isFalse();
            s.assertThat(m.memory.read32(0x03001000)).isZero();
        });
        m.cpu.step();
        assertSoftly(s -> s.assertThat(m.cpu.instructions()).isEqualTo(1));
    }

    private static Machine branch(boolean thumb, int target) {
        var image = EmulatorFixtures.blankRom();
        for (var i = 0; i < 16; i++) image[0x100 + i] = (byte) (i + 1);
        var m = new Machine(image);
        for (var i = 0; i < 16; i++) {
            m.memory.write8(0x03000100 + i, i + 1);
            m.memory.write8(0x02000100 + i, i + 1);
        }
        if (thumb) m.memory.write16(0x03000000, 0x4710);
        else m.memory.write32(0x03000000, 0xe12fff12);
        m.cpu.setPc(0x03000000);
        m.cpu.setCpsr(Cpu.SYSTEM | 0xc0 | (thumb ? Cpu.T : 0));
        m.cpu.setRegister(2, target | (thumb ? 1 : 0));
        return m;
    }

    private static void configureDma(Machine m, int source, int destination, int count, int control) {
        m.memory.write32(0x040000d4, source);
        m.memory.write32(0x040000d8, destination);
        m.memory.write16(0x040000dc, count);
        m.memory.write16(0x040000de, control);
    }

    private static void enableIrq(Machine m) {
        m.memory.setBios(new byte[0x4000]);
        m.memory.write16(0x04000200, 8);
        m.memory.write16(0x04000208, 1);
        m.cpu.setCpsr(Cpu.SYSTEM);
    }

    private static void retire(Machine m) {
        for (var step = 0; m.cpu.instructions() == 0; step++) {
            if (step > 1000) throw new IllegalStateException("Branch did not retire");
            m.cpu.step();
        }
    }
}

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
class CpuDmaDataAccessTest {
    static Stream<Arguments> loads() {
        return Stream.of(false, true).flatMap(thumb -> Stream.of(false, true).flatMap(multiple ->
                Stream.of(0x03000000, 0x02000000, 0x08000000).flatMap(source ->
                        Stream.of(1, 2).map(ready -> Arguments.of(thumb, multiple, source, ready)))));
    }

    @ParameterizedTest(name = "thumb={0}, multiple={1}, source={2}, ready={3}")
    @MethodSource("loads")
    void arbitratesBeforeEachLoadWithoutReplayingCompletedReads(boolean thumb, boolean multiple, int source, int ready) {
        // Exact retirement observations from the unmodified independent CPU:
        // tools/nba-dma-data-probe.cc. Request activation is excluded here.
        var m = program(thumb, multiple ? 0xe8b20009 : 0xe5920000, multiple ? 0xca09 : 0x6810);
        configureDma(m, source + 0x100, 0x03000200, 2, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), ready);
        m.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(m.cpu.instructions()).isZero();
            softly.assertThat(m.cpu.instructionInFlight()).isTrue();
            softly.assertThat(m.cpu.register(2)).isEqualTo(0x03000200); // No premature writeback.
            softly.assertThat(m.cpu.register(0)).isEqualTo(ready == 1 ? 0 : 0x12345678);
            softly.assertThat(m.cpu.register(3)).isZero();
        });
        retire(m);
        var ownership = source == 0x03000000 ? 6 : source == 0x02000000 ? 16 : 18;
        assertSoftly(softly -> {
            softly.assertThat(m.clock.now()).isEqualTo(ownership + (multiple ? 4 : ready == 1 ? 3 : 2));
            softly.assertThat(m.cpu.instructions()).isEqualTo(1);
            softly.assertThat(m.cpu.instructionInFlight()).isFalse();
            softly.assertThat(m.cpu.register(0)).isEqualTo(ready == 1 ? 0x04030201 : 0x12345678);
            softly.assertThat(m.cpu.register(3)).isEqualTo(multiple ? 0x08070605 : 0);
            softly.assertThat(m.cpu.register(2)).isEqualTo(0x03000200 + (multiple ? 8 : 0));
            softly.assertThat(m.cpu.pc()).isEqualTo(0x03000000 + (thumb ? 2 : 4));
        });
    }

    @ParameterizedTest
    @MethodSource("romLoads")
    void dmaBreaksTheSequentialGamePakDataBurst(boolean thumb, int source) {
        var m = program(thumb, 0xe8b20009, 0xca09);
        m.cpu.setRegister(2, 0x08000100);
        configureDma(m, source + 0x100, 0x03000200, 2, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), 2); // During the first eight-cycle ROM read.
        retire(m);
        var ownership = source == 0x03000000 ? 6 : source == 0x02000000 ? 16 : 18;
        assertSoftly(softly -> {
            // NBA: fetch 1 + N read 8 + DMA + N read 8 + internal 1.
            // Retaining the pre-DMA S sequence incorrectly saves two cycles.
            softly.assertThat(m.clock.now()).isEqualTo(ownership + 18);
            softly.assertThat(m.cpu.register(0)).isEqualTo(0x04030201);
            softly.assertThat(m.cpu.register(3)).isEqualTo(0x08070605);
            softly.assertThat(m.cpu.register(2)).isEqualTo(0x08000108);
        });
    }

    static Stream<Arguments> romLoads() {
        return Stream.of(false, true).flatMap(thumb -> Stream.of(0x03000000, 0x02000000, 0x08000000)
                .map(source -> Arguments.of(thumb, source)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resumedMultipleStoreDoesNotRepeatAnInterruptAcknowledge(boolean thumb) {
        var m = program(thumb, 0xe8a20003, 0xc203); // STMIA r2!,{r0,r1}.
        m.cpu.setRegister(2, 0x04000200);
        m.cpu.setRegister(0, 0x00080000); // W1C IF bit 3; the next store targets WAITCNT.
        m.memory.interrupts().request(8);
        configureDma(m, 0x03000100, 0x03001000, 2, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), 2);
        m.clock.schedule(() -> m.memory.interrupts().request(8), 3);
        m.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(m.cpu.instructions()).isZero();
            softly.assertThat(m.cpu.register(2)).isEqualTo(0x04000200);
        });
        retire(m);
        assertSoftly(softly -> {
            softly.assertThat(m.memory.read16(0x04000202)).isEqualTo(8); // New request survives; no replay.
            softly.assertThat(m.cpu.register(2)).isEqualTo(0x04000208);
            softly.assertThat(m.clock.now()).isEqualTo(9);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void swapArbitratesBeforeItsReadAndKeepsTheReadWritePairLocked(int ready) {
        var m = program(false, 0xe1023090, 0); // SWP r3,r0,[r2].
        m.cpu.setRegister(0, 0x87654321);
        configureDma(m, 0x03000200, 0x03001000, 1, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), ready);
        retire(m);
        assertSoftly(softly -> {
            softly.assertThat(m.cpu.register(3)).isEqualTo(0x12345678);
            softly.assertThat(m.memory.read32(0x03000200)).isEqualTo(0x87654321);
            softly.assertThat(m.memory.read32(0x03001000)).isEqualTo(ready == 1 ? 0x12345678 : 0x87654321);
            softly.assertThat(m.cpu.instructions()).isEqualTo(1);
        });
    }

    @Test
    void popResumesBetweenStackReadsAndWritesTheThumbPcAfterTheFinalRead() {
        var m = program(true, 0, 0xbd01); // POP {r0,pc}.
        m.cpu.setRegister(13, 0x03000200);
        m.memory.write32(0x03000104, 0x03000401);
        configureDma(m, 0x03000100, 0x03000200, 2, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), 2);
        m.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(m.cpu.register(0)).isEqualTo(0x12345678);
            softly.assertThat(m.cpu.register(13)).isEqualTo(0x03000200);
            softly.assertThat(m.cpu.pc()).isEqualTo(0x03000002);
        });
        retire(m);
        assertSoftly(softly -> {
            softly.assertThat(m.cpu.register(0)).isEqualTo(0x12345678);
            softly.assertThat(m.cpu.register(13)).isEqualTo(0x03000208);
            softly.assertThat(m.cpu.pc()).isEqualTo(0x03000400);
            softly.assertThat(m.cpu.isThumb()).isTrue();
        });
    }

    @Test
    void pushResumesWithoutRepeatingAnEarlierStoreOrDecrementingTheStackAgain() {
        var m = program(true, 0, 0xb403); // PUSH {r0,r1}.
        m.cpu.setRegister(0, 0x11111111);
        m.cpu.setRegister(1, 0x22222222);
        m.cpu.setRegister(13, 0x03000208);
        configureDma(m, 0x03000100, 0x03000200, 2, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), 2);
        retire(m);
        assertSoftly(softly -> {
            softly.assertThat(m.cpu.register(13)).isEqualTo(0x03000200);
            softly.assertThat(m.memory.read32(0x03000200)).isEqualTo(0x04030201); // DMA overwrote first store.
            softly.assertThat(m.memory.read32(0x03000204)).isEqualTo(0x22222222); // Second store follows DMA.
        });
    }

    @Test
    void requestDuringReleaseGrantsOneWaitingDataAccessBeforeTheNextInterval() {
        var m = program(false, 0xe8b20009, 0); // LDMIA r2!,{r0,r3}.
        configureDma(m, 0x03000100, 0x03000200, 1, 0xa400);
        m.memory.write32(0x03000120, 0xaabbccdd);
        m.memory.write32(0x040000bc, 0x03000120); // DMA1, independently triggered on VBlank.
        m.memory.write32(0x040000c0, 0x03000204);
        m.memory.write16(0x040000c4, 1);
        m.memory.write16(0x040000c6, 0x9400);
        m.clock.schedule(() -> m.memory.dma().request(2), 1);
        m.clock.schedule(() -> m.memory.dma().request(1), 5); // Release of first interval.
        m.cpu.step();
        m.cpu.step(); // The waiting first load runs before servicing the new interval.
        assertSoftly(softly -> {
            softly.assertThat(m.cpu.register(0)).isEqualTo(0x04030201);
            softly.assertThat(m.cpu.register(3)).isZero();
            softly.assertThat(m.cpu.instructions()).isZero();
        });
        retire(m);
        assertSoftly(softly -> {
            softly.assertThat(m.cpu.register(3)).isEqualTo(0xaabbccdd);
            softly.assertThat(m.cpu.register(2)).isEqualTo(0x03000208);
            softly.assertThat(m.clock.now()).isEqualTo(12);
        });
    }

    @Test
    void exceptionReturnRestoresStatusAfterTheFinalLoadAndWritesBackTheOriginalStackBank() {
        var m = program(false, 0xe16ff004, 0); // MSR SPSR_fsxc,r4.
        m.cpu.setCpsr(Cpu.SUPERVISOR | 0xc0);
        m.cpu.setRegister(4, Cpu.SYSTEM | Cpu.T | 0xc0);
        m.cpu.setRegister(13, 0x03000200);
        m.memory.write32(0x03000004, 0xe8fd8001); // LDMIA sp!,{r0,pc}^.
        m.cpu.step();
        m.memory.write32(0x03000104, 0x03000401);
        configureDma(m, 0x03000100, 0x03000200, 2, 0xa400);
        m.clock.schedule(() -> m.memory.dma().request(2), m.clock.now() + 2);
        m.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(m.cpu.cpsr() & 0x3f).isEqualTo(Cpu.SUPERVISOR);
            softly.assertThat(m.cpu.register(13)).isEqualTo(0x03000200);
            softly.assertThat(m.cpu.register(0)).isEqualTo(0x12345678);
            softly.assertThat(m.cpu.instructions()).isEqualTo(1);
        });
        while (m.cpu.instructions() == 1) m.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(m.cpu.cpsr() & 0x3f).isEqualTo(Cpu.SYSTEM | Cpu.T);
            softly.assertThat(m.cpu.pc()).isEqualTo(0x03000400); // Align using restored Thumb state.
            softly.assertThat(m.cpu.register(0)).isEqualTo(0x12345678);
        });
        m.cpu.setCpsr(Cpu.SUPERVISOR | 0xc0);
        assertSoftly(softly -> softly.assertThat(m.cpu.register(13)).isEqualTo(0x03000208));
    }

    @Test
    void pendingDataAccessYieldsAtFrameBudgetsAndUsesTheAlreadyDecodedOpcode() {
        var m = program(false, 0xe5920000, 0);
        m.memory.write32(0x02000100, 0x01020304);
        m.memory.clockedAudio().setCapture(true);
        configureDma(m, 0x02000100, 0x03001000, 0, 0xa500); // 65536 words, fixed source, wraps IWRAM.
        m.clock.schedule(() -> m.memory.dma().request(2), 1);
        m.runCycles(LcdTiming.FRAME_CYCLES);
        assertSoftly(softly -> {
            softly.assertThat(m.clock.now()).isBetween((long) LcdTiming.FRAME_CYCLES, LcdTiming.FRAME_CYCLES + 6L);
            softly.assertThat(m.cpu.instructions()).isZero();
            softly.assertThat(m.cpu.instructionInFlight()).isTrue();
            softly.assertThat(m.cpu.register(0)).isZero(); // LDR has not accessed data yet.
            softly.assertThat(m.memory.raster().completedFrames()).isEqualTo(1);
            softly.assertThat(m.memory.clockedAudio().availableFrames()).isEqualTo(548);
        });
        m.memory.clockedAudio().drain(new short[548 * 2]);
        m.runCycles(458757 - m.clock.now());
        assertSoftly(softly -> {
            softly.assertThat(m.clock.now()).isEqualTo(458757);
            softly.assertThat(m.cpu.instructions()).isEqualTo(1);
            softly.assertThat(m.cpu.instructionInFlight()).isFalse();
            softly.assertThat(m.cpu.register(0)).isEqualTo(0x01020304);
            softly.assertThat(m.cpu.pc()).isEqualTo(0x03000004);
            softly.assertThat(m.memory.clockedAudio().producedFrames()).isEqualTo(896);
            softly.assertThat(m.memory.clockedAudio().overruns()).isZero();
            softly.assertThat(m.memory.clockedAudio().underruns()).isZero();
        });
    }

    private static Machine program(boolean thumb, int armOpcode, int thumbOpcode) {
        var image = EmulatorFixtures.blankRom();
        for (var i = 0; i < 16; i++) image[0x100 + i] = (byte) (i + 1);
        var m = new Machine(image);
        for (var i = 0; i < 16; i++) {
            m.memory.write8(0x03000100 + i, i + 1);
            m.memory.write8(0x02000100 + i, i + 1);
        }
        if (thumb) m.memory.write16(0x03000000, thumbOpcode); else m.memory.write32(0x03000000, armOpcode);
        m.cpu.setCpsr(Cpu.SYSTEM | 0xc0 | (thumb ? Cpu.T : 0));
        m.cpu.setPc(0x03000000);
        m.cpu.setRegister(2, 0x03000200);
        m.memory.write32(0x03000200, 0x12345678);
        m.memory.write32(0x03000204, 0x12345679);
        return m;
    }

    private static void configureDma(Machine m, int source, int destination, int count, int control) {
        m.memory.write32(0x040000d4, source);
        m.memory.write32(0x040000d8, destination);
        m.memory.write16(0x040000dc, count);
        m.memory.write16(0x040000de, control);
    }

    private static void retire(Machine m) {
        for (var steps = 0; m.cpu.instructions() == 0; steps++) {
            if (steps > 1000) throw new IllegalStateException("CPU did not retire");
            m.cpu.step();
        }
    }
}

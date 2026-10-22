package berryboy.bus;

import berryboy.EmulationException;
import berryboy.EmulatorFixtures;
import berryboy.Machine;

import static berryboy.EmulatorFixtures.arm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class DmaControllerTest {
    private static final int DMA = 0x040000b0;

    @Test
    void immediateWordTransferOwnsBusAndRaisesCompletionIrq() {
        var m = arm(0xe1a00000);
        m.memory.write32(0x02000000, 0x12345678);
        m.memory.write32(0x02000004, 0x76543210);
        configure(m, 0, 0x02000000, 0x03000000, 2, 0xc400);
        m.runCycles(16);
        assertThat(m.memory.read32(0x03000000)).isEqualTo(0x12345678);
        assertThat(m.memory.read32(0x03000004)).isEqualTo(0x76543210);
        assertThat(m.clock.now()).isEqualTo(16); // Startup 2 + 2*(EWRAM 6 + IWRAM 1).
        assertThat(m.cpu.instructions()).isZero();
        assertThat(m.memory.read16(0x04000202)).isEqualTo(0x100);
        assertThat(m.memory.read16(DMA + 10) & 0x8000).isZero();
    }

    @Test
    void hblankRepeatsReloadDestinationAndKeepsSourceProgress() {
        var m = arm(0xe1a00000);
        m.memory.write16(0x02000000, 0x1111);
        m.memory.write16(0x02000002, 0x2222);
        configure(m, 1, 0x02000000, 0x03000000, 1, 0xa260);
        m.clock.advanceTo(959);
        assertThat(m.memory.dma().runPending()).isFalse();
        m.clock.advanceTo(960);
        m.cpu.step();
        assertThat(m.memory.read16(0x03000000)).isEqualTo(0x1111);
        m.clock.advanceTo(1232 + 960);
        m.cpu.step();
        assertThat(m.memory.read16(0x03000000)).isEqualTo(0x2222);
        assertThat(m.memory.read16(DMA + 12 + 10) & 0x8000).isEqualTo(0x8000);
    }

    @Test
    void lowerChannelWinsPriorityAndAddressModesAreApplied() {
        var m = arm(0xe1a00000);
        m.memory.write16(0x02000000, 0x1234);
        m.memory.write16(0x02000002, 0x5678);
        // Channel 0 completes before channel 1 reads its destination.
        configure(m, 1, 0x03000000, 0x03000010, 2, 0x8000);
        configure(m, 0, 0x02000002, 0x03000000, 2, 0x8080); // Source decrement.
        m.runCycles(16);
        assertThat(m.memory.read16(0x03000010)).isEqualTo(0x5678);
        assertThat(m.memory.read16(0x03000012)).isEqualTo(0x1234);
    }

    @Test
    void vblankWaitsForVisibleRegionToFinishAndDisabledChannelDoesNotRun() {
        var m = arm(0xe1a00000);
        m.memory.write16(0x02000000, 42);
        configure(m, 3, 0x02000000, 0x03000000, 1, 0x9000);
        m.clock.advanceTo(160L * 1232 - 1);
        assertThat(m.memory.dma().runPending()).isFalse();
        m.clock.advance(1);
        m.cpu.step();
        assertThat(m.memory.read16(0x03000000)).isEqualTo(42);
        configure(m, 0, 0x02000000, 0x03000010, 1, 0x8000);
        m.memory.write16(DMA + 10, 0);
        assertThat(m.memory.dma().runPending()).isFalse();
        assertThat(m.memory.read16(0x03000010)).isZero();
    }

    @Test
    void rejectsReservedSourceAndUnimplementedSpecialRequests() {
        var m = arm(0xe1a00000);
        assertThatThrownBy(() -> configure(m, 0, 0x02000000, 0x03000000, 1, 0x8180))
                .isInstanceOf(EmulationException.class).hasMessageContaining("source");
        assertThatThrownBy(() -> configure(m, 3, 0x02000000, 0x03000000, 1, 0xb000))
                .isInstanceOf(EmulationException.class).hasMessageContaining("Special");
    }

    @Test
    void longDmaYieldsAtFrameBudgetsWithoutLettingCpuExecute() {
        var m = arm(0xe3a0002a, 0xeafffffe); // MOV r0,#42 must wait for DMA completion.
        m.memory.write32(0x02000000, 0x12345678);
        configure(m, 3, 0x02000000, 0x03000000, 0, 0x8500); // 65536 words, fixed source.
        var frameCycles = berryboy.video.LcdTiming.FRAME_CYCLES;
        m.runCycles(frameCycles);
        assertSoftly(softly -> {
            softly.assertThat(m.clock.now()).isBetween((long) frameCycles, frameCycles + 6L);
            softly.assertThat(m.memory.raster().completedFrames()).isEqualTo(1);
            softly.assertThat(m.cpu.instructions()).isZero();
            softly.assertThat(m.cpu.register(0)).isZero();
            softly.assertThat(m.memory.read16(DMA + 3 * 12 + 10) & 0x8000).isEqualTo(0x8000);
        });
        m.runCycles(458754 - m.clock.now()); // Startup 2 + 65536*(EWRAM 6 + IWRAM 1).
        assertSoftly(softly -> {
            softly.assertThat(m.clock.now()).isEqualTo(458754);
            softly.assertThat(m.memory.raster().completedFrames()).isEqualTo(1);
            softly.assertThat(m.cpu.instructions()).isZero();
            softly.assertThat(m.memory.read32(0x03000000)).isEqualTo(0x12345678);
            softly.assertThat(m.memory.read16(DMA + 3 * 12 + 10) & 0x8000).isZero();
        });
        m.cpu.step();
        assertThat(m.cpu.register(0)).isEqualTo(42);
        m.runCycles(2L * frameCycles - m.clock.now());
        assertThat(m.memory.raster().completedFrames()).isEqualTo(2);
    }

    @Test
    void higherPriorityRequestPreemptsBetweenUnitsAndLowerChannelResumes() {
        var m = arm(0xe1a00000);
        m.memory.write16(0x02000000, 0x1111);
        m.memory.write16(0x02000002, 0x2222);
        configure(m, 3, 0x02000000, 0x03000000, 2, 0x8000);
        m.cpu.step();
        configure(m, 0, 0x03000000, 0x03000010, 1, 0x8000);
        m.cpu.step();
        assertThat(m.memory.read16(0x03000010)).isEqualTo(0x1111);
        assertThat(m.memory.read16(0x03000002)).isZero();
        m.cpu.step();
        assertThat(m.memory.read16(0x03000002)).isEqualTo(0x2222);
        assertThat(m.cpu.instructions()).isZero();
    }

    private void configure(Machine m, int channel, int source, int destination, int count, int control) {
        int base = DMA + channel * 12;
        m.memory.write32(base, source);
        m.memory.write32(base + 4, destination);
        m.memory.write16(base + 8, count);
        m.memory.write16(base + 10, control);
    }
}

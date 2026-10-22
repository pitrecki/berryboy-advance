package berryboy.video;

import berryboy.EmulatorFixtures;

import static berryboy.EmulatorFixtures.bitmap;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class RasterPpuTest {
    @Test
    void midScanlinePaletteWritesOnlyAffectLaterPixels() {
        var m = bitmap(0x0404);
        for (int i = 0; i < 240 * 160; i += 2) m.memory.write16(0x06000000 + i, 0x0101);
        m.memory.write16(0x05000002, 31);
        m.clock.advanceTo(480); // First 120 pixels sampled red.
        m.memory.write16(0x05000002, 0x3e0);
        m.clock.advanceTo(160L * 1232);
        var pixels = m.memory.raster().completedFrame();
        assertThat(pixels[0]).isEqualTo(0xff0000);
        assertThat(pixels[119]).isEqualTo(0xff0000);
        assertThat(pixels[120]).isEqualTo(0x00ff00);
        assertThat(pixels[240]).isEqualTo(0x00ff00);
        assertThat(m.memory.raster().completedFrames()).isEqualTo(1);
    }

    @Test
    void affineReferenceWritesReloadInternalCoordinatesForFollowingPixels() {
        var m = bitmap(0x0403);
        m.memory.write16(0x06000000, 31);
        m.memory.write16(0x06000002, 0x3e0);
        m.clock.advanceTo(4);
        m.memory.write32(0x04000028, -256); // Pixel 1 now samples source pixel 0.
        m.clock.advanceTo(160L * 1232);
        assertThat(m.memory.raster().completedFrame()[0]).isEqualTo(0xff0000);
        assertThat(m.memory.raster().completedFrame()[1]).isEqualTo(0xff0000);
    }

    @Test
    void completedFramesStayStableUntilNextVisibleFrameCompletes() {
        var m = bitmap(0x80);
        m.clock.advanceTo(160L * 1232);
        assertThat(m.memory.raster().completedFrame()).containsOnly(0xffffff);
        m.memory.write16(0x04000000, 0);
        m.clock.advanceTo(LcdTiming.FRAME_CYCLES + 100);
        assertThat(m.memory.raster().completedFrame()).containsOnly(0xffffff);
        m.clock.advanceTo(LcdTiming.FRAME_CYCLES + 160L * 1232);
        assertThat(m.memory.raster().completedFrame()).containsOnly(0);
        assertThat(m.memory.raster().completedFrames()).isEqualTo(2);
    }

    @Test
    void midlineScrollWriteInvalidatesCachedDisplayRegisters() {
        var m = bitmap(0x0100);
        m.memory.write16(0x04000008, 0x1f00);
        m.memory.write16(0x06000000, 0x0321);
        m.memory.write16(0x05000002, 31);
        m.memory.write16(0x05000004, 0x3e0);
        m.memory.write16(0x05000006, 0x7c00);
        m.clock.advanceTo(4);
        m.memory.write16(0x04000010, 1);
        m.clock.advanceTo(160L * 1232);
        assertThat(m.memory.raster().completedFrame()[0]).isEqualTo(0xff0000);
        assertThat(m.memory.raster().completedFrame()[1]).isEqualTo(0x0000ff);
    }
}

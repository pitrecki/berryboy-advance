package berryboy.video;

import berryboy.EmulatorFixtures;
import berryboy.Machine;

import static berryboy.EmulatorFixtures.bitmap;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class SoftwarePpuTest {
    @Test
    void textTilesSupportPalettesFlipsScrollAndScreenBlocks() {
        var m = text(0x100);
        m.memory.write16(0x06000000, 0x0021); // Red then green.
        m.memory.write16(0x0600f800, 0);
        assertThat(line(m, 0)).startsWith(0xff0000, 0x00ff00, 0);
        m.memory.write16(0x0600f800, 0x0400);
        assertThat(line(m, 0)[6]).isEqualTo(0x00ff00);
        m.memory.write16(0x04000008, 0x5f00); // 512x256, screen base 31 (wrap in VRAM).
        m.memory.write16(0x04000010, 256);
        m.memory.write16(0x06010000, 1); // Second screen block.
        m.memory.write16(0x06000020, 0x3333);
        assertThat(line(m, 0)[0]).isEqualTo(0x0000ff);
    }

    @Test
    void affineBackgroundTransformsWrapAndTreatZeroAsTransparent() {
        var m = text(0x0402);
        m.memory.write16(0x0400000c, 0x1f00);
        m.memory.write16(0x0600f800, 0x0101); // Two affine map bytes.
        m.memory.write16(0x06000040, 0x0201); // Tile 1: red, green.
        assertThat(line(m, 0)).startsWith(0xff0000, 0x00ff00, 0);
        m.memory.write32(0x04000028, 128 << 8);
        assertThat(line(m, 0)[0]).isZero();
        m.memory.write16(0x0400000c, 0x3f00); // Overflow wraps 128x128 BG.
        assertThat(line(m, 0)[0]).isEqualTo(0xff0000);
    }

    @Test
    void spritePriorityTransparencyAndFlipsRespectBackgroundOrder() {
        var m = text(0x1140);
        disableObjects(m);
        m.memory.write16(0x06000000, 0x1111);
        m.memory.write16(0x05000202, 0x7c00);
        m.memory.write16(0x06010000, 0x0011);
        m.memory.write16(0x07000000, 0);
        m.memory.write16(0x07000002, 0);
        m.memory.write16(0x07000004, 0);
        assertThat(line(m, 0)).startsWith(0x0000ff, 0x0000ff, 0xff0000);
        m.memory.write16(0x07000004, 0x400); // OBJ priority 1 behind BG0 priority 0.
        assertThat(line(m, 0)[0]).isEqualTo(0xff0000);
        m.memory.write16(0x07000004, 0);
        m.memory.write16(0x07000002, 0x1000);
        assertThat(line(m, 0)[6]).isEqualTo(0x0000ff);
    }

    @Test
    void affineObjectsUseOamMatricesAndDoubleSizeBounds() {
        var m = text(0x1040);
        disableObjects(m);
        m.memory.write16(0x05000202, 0x7c00);
        m.memory.write16(0x06010000, 0x1111);
        m.memory.write16(0x07000000, 0x0300); // Affine, double-size.
        m.memory.write16(0x07000002, 0);
        m.memory.write16(0x07000004, 0);
        m.memory.write16(0x07000006, 0x100);
        m.memory.write16(0x0700000e, 0);
        m.memory.write16(0x07000016, 0);
        m.memory.write16(0x0700001e, 0x100);
        assertThat(line(m, 4)[3]).isZero();
        assertThat(line(m, 4)[4]).isEqualTo(0x0000ff);
        assertThat(line(m, 4)[8]).isZero();
    }

    @Test
    void windowsGateLayersAndColorEffectsAndSupportWrappedBounds() {
        var m = text(0x2100);
        m.memory.write16(0x06000000, 0x1111);
        m.memory.write16(0x04000040, 0x0204); // x in [2,4).
        m.memory.write16(0x04000044, 0x00a0);
        m.memory.write16(0x04000048, 1);
        m.memory.write16(0x0400004a, 0);
        assertThat(line(m, 0)).startsWith(0, 0, 0xff0000, 0xff0000, 0);
        m.memory.write16(0x04000040, 0x0402); // Wrapped: x >=4 or x<2.
        assertThat(line(m, 0)).startsWith(0xff0000, 0xff0000, 0, 0);
    }

    @Test
    void blendingClampsCoefficientsAndBrightnessUsesHardwareRounding() {
        var m = text(0x100);
        m.memory.write16(0x06000000, 0x1111);
        m.memory.write16(0x05000000, 0x7c00);
        m.memory.write16(0x04000050, 0x2041); // BG0 first, backdrop second, alpha.
        m.memory.write16(0x04000052, 0x0808);
        assertThat(line(m, 0)[0]).isEqualTo(0x7b007b);
        m.memory.write16(0x04000052, 0x1f1f);
        assertThat(line(m, 0)[0]).isEqualTo(0xff00ff);
        m.memory.write16(0x04000050, 0xc1); // Darken BG0.
        m.memory.write16(0x04000054, 8);
        assertThat(line(m, 0)[0]).isEqualTo(0x840000);
        m.memory.write16(0x04000050, 0x81); // Brighten BG0.
        assertThat(line(m, 0)[0]).isEqualTo(0xff7b7b);
    }

    @Test
    void darkeningRoundsNativeFiveBitChannelsBeforeRgbExpansion() {
        // GBATEK BLDY: I = I1 - ((I1 * EVY) >> 4), independently per 5-bit channel.
        // RGB888 reference renderers can produce different shades from identical state.
        var m = text(0);
        m.memory.write16(0x05000000, 0x7e51); // Native RGB (17,18,31).
        m.memory.write16(0x04000050, 0x00ff); // Darken all first targets, including backdrop.
        m.memory.write16(0x04000054, 7);
        assertThat(line(m, 0)).containsOnly(0x525a94); // Native (10,11,18), then bit replication.
        m.clock.advanceTo(160L * 1232);
        assertThat(m.memory.raster().completedFrame()).containsOnly(0x525a94);
    }

    @Test
    void mosaicRepeatsBackgroundPixelsAndBitmapAffineTransformsRemainSupported() {
        var m = text(0x100);
        m.memory.write16(0x06000000, 0x0021);
        m.memory.write16(0x04000008, 0x1f40);
        m.memory.write16(0x0400004c, 1);
        assertThat(line(m, 0)).startsWith(0xff0000, 0xff0000, 0, 0);
        var b = bitmap(0x0403);
        b.memory.write16(0x06000000, 31);
        b.memory.write32(0x04000028, -256);
        assertThat(line(b, 0)).startsWith(0, 0xff0000);
    }

    @Test
    void offscreenObjectsConsumeLineBudgetAndHblankFreeReducesIt() {
        var m = text(0x1000);
        disableObjects(m);
        m.memory.write16(0x05000202, 0x7c00);
        m.memory.write16(0x06010000, 0x1111);
        for (int i = 0; i < 15; i++) {
            m.memory.write16(0x07000000 + i * 8, 0);
            m.memory.write16(0x07000002 + i * 8, 0xc100); // Invisible 64x64 OBJ.
        }
        m.memory.write16(0x07000000 + 15 * 8, 0);
        m.memory.write16(0x07000002 + 15 * 8, 0);
        assertThat(line(m, 0)[0]).isEqualTo(0x0000ff);
        m.memory.write16(0x04000000, 0x1020);
        assertThat(line(m, 0)[0]).isZero();
    }

    @Test
    void bitmapOverflowStaysTransparentEvenWhenAffineWrapBitIsSet() {
        var m = bitmap(0x0405);
        m.memory.write16(0x0400000c, 0x2000);
        m.memory.write16(0x06000000, 31);
        m.memory.write32(0x04000028, 160 << 8);
        assertThat(line(m, 0)[0]).isZero();
    }

    private Machine text(int display) {
        var m = bitmap(display);
        m.memory.write16(0x04000008, 0x1f00);
        m.memory.write16(0x05000002, 31);
        m.memory.write16(0x05000004, 0x3e0);
        m.memory.write16(0x05000006, 0x7c00);
        return m;
    }

    private int[] line(Machine m, int y) {
        var pixels = new int[240];
        new SoftwarePpu(m.memory).renderLine(y, pixels, 0);
        return pixels;
    }

    private void disableObjects(Machine m) {
        for (int i = 0; i < 128; i++) m.memory.write16(0x07000000 + i * 8, 0x200);
    }
}

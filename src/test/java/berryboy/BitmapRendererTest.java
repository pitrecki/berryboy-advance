package berryboy;

import static berryboy.EmulatorFixtures.bitmap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
@DisplayName("Bitmap renderer")
class BitmapRendererTest {
    @Test
    void resolvesModeFourPaletteOnSecondPage() {
        var machine = bitmap(0x0414);
        machine.memory.write16(Memory.VRAM_BASE + 0xa000, 0x0101);
        machine.memory.write16(Memory.PALETTE_BASE + 2, 0x03e0);

        var pixels = machine.video.render();

        assertThat(pixels[0]).isEqualTo(0x00ff00);
    }

    @Test
    void rendersModeFiveSecondPageAndBackdropOutsideBitmap() {
        var machine = bitmap(0x0415);
        machine.memory.write16(Memory.VRAM_BASE + 0xa000, 0x7c00);

        var pixels = machine.video.render();

        assertSoftly(softly -> {
            softly.assertThat(pixels[0]).isEqualTo(0x0000ff);
            softly.assertThat(pixels[160]).as("mode 5 bitmap ends at x=160").isEqualTo(0);
        });
    }

    @Test
    void fillsForcedBlankWithWhite() {
        var machine = bitmap(0x0080);

        var pixels = machine.video.render();

        assertThat(pixels[100]).isEqualTo(0xffffff);
    }
}

package berryboy;

import static berryboy.EmulatorFixtures.bitmap;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

        assertEquals(0x00ff00, pixels[0]);
    }

    @Test
    void rendersModeFiveSecondPageAndBackdropOutsideBitmap() {
        var machine = bitmap(0x0415);
        machine.memory.write16(Memory.VRAM_BASE + 0xa000, 0x7c00);

        var pixels = machine.video.render();

        assertAll(
                () -> assertEquals(0x0000ff, pixels[0]),
                () -> assertEquals(0, pixels[160], "mode 5 bitmap ends at x=160"));
    }

    @Test
    void fillsForcedBlankWithWhite() {
        var machine = bitmap(0x0080);

        var pixels = machine.video.render();

        assertEquals(0xffffff, pixels[100]);
    }
}

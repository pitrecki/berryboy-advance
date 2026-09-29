package berryboy;

/** Snapshot of a single BG2 bitmap. No scanline timing, OBJ, windows or blending. */
public final class BitmapRenderer {
    public static final int WIDTH = 240;
    public static final int HEIGHT = 160;

    private static final int SECOND_FRAME = 0x0010;
    private static final int FORCED_BLANK = 0x0080;
    private static final int BACKGROUND_2_ENABLED = 0x0400;
    private static final int UNSUPPORTED_DISPLAY_FLAGS = 0xfb00;
    private static final int UNSUPPORTED_BACKGROUND_FLAGS = 0x2040;

    private final Memory memory;
    private final int[] pixels = new int[WIDTH * HEIGHT];

    public BitmapRenderer(Memory memory) {
        this.memory = memory;
    }

    /** Returned RGB888 buffer is owned and reused by this renderer. */
    public int[] render() {
        var control = memory.read16(Memory.DISPLAY_CONTROL);
        var backdrop = rgb888(memory.read16(Memory.PALETTE_BASE));

        if ((control & FORCED_BLANK) != 0) {
            fill(0xffffff);
        } else if ((control & BACKGROUND_2_ENABLED) == 0) {
            fill(backdrop);
        } else {
            var mode = control & 7;
            validateDisplay(control, mode);
            var frameOffset = mode != 3 && (control & SECOND_FRAME) != 0 ? 0xa000 : 0;
            renderBitmap(mode, frameOffset, backdrop);
        }
        return pixels;
    }

    private void validateDisplay(int control, int mode) {
        if (mode < 3 || mode > 5) {
            throw new EmulationException("Only bitmap modes 3, 4, 5 can be rendered");
        }
        var backgroundControl = memory.read16(Memory.BACKGROUND_2_CONTROL);
        if ((control & UNSUPPORTED_DISPLAY_FLAGS) != 0
                || (backgroundControl & UNSUPPORTED_BACKGROUND_FLAGS) != 0) {
            throw new EmulationException("OBJ, other BGs, windows, mosaic and bitmap wrap are unsupported");
        }
    }

    private void renderBitmap(int mode, int frameOffset, int backdrop) {
        var sourceWidth = mode == 5 ? 160 : WIDTH;
        var sourceHeight = mode == 5 ? 128 : HEIGHT;
        var transform = Memory.BACKGROUND_2_TRANSFORM;

        // PA/PB/PC/PD are signed 8.8 steps; reference coordinates are signed 20.8.
        var stepXPerColumn = (short) memory.read16(transform);
        var stepXPerRow = (short) memory.read16(transform + 2);
        var stepYPerColumn = (short) memory.read16(transform + 4);
        var stepYPerRow = (short) memory.read16(transform + 6);
        var referenceX = memory.read32(transform + 8) << 4 >> 4;
        var referenceY = memory.read32(transform + 12) << 4 >> 4;
        var outputIndex = 0;

        for (var y = 0; y < HEIGHT; y++) {
            var transformedX = referenceX + stepXPerRow * y;
            var transformedY = referenceY + stepYPerRow * y;

            for (var x = 0; x < WIDTH; x++) {
                var sourceX = transformedX >> 8;
                var sourceY = transformedY >> 8;
                var outsideBitmap = sourceX < 0 || sourceY < 0
                        || sourceX >= sourceWidth || sourceY >= sourceHeight;

                pixels[outputIndex++] = outsideBitmap
                        ? backdrop
                        : readPixel(mode, frameOffset, sourceY * sourceWidth + sourceX);
                transformedX += stepXPerColumn;
                transformedY += stepYPerColumn;
            }
        }
    }

    private int readPixel(int mode, int frameOffset, int pixelIndex) {
        var frameAddress = Memory.VRAM_BASE + frameOffset;
        var color = mode == 4
                ? memory.read16(Memory.PALETTE_BASE + 2 * memory.read8(frameAddress + pixelIndex))
                : memory.read16(frameAddress + pixelIndex * 2);
        return rgb888(color);
    }

    private void fill(int color) {
        for (var index = 0; index < pixels.length; index++) {
            pixels[index] = color;
        }
    }

    private static int rgb888(int color) {
        var red = expandChannel(color & 31);
        var green = expandChannel((color >>> 5) & 31);
        var blue = expandChannel((color >>> 10) & 31);
        return (red << 16) | (green << 8) | blue;
    }

    private static int expandChannel(int channel) {
        return (channel << 3) | (channel >>> 2);
    }
}

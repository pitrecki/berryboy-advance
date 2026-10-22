package berryboy.video;

/** Snapshot adapter for the reference software PPU; returned RGB888 pixels are reused. */
public final class BitmapRenderer {
    public static final int WIDTH = 240;
    public static final int HEIGHT = 160;
    private final SoftwarePpu ppu;
    private final int[] pixels = new int[WIDTH * HEIGHT];

    public BitmapRenderer(VideoBus memory) { ppu = new SoftwarePpu(memory); }

    public int[] render() {
        for (var y = 0; y < HEIGHT; y++) ppu.renderLine(y, pixels, y * WIDTH);
        return pixels;
    }
}

package berryboy.video;

import berryboy.timing.CycleEvent;

/** Cycle-driven reference sampling, one pixel per four guest clocks during drawing. */
public final class RasterPpu implements CycleEvent {
    private final VideoBus memory;
    private final SoftwarePpu ppu;
    private int[] drawing = new int[240 * 160];
    private int[] completed = new int[240 * 160];
    private int x;
    private int y;
    private long frames;

    public RasterPpu(VideoBus memory) {
        this.memory = memory;
        ppu = new SoftwarePpu(memory);
        memory.clock().schedule(this, memory.clock().now() + 4);
    }

    public long completedFrames() { return frames; }

    /** Reused complete-frame buffer. Consumers must copy before the next frame. */
    public int[] completedFrame() { return completed; }

    public void affineWrite(int address) { ppu.affineWrite(address); }

    public void fire() {
        if (x == 0 && y == 0) ppu.reloadAffine();
        ppu.renderPixel(x, y, drawing);
        x++;
        var delay = 4;
        if (x == 240) {
            ppu.finishLine();
            x = 0;
            y++;
            delay += LcdTiming.LINE_CYCLES - LcdTiming.DRAW_CYCLES;
            if (y == 160) {
                var previous = completed;
                completed = drawing;
                drawing = previous;
                frames++;
                y = 0;
                delay += (LcdTiming.LINES - LcdTiming.VISIBLE_LINES) * LcdTiming.LINE_CYCLES;
            }
        }
        memory.clock().schedule(this, memory.clock().now() + delay);
    }
}

package berryboy.video;

import berryboy.timing.CycleEvent;
import berryboy.timing.CycleScheduler;
import berryboy.timing.InterruptController;

/** LCD status clock: 960 drawing cycles, 272 HBlank cycles, 228 lines/frame. */
public final class LcdTiming implements CycleEvent {
    public static final int DRAW_CYCLES = 960;
    public static final int LINE_CYCLES = 1232;
    public static final int LINES = 228;
    public static final int VISIBLE_LINES = 160;
    public static final int FRAME_CYCLES = LINE_CYCLES * LINES;
    private final CycleScheduler clock;
    private final InterruptController interrupts;
    private int control;
    private int line;
    private boolean hblank;
    private long frames;
    private LcdListener listener;

    public LcdTiming(CycleScheduler clock, InterruptController interrupts) {
        this.clock = clock;
        this.interrupts = interrupts;
        clock.schedule(this, clock.now() + DRAW_CYCLES);
    }

    public int line() { return line; }

    public void attachListener(LcdListener listener) { this.listener = listener; }

    public long frames() { return frames; }

    public int status() {
        return control | (line >= VISIBLE_LINES && line < LINES - 1 ? 1 : 0)
                | (hblank ? 2 : 0) | (line == (control >>> 8) ? 4 : 0);
    }

    public void fire() {
        if (!hblank) {
            hblank = true;
            if ((control & 0x10) != 0) interrupts.request(2);
            if (listener != null && line < VISIBLE_LINES) listener.hblank(line);
            clock.schedule(this, clock.now() + LINE_CYCLES - DRAW_CYCLES);
        } else {
            hblank = false;
            line++;
            if (line == LINES) {
                line = 0;
                frames++;
            }
            if (line == VISIBLE_LINES && (control & 8) != 0) interrupts.request(1);
            if (listener != null && line == VISIBLE_LINES) listener.vblank();
            if (line == (control >>> 8) && (control & 0x20) != 0) interrupts.request(4);
            clock.schedule(this, clock.now() + DRAW_CYCLES);
        }
    }

    public int read8(int address) {
        var value = (address & ~1) == 0x04000004 ? status() : line;
        return (value >>> ((address & 1) * 8)) & 255;
    }

    public void write8(int address, int value) {
        if ((address & ~1) != 0x04000004) return; // VCOUNT is read-only.
        var matched = line == (control >>> 8);
        var shift = (address & 1) * 8;
        control = ((control & ~(255 << shift)) | ((value & 255) << shift)) & 0xff38;
        if (!matched && line == (control >>> 8) && (control & 0x20) != 0) interrupts.request(4);
    }
}

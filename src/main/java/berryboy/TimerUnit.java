package berryboy;

/** Four cycle-driven 16-bit timers, including reload, prescale and cascading. */
public final class TimerUnit {
    private static final int[] PERIODS = {1, 64, 256, 1024};
    private final CycleScheduler clock;
    private final InterruptController interrupts;
    private final Timer[] timers = new Timer[4];
    private AudioUnit audio;
    private DmaController dma;

    public TimerUnit(CycleScheduler clock, InterruptController interrupts) {
        this.clock = clock;
        this.interrupts = interrupts;
        for (var i = 0; i < 4; i++) timers[i] = new Timer(i);
    }

    void attachSound(AudioUnit unit, DmaController controller) { audio = unit; dma = controller; }

    int read8(int address) {
        var offset = address - 0x04000100;
        var timer = timers[offset / 4];
        timer.synchronize();
        var value = (offset & 2) == 0 ? timer.counter : timer.control;
        return (value >>> ((offset & 1) * 8)) & 255;
    }

    void write8(int address, int value) {
        var offset = address - 0x04000100;
        var timer = timers[offset / 4];
        value &= 255;
        if ((offset & 2) == 0) {
            var shift = (offset & 1) * 8;
            timer.reload = (timer.reload & ~(255 << shift)) | (value << shift);
        } else if ((offset & 1) == 0) {
            timer.synchronize();
            var enabled = timer.enabled();
            var previous = timer.control;
            timer.control = value & (timer.index == 0 ? 0xc3 : 0xc7);
            if (!enabled && timer.enabled()) timer.counter = timer.reload;
            if (previous != timer.control) timer.lastCycle = clock.now();
            timer.schedule();
        }
    }

    private final class Timer implements CycleEvent {
        private final int index;
        private int reload;
        private int counter;
        private int control;
        private long lastCycle;

        Timer(int index) { this.index = index; }

        boolean enabled() { return (control & 0x80) != 0; }

        boolean cascade() { return (control & 4) != 0; }

        void synchronize() {
            if (enabled() && !cascade()) {
                var period = PERIODS[control & 3];
                var ticks = (clock.now() - lastCycle) / period;
                counter = (counter + (int) ticks) & 0xffff;
                lastCycle += ticks * period;
            }
        }

        void schedule() {
            clock.cancel(this);
            if (enabled() && !cascade()) {
                clock.schedule(this, lastCycle + (0x10000L - counter) * PERIODS[control & 3]);
            }
        }

        public void fire() {
            counter = reload;
            lastCycle = clock.now();
            if ((control & 0x40) != 0) interrupts.request(8 << index);
            if (audio != null && index < 2) {
                var request = audio.timerOverflow(index);
                if ((request & 1) != 0) dma.requestFifo(0x040000a0);
                if ((request & 2) != 0) dma.requestFifo(0x040000a4);
            }
            if (index < 3) {
                var next = timers[index + 1];
                if (next.enabled() && next.cascade()) {
                    next.counter++;
                    if (next.counter == 0x10000) next.fire();
                }
            }
            schedule();
        }
    }
}

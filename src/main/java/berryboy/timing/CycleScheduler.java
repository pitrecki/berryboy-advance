package berryboy.timing;

import berryboy.EmulationException;

/**
 * Deterministic guest clock. Equal deadlines execute in scheduling order.
 * One pending deadline per callback; scheduling it again replaces that deadline.
 * No wall clock, threads, collections or per-dispatch allocation are required.
 */
public final class CycleScheduler {
    public static final int CLOCK_HZ = 16777216;
    private CycleEvent[] events = new CycleEvent[16];
    private long[] deadlines = new long[16];
    private int size;
    private long now;
    private boolean advancing;

    public long now() {
        return now;
    }

    /** Long.MAX_VALUE denotes an empty queue; deadlines themselves may use it. */
    public long nextDeadline() {
        return size == 0 ? Long.MAX_VALUE : deadlines[0];
    }

    public boolean hasEvents() {
        return size != 0;
    }

    public void schedule(CycleEvent event, long deadline) {
        if (event == null || deadline < now) {
            throw new IllegalArgumentException("Event must have a callback and a future/current deadline");
        }
        cancel(event);
        if (size == events.length) {
            var expandedEvents = new CycleEvent[size * 2];
            var expandedDeadlines = new long[size * 2];
            System.arraycopy(events, 0, expandedEvents, 0, size);
            System.arraycopy(deadlines, 0, expandedDeadlines, 0, size);
            events = expandedEvents;
            deadlines = expandedDeadlines;
        }
        var index = size;
        while (index > 0 && deadlines[index - 1] > deadline) {
            events[index] = events[index - 1];
            deadlines[index] = deadlines[index - 1];
            index--;
        }
        events[index] = event;
        deadlines[index] = deadline;
        size++;
    }

    public void cancel(CycleEvent event) {
        for (var index = 0; index < size; index++) {
            if (events[index] == event) {
                remove(index);
                return;
            }
        }
    }

    public void advance(long cycles) {
        if (cycles < 0 || cycles > Long.MAX_VALUE - now) {
            throw new IllegalArgumentException("Invalid cycle budget");
        }
        advanceTo(now + cycles);
    }

    public void advanceTo(long target) {
        if (target < now) {
            throw new IllegalArgumentException("Guest time cannot go backwards");
        }
        if (advancing) {
            throw new EmulationException("Recursive guest-clock advancement");
        }
        advancing = true;
        try {
            while (size > 0 && deadlines[0] <= target) {
                now = deadlines[0];
                var event = events[0];
                remove(0); // A callback may reschedule itself or cancel another event.
                event.fire();
            }
            now = target;
        } finally {
            advancing = false;
        }
    }

    /** Device owners must re-register their reset deadlines after this call. */
    public void reset() {
        if (advancing) {
            throw new EmulationException("Cannot reset a dispatching guest clock");
        }
        while (size > 0) {
            events[--size] = null;
        }
        now = 0;
    }

    private void remove(int index) {
        size--;
        System.arraycopy(events, index + 1, events, index, size - index);
        System.arraycopy(deadlines, index + 1, deadlines, index, size - index);
        events[size] = null;
    }
}

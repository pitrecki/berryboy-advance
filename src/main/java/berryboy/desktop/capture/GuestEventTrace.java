package berryboy.desktop.capture;

import berryboy.Machine;
import berryboy.bus.Memory;
import berryboy.timing.CycleEvent;
import berryboy.timing.CycleObserver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Bounded primitive snapshots; no disk IO or live-state handoff during dispatch. */
public final class GuestEventTrace implements CycleObserver {
    private static final int DEFAULT_CAPACITY = 65536;
    private static final int MAX_CAPACITY = 1000000;
    private static final int STATE_COLUMNS = 14;
    private static final int BLEND_CONTROL = Memory.IO_BASE + 0x50;
    private static final int BLEND_ALPHA = Memory.IO_BASE + 0x52;
    private static final int BLEND_BRIGHTNESS = Memory.IO_BASE + 0x54;
    private static final int INTERRUPT_ENABLE = Memory.IO_BASE + 0x200;
    private static final int INTERRUPT_FLAGS = Memory.IO_BASE + 0x202;
    private static final int INTERRUPT_MASTER = Memory.IO_BASE + 0x208;
    private final Machine machine;
    private final long start;
    private final long end;
    private final boolean allEvents;
    private final long[] cycles;
    private final int[][] values;
    private final IdentityHashMap<CycleEvent, Integer> eventIds = new IdentityHashMap<>();
    private final ArrayList<String> eventNames = new ArrayList<>();
    private int size;
    private long dropped;

    private GuestEventTrace(Machine machine, long start, long end, int capacity, boolean allEvents) {
        this.machine = machine;
        this.start = start;
        this.end = end;
        this.allEvents = allEvents;
        cycles = new long[capacity];
        values = new int[STATE_COLUMNS][capacity];
    }

    /** Validate before creating outputs. The cycle window is [start, end). */
    public static GuestEventTrace from(Properties config, Machine machine, long budget) {
        var enabled = config.getProperty("event_trace", "false");
        if (!enabled.equals("true") && !enabled.equals("false")) {
            throw new IllegalArgumentException("event_trace must be true or false");
        }
        if (enabled.equals("false")) {
            for (var key : new String[]{"trace_start_cycle", "trace_end_cycle", "trace_capacity", "trace_events"}) {
                if (config.containsKey(key)) throw new IllegalArgumentException(key + " requires event_trace=true");
            }
            return null;
        }
        var start = Long.parseLong(config.getProperty("trace_start_cycle", "0"));
        var end = Long.parseLong(config.getProperty("trace_end_cycle", Long.toString(budget + 1)));
        var capacity = Integer.parseInt(config.getProperty("trace_capacity", Integer.toString(DEFAULT_CAPACITY)));
        var events = config.getProperty("trace_events", "lcd");
        if (!events.equals("lcd") && !events.equals("all")) throw new IllegalArgumentException("trace_events must be lcd or all");
        if (start < 0 || end <= start || end > budget + 1 || capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("Invalid event trace window or capacity");
        }
        return new GuestEventTrace(machine, start, end, capacity, events.equals("all"));
    }

    public void dispatch(CycleEvent event, boolean before) {
        var cycle = machine.clock.now();
        if (cycle < start || cycle >= end) return;
        if (!allEvents && event != machine.memory.lcd()) return;
        if (size == cycles.length) {
            dropped++;
            return;
        }
        var id = eventIds.get(event);
        if (id == null) {
            id = eventNames.size();
            eventIds.put(event, id);
            var name = event.getClass().getName();
            var lambda = name.indexOf("$$Lambda");
            eventNames.add(lambda < 0 ? name : name.substring(0, lambda) + "::callback");
        }
        cycles[size] = cycle;
        values[0][size] = before ? 0 : 1;
        values[1][size] = id;
        values[2][size] = machine.cpu.pc();
        values[3][size] = machine.cpu.cpsr();
        values[4][size] = machine.memory.lcd().line();
        values[5][size] = machine.memory.lcd().status();
        // CPU accesses may be active inside clock dispatch. Read-only peeks
        // avoid charging a bus access and changing the instruction's timing.
        values[6][size] = machine.memory.peek16(Memory.DISPLAY_CONTROL);
        values[7][size] = machine.memory.peek16(BLEND_CONTROL);
        values[8][size] = machine.memory.peek16(BLEND_ALPHA);
        values[9][size] = machine.memory.peek16(BLEND_BRIGHTNESS);
        values[10][size] = machine.memory.peek16(INTERRUPT_ENABLE);
        values[11][size] = machine.memory.peek16(INTERRUPT_FLAGS);
        values[12][size] = machine.memory.peek16(INTERRUPT_MASTER);
        values[13][size] = machine.memory.halted() ? 1 : 0;
        size++;
    }

    /** Call after detaching from the guest owner; only copied state is serialized. */
    public Map<String, Object> write(Path output) throws IOException {
        try (var writer = Files.newBufferedWriter(output)) {
            writer.write("cycle\tdispatch_cycle\tphase\tevent_id\tevent\tpc\tcpsr\tvcount\tdispstat\tdispcnt\tbldcnt\tbldalpha\tbldy\tie\tif\time\thalted\n");
            for (var row = 0; row < size; row++) {
                writer.write(cycles[row] + "\t" + cycles[row] + "\t" + (values[0][row] == 0 ? "before" : "after")
                        + "\t" + values[1][row] + "\t" + eventNames.get(values[1][row]));
                for (var column = 2; column < STATE_COLUMNS; column++) {
                    writer.write("\t" + Integer.toUnsignedString(values[column][row]));
                }
                writer.write("\n");
            }
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("file", output.getFileName().toString());
        summary.put("status", dropped == 0 ? "complete" : "truncated");
        summary.put("start_cycle", start); summary.put("end_cycle_exclusive", end);
        summary.put("capacity", cycles.length); summary.put("rows", size); summary.put("dropped_rows", dropped);
        summary.put("events", allEvents ? "all" : "lcd");
        summary.put("scope", "scheduled callback boundaries only; not CPU bus accesses or IRQ entry");
        return summary;
    }
}

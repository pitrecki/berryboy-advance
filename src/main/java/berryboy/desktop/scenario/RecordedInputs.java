package berryboy.desktop.scenario;

import berryboy.Machine;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Validated immutable recording shared by scenario captures and state-replay tools. */
public final class RecordedInputs {
    private final List<Input> events;

    private RecordedInputs(List<Input> events) { this.events = List.copyOf(events); }

    public static RecordedInputs parse(byte[] bytes) {
        var events = new ArrayList<Input>();
        long previous = -1; int lineNumber = 0;
        for (var line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            lineNumber++;
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            var values = line.strip().split("\\s+");
            if (values.length != 6) throw invalid(lineNumber);
            Input event;
            try {
                event = new Input(Long.parseLong(values[0]), Integer.decode(values[1]), Integer.decode(values[2]),
                        Integer.decode(values[3]), Integer.decode(values[4]), Integer.decode(values[5]));
            } catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid input number at line " + lineNumber, error); }
            if (event.cycle <= previous || event.keys < 0 || event.keys > 1023
                    || event.x < 0 || event.x > 4095 || event.y < 0 || event.y > 4095
                    || event.gyro < 0 || event.gyro > 4095 || event.solar < 0 || event.solar > 255) throw invalid(lineNumber);
            events.add(event); previous = event.cycle;
        }
        return new RecordedInputs(events);
    }

    /** -1 for an empty recording; inspection never schedules or executes input. */
    public long lastCycle() { return events.isEmpty() ? -1 : events.getLast().cycle; }

    /** Attach original absolute guest-cycle timestamps, preserving recording order. */
    public void install(Machine machine) {
        if (!events.isEmpty() && events.getFirst().cycle < machine.clock.now()) {
            throw new IllegalArgumentException("Input recording starts before the current guest cycle");
        }
        for (var event : events) machine.clock.schedule(() -> event.applyTo(machine), event.cycle);
    }

    private static IllegalArgumentException invalid(int line) { return new IllegalArgumentException("Invalid input event at line " + line); }

    private record Input(long cycle, int keys, int x, int y, int gyro, int solar) {
        void applyTo(Machine machine) {
            machine.memory.setPressedKeys(keys); machine.memory.gpio().setSensors(x, y, gyro, solar);
        }
    }
}

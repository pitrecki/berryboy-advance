package berryboy.desktop.scenario;

import berryboy.Machine;
import berryboy.input.Keypad;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Host button changes between completed frames; never adds events to the guest clock. */
public final class FrameInputs {
    private final List<Input> events;

    private FrameInputs(List<Input> events) { this.events = List.copyOf(events); }

    public static FrameInputs parse(byte[] bytes) {
        var events = new ArrayList<Input>();
        int previous = -1, lineNumber = 0;
        for (var line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            lineNumber++;
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            var fields = line.strip().split("\\s+");
            if (fields.length != 2) throw invalid(lineNumber);
            try {
                var frame = Integer.parseInt(fields[0]);
                var keys = Integer.decode(fields[1]);
                if (frame <= previous || keys < 0 || keys > Keypad.ALL_BUTTONS) throw invalid(lineNumber);
                events.add(new Input(frame, keys));
                previous = frame;
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Invalid frame input number at line " + lineNumber, error);
            }
        }
        return new FrameInputs(events);
    }

    public int firstFrame() { return events.isEmpty() ? -1 : events.getFirst().frame(); }
    public int lastFrame() { return events.isEmpty() ? -1 : events.getLast().frame(); }
    public Playback playback(Machine machine) { return new Playback(machine); }

    /** One cursor per deterministic guest owner; parsing and construction never change the guest. */
    public final class Playback {
        private final Machine machine;
        private int index;
        private int previous = -1;

        private Playback(Machine machine) { this.machine = machine; }

        /** Call just before executing frame completedFrame+1, after any cycle-input prefix. */
        public void afterFrame(int completedFrame) {
            if (completedFrame <= previous) throw new IllegalArgumentException("Frame input cannot go backwards or repeat");
            if (index < events.size() && events.get(index).frame() < completedFrame) {
                throw new IllegalArgumentException("Missed frame input boundary " + events.get(index).frame());
            }
            previous = completedFrame;
            if (index < events.size() && events.get(index).frame() == completedFrame) {
                machine.memory.setPressedKeys(events.get(index++).keys());
            }
        }
    }

    private static IllegalArgumentException invalid(int line) {
        return new IllegalArgumentException("Invalid frame input at line " + line);
    }
    private record Input(int frame, int keys) { }
}

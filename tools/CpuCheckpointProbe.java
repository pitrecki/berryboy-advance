import berryboy.Machine;
import berryboy.timing.CycleEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Properties;

/** Bounded, fresh-backup CPU observations; never loads or writes a player save. */
public final class CpuCheckpointProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: CpuCheckpointProbe fresh.properties windows.tsv");
        var path = Path.of(args[0]).toAbsolutePath();
        var config = new Properties();
        try (var reader = Files.newBufferedReader(path)) { config.load(reader); }
        if (!config.getProperty("fresh_backup", "false").equals("true")
                || Integer.parseInt(config.getProperty("devices", "0")) != 0
                || !config.getProperty("rtc", "2000-01-01T00:00:00").equals("2000-01-01T00:00:00")) {
            throw new IllegalArgumentException("Requires explicit fresh_backup=true, default RTC and no peripherals");
        }
        var windows = new ArrayList<long[]>();
        long samples = 0, previousEnd = -1;
        for (var line : Files.readAllLines(Path.of(args[1]))) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            var bounds = line.strip().split("\\s+");
            if (bounds.length != 2) throw new IllegalArgumentException("Expected startCycle endCycle");
            var start = Long.parseLong(bounds[0]);
            var end = Long.parseLong(bounds[1]);
            samples += end - start + 1;
            if (start <= previousEnd || end <= start || end - start > 10000
                    || end > 3600L * 280896 || samples > 100000 || windows.size() >= 256) {
                throw new IllegalArgumentException("Invalid/overlapping windows or sample budget exceeded");
            }
            windows.add(new long[]{start, end});
            previousEnd = end;
        }
        if (windows.isEmpty()) throw new IllegalArgumentException("At least one window required");
        var machine = new Machine(verified(path.getParent(), config, "rom"), verified(path.getParent(), config, "bios"));
        if (config.containsKey("backup")) throw new IllegalArgumentException("Explicit backup overrides are outside this probe's scope");
        var input = verified(path.getParent(), config, "input");
        long previous = -1;
        for (var line : new String(input, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            var values = line.strip().split("\\s+");
            if (values.length != 6) throw new IllegalArgumentException("Expected cycle keys tiltX tiltY gyro solar");
            var cycle = Long.parseLong(values[0]);
            var keys = Integer.decode(values[1]);
            var x = Integer.decode(values[2]); var y = Integer.decode(values[3]);
            var gyro = Integer.decode(values[4]); var solar = Integer.decode(values[5]);
            if (cycle <= previous || keys < 0 || keys > 1023 || x < 0 || x > 4095
                    || y < 0 || y > 4095 || gyro < 0 || gyro > 4095 || solar < 0 || solar > 255) {
                throw new IllegalArgumentException("Invalid input event");
            }
            previous = cycle;
            machine.clock.schedule(() -> {
                machine.memory.setPressedKeys(keys);
                machine.memory.gpio().setSensors(x, y, gyro, solar);
            }, cycle);
        }
        var rows = new ArrayList<String>();
        CycleEvent sample = new CycleEvent() {
            private int window;
            public void fire() {
                rows.add(snapshot(machine, "clock"));
                if (machine.clock.now() < windows.get(window)[1]) machine.clock.schedule(this, machine.clock.now() + 1);
                else if (++window < windows.size()) machine.clock.schedule(this, windows.get(window)[0]);
            }
        };
        machine.clock.schedule(sample, windows.getFirst()[0]);
        var activeWindow = 0;
        while (machine.clock.now() < windows.getLast()[1]) {
            var retired = machine.cpu.instructions();
            machine.cpu.step();
            while (activeWindow < windows.size() && machine.clock.now() > windows.get(activeWindow)[1]) activeWindow++;
            if (activeWindow < windows.size() && machine.clock.now() >= windows.get(activeWindow)[0]) {
                rows.add(snapshot(machine, machine.cpu.instructions() == retired ? "step" : "retire"));
            }
        }
        System.out.print("kind,cycle,instructions,pc,cpsr,halted,irq");
        for (var r = 0; r < 15; r++) System.out.print(",r" + r);
        System.out.println();
        for (var row : rows) System.out.println(row);
        if (System.out.checkError()) throw new IllegalStateException("Cannot write CPU trace");
    }

    private static String snapshot(Machine machine, String kind) {
        var row = new StringBuilder(kind).append(',').append(machine.clock.now())
                .append(',').append(machine.cpu.instructions()).append(',').append(Integer.toUnsignedString(machine.cpu.pc()))
                .append(',').append(Integer.toUnsignedString(machine.cpu.cpsr())).append(',').append(machine.memory.halted())
                // Raw IRQ port: a CPU-context Memory read would charge bus
                // cycles and recursively advance the dispatching clock.
                .append(',').append(machine.memory.interrupts().read8(0x04000202)
                        | (machine.memory.interrupts().read8(0x04000203) << 8));
        for (var r = 0; r < 15; r++) row.append(',').append(Integer.toUnsignedString(machine.cpu.register(r)));
        return row.toString();
    }

    private static byte[] verified(Path base, Properties config, String key) throws Exception {
        var value = config.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing " + key);
        var bytes = Files.readAllBytes(base.resolve(value));
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!hash.equals(config.getProperty(key + "_sha256"))) throw new IllegalArgumentException("Wrong " + key + " SHA-256");
        return bytes;
    }
}

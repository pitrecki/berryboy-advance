import berryboy.Machine;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;

/** Host-only, read-only private-state adapter. Installs no scheduler events. */
public final class DmaHandoffProbe {
    private static final String[] NAMES = {"pending", "remaining", "currentSource", "currentDestination", "first", "control"};

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: DmaHandoffProbe fresh.properties startCycle endCycle");
        var start = Long.parseLong(args[1]);
        var end = Long.parseLong(args[2]);
        if (start < 0 || end <= start || end - start > 10000 || end > 3600L * 280896) {
            throw new IllegalArgumentException("Invalid bounded handoff window");
        }
        var machine = CpuCheckpointProbe.loadFreshMachine(Path.of(args[0]).toAbsolutePath());
        var dma = machine.memory.dma();
        var channels = (Object[]) field(dma.getClass(), "channels").get(dma);
        var owner = field(dma.getClass(), "ownsBus");
        var fields = new Field[NAMES.length];
        for (var i = 0; i < fields.length; i++) fields[i] = field(channels[0].getClass(), NAMES[i]);
        var rows = new ArrayList<String>();
        machine.clock.setObserver((event, before) -> {
            if (machine.clock.now() >= start && machine.clock.now() <= end) {
                rows.add(snapshot(machine, (before ? "before:" : "after:") + event.getClass().getName(), dma, channels, owner, fields));
            }
        });
        try {
            while (machine.clock.now() < end) {
                machine.cpu.step();
                if (machine.clock.now() >= start && machine.clock.now() <= end) {
                    rows.add(snapshot(machine, "step", dma, channels, owner, fields));
                }
            }
        } finally {
            machine.clock.setObserver(null);
        }
        System.out.print("kind,cycle,instructions,pc,owns_bus");
        for (var c = 0; c < 4; c++) for (var name : NAMES) System.out.print(",ch" + c + "_" + name);
        System.out.println();
        for (var row : rows) System.out.println(row);
        if (System.out.checkError()) throw new IllegalStateException("Cannot write handoff trace");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var result = type.getDeclaredField(name);
        result.setAccessible(true); // Fail on incompatible layouts; never write guest fields.
        return result;
    }

    private static String snapshot(Machine machine, String kind, Object dma, Object[] channels, Field owner, Field[] fields) {
        try {
            var row = new StringBuilder(kind).append(',').append(machine.clock.now())
                    .append(',').append(machine.cpu.instructions()).append(',').append(Integer.toUnsignedString(machine.cpu.pc()))
                    .append(',').append(owner.getBoolean(dma));
            for (var channel : channels) for (var field : fields) row.append(',').append(field.get(channel));
            return row.toString();
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot inspect DMA reference layout", error);
        }
    }
}

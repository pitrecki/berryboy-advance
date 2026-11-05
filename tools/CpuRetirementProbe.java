import berryboy.Machine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Observe completed instructions only; installs no diagnostic clock events. */
public final class CpuRetirementProbe {
    private static final int BLOCK_SIZE = 4096;
    private static final long MAX_CYCLES = 3600L * 280896;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("Usage: digest fresh.properties endCycle | trace fresh.properties firstInstruction lastInstruction");
        var digestMode = args[0].equals("digest");
        if ((!digestMode && !args[0].equals("trace")) || args.length != (digestMode ? 3 : 4)) {
            throw new IllegalArgumentException("Unknown mode or wrong argument count");
        }
        var first = digestMode ? 1 : Long.parseLong(args[2]);
        var last = digestMode ? Long.MAX_VALUE : Long.parseLong(args[3]);
        var endCycle = digestMode ? Long.parseLong(args[2]) : MAX_CYCLES;
        if (first < 1 || last < first || (!digestMode && last - first >= BLOCK_SIZE)
                || endCycle < 1 || endCycle > MAX_CYCLES) throw new IllegalArgumentException("Invalid cycle/instruction bound");
        var machine = CpuCheckpointProbe.loadFreshMachine(Path.of(args[1]).toAbsolutePath());
        var digest = MessageDigest.getInstance("SHA-256");
        var state = ByteBuffer.allocate(81).order(ByteOrder.LITTLE_ENDIAN);
        long blockFirst = 1;
        long lastRetiredCycle = 0;
        System.out.println(digestMode ? "first,last,cycle,sha256" : "instruction,cycle,pc,cpsr,r0,r1,r2,r3,r4,r5,r6,r7,r8,r9,r10,r11,r12,r13,r14,irq,halted");
        while (machine.clock.now() < endCycle && machine.cpu.instructions() < last) {
            var retired = machine.cpu.instructions();
            machine.cpu.step();
            if (machine.cpu.instructions() == retired) continue;
            var index = machine.cpu.instructions();
            lastRetiredCycle = machine.clock.now();
            if (digestMode) {
                state.clear();
                state.putLong(machine.clock.now()).putInt(machine.cpu.pc()).putInt(machine.cpu.cpsr());
                for (var r = 0; r < 15; r++) state.putInt(machine.cpu.register(r));
                state.putInt(irq(machine)).put((byte) (machine.memory.halted() ? 1 : 0));
                digest.update(state.array(), 0, state.position());
                if (index % BLOCK_SIZE == 0) {
                    printDigest(blockFirst, index, lastRetiredCycle, digest);
                    blockFirst = index + 1;
                }
            } else if (index >= first) {
                var row = new StringBuilder().append(index).append(',').append(machine.clock.now())
                        .append(',').append(Integer.toUnsignedString(machine.cpu.pc()))
                        .append(',').append(Integer.toUnsignedString(machine.cpu.cpsr()));
                for (var r = 0; r < 15; r++) row.append(',').append(Integer.toUnsignedString(machine.cpu.register(r)));
                System.out.println(row.append(',').append(irq(machine)).append(',').append(machine.memory.halted()));
            }
        }
        if (!digestMode && machine.cpu.instructions() < last) throw new IllegalStateException("Cycle safety limit reached before requested instructions");
        if (digestMode && blockFirst <= machine.cpu.instructions()) printDigest(blockFirst, machine.cpu.instructions(), lastRetiredCycle, digest);
        if (System.out.checkError()) throw new IllegalStateException("Cannot write retirement observations");
    }

    private static int irq(Machine machine) {
        return machine.memory.interrupts().read8(0x04000202) | (machine.memory.interrupts().read8(0x04000203) << 8);
    }

    private static void printDigest(long first, long last, long cycle, MessageDigest digest) {
        System.out.println(first + "," + last + "," + cycle + "," + HexFormat.of().formatHex(digest.digest()));
    }
}

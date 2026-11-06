package berryboy.boot;

import berryboy.EmulationException;
import berryboy.Machine;
import berryboy.bus.Memory;
import berryboy.timing.CycleScheduler;

/** Runs authentic BIOS startup in bounded guest budgets before a frontend presents gameplay. */
public final class BiosStartup {
    private final Machine machine;
    private final long deadline;
    private long cleanFrame = Long.MAX_VALUE;
    private boolean complete;

    public BiosStartup(Machine machine) { this(machine, 10L * CycleScheduler.CLOCK_HZ); }

    BiosStartup(Machine machine, long maximumCycles) {
        if (maximumCycles <= 0 || maximumCycles > Long.MAX_VALUE - machine.clock.now()) {
            throw new IllegalArgumentException("Invalid startup cycle limit");
        }
        this.machine = machine;
        deadline = machine.clock.now() + maximumCycles;
        complete = !machine.memory.hasBios();
    }

    /** Keep the same execution and end-of-budget state as Machine.runCycles. */
    public void runCycles(long cycles) {
        if (cycles < 0 || cycles > Long.MAX_VALUE - machine.clock.now()) throw new IllegalArgumentException("Invalid cycle budget");
        if (complete) return;
        var target = machine.clock.now() + cycles;
        while (machine.clock.now() < target) {
            machine.cpu.step();
            var pc = machine.cpu.pc();
            if (cleanFrame == Long.MAX_VALUE && pc >= Memory.ROM_BASE && pc < 0x0e000000
                    && !machine.cpu.instructionInFlight() && !machine.cpu.fetchInFlight()) {
                // The first completion can contain BIOS pixels drawn before
                // the branch. The following complete frame contains only game work.
                cleanFrame = machine.memory.raster().completedFrames() + 2;
            }
            if (cleanFrame == Long.MAX_VALUE && machine.clock.now() >= deadline) {
                throw new EmulationException("BIOS startup did not reach the cartridge; check the ROM and BIOS");
            }
            if (machine.memory.raster().completedFrames() >= cleanFrame) complete = true;
        }
    }

    public boolean complete() { return complete; }
}

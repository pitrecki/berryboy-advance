package berryboy.cpu;

import berryboy.EmulationException;
import berryboy.bus.Memory;

/**
 * ARM7TDMI execution loop. One step services a DMA unit, interrupt, HALT event
 * or instruction phase and advances guest cycles. A decoded instruction can
 * wait for DMA during internal cycles; it retires only after that interval.
 * The interpreters and register banks are created once and reused for every step.
 */
public final class Cpu {
    public static final int STACK_POINTER = 13;
    public static final int USER = 0x10;
    public static final int FIQ = 0x11;
    public static final int IRQ = 0x12;
    public static final int SUPERVISOR = 0x13;
    public static final int ABORT = 0x17;
    public static final int UNDEFINED = 0x1b;
    public static final int SYSTEM = 0x1f;

    public static final int N = 0x80000000;
    public static final int Z = 0x40000000;
    public static final int C = 0x20000000;
    public static final int V = 0x10000000;
    public static final int T = 0x20;

    private final Memory memory;
    private final CpuState state;
    private final ArmInterpreter arm;
    private final ThumbInterpreter thumb;
    private long instructionCount;
    private boolean instructionInFlight;
    private int remainingInternalCycles;

    public Cpu(Memory memory) {
        this.memory = memory;
        this.state = new CpuState();
        this.arm = new ArmInterpreter(state, memory);
        this.thumb = new ThumbInterpreter(state, memory);
    }

    public void step() {
        if (serviceDma()) return;
        if (instructionInFlight) {
            resumeInstructionTiming();
            return;
        }
        if (memory.halted()) {
            if (!memory.interrupts().wakePending()) {
                memory.clock().advanceTo(memory.clock().nextDeadline());
                return;
            }
            memory.wake();
        }
        if (memory.interrupts().irqPending() && (state.status() & 0x80) == 0) {
            if (!memory.hasBios()) throw new EmulationException("IRQ needs a supplied BIOS");
            state.enterIrq();
            memory.clock().advance(1);
            memory.busTiming().refill(0x18, 4);
            return;
        }
        var address = state.programCounter();
        var thumbMode = state.isThumb();
        int opcode = 0;
        try {
            opcode = memory.fetch(address, thumbMode);
            state.beginInstruction(thumbMode ? 2 : 4);
            var execute = thumbMode || state.conditionPassed(opcode >>> 28);
            var internal = execute ? InstructionTiming.internal(opcode, thumbMode, state) : 0;
            if (thumbMode) {
                thumb.execute(opcode);
            } else if (execute) {
                arm.execute(opcode);
            }
            remainingInternalCycles = internal;
            instructionInFlight = true;
            // DMA accesses must not inherit the CPU's timed-data context.
            memory.finishInstruction();
            resumeInstructionTiming();
        } catch (EmulationException error) {
            // A diagnostic stop restores PC, but does not roll back memory side effects.
            state.writeRegister(CpuState.PROGRAM_COUNTER, address);
            memory.busTiming().invalidateFetch();
            instructionInFlight = false;
            remainingInternalCycles = 0;
            throw new EmulationException(error.getMessage() + " [opcode=0x" + Integer.toHexString(opcode)
                    + ", pc=0x" + Integer.toHexString(address) + "]");
        } finally {
            memory.finishInstruction();
        }
    }

    private void resumeInstructionTiming() {
        // Preserve dispatch of zero-delay device writes even for instructions
        // with no internal cycles. Readiness at the end of the final cycle
        // belongs to the next bus operation, not to an extra retirement stall.
        memory.busTiming().internal(0, state.branched());
        while (remainingInternalCycles > 0) {
            if (serviceDma()) return; // At most one DMA unit per public step.
            memory.busTiming().internal(1, state.branched());
            remainingInternalCycles--;
        }
        retireInstruction();
    }

    private boolean serviceDma() {
        var before = memory.clock().now();
        if (!memory.dma().runPending()) return false;
        if (instructionInFlight) {
            // Internal CPU work overlaps this DMA interval. Keep retirement
            // blocked until ownership ends, even when its cycle budget is used.
            var elapsed = memory.clock().now() - before;
            remainingInternalCycles = elapsed >= remainingInternalCycles ? 0
                    : remainingInternalCycles - (int) elapsed;
            if (!memory.dma().ownsBus() && remainingInternalCycles == 0) retireInstruction();
        }
        return true;
    }

    private void retireInstruction() {
        if (state.branched()) memory.busTiming().refill(state.programCounter(), state.isThumb() ? 2 : 4);
        instructionInFlight = false;
        instructionCount++;
    }

    /** Registers/PC can reflect executed work before the instruction retires. */
    public boolean instructionInFlight() { return instructionInFlight; }

    public int register(int index) {
        return state.register(index);
    }

    public void setRegister(int index, int value) {
        state.writeRegister(index, value);
    }

    public int pc() {
        return state.programCounter();
    }

    public void setPc(int address) {
        memory.busTiming().invalidateFetch();
        state.writeRegister(CpuState.PROGRAM_COUNTER, address);
    }

    public int cpsr() {
        return state.status();
    }

    public void setCpsr(int value) {
        state.writeStatus(value);
    }

    public long instructions() {
        return instructionCount;
    }

    public boolean isThumb() {
        return state.isThumb();
    }

    public boolean negative() {
        return state.negative();
    }

    public boolean zero() {
        return state.zero();
    }

    public boolean carry() {
        return state.carry();
    }

    public boolean overflow() {
        return state.overflow();
    }
}

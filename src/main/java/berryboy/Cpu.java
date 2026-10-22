package berryboy;

/**
 * ARM7TDMI execution loop. One step executes an instruction and advances guest cycles.
 * The interpreters and register banks are created once and reused for every step.
 */
public final class Cpu {
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

    public Cpu(Memory memory) {
        this.memory = memory;
        this.state = new CpuState();
        this.arm = new ArmInterpreter(state, memory);
        this.thumb = new ThumbInterpreter(state, memory);
    }

    public void step() {
        if (memory.dma().runPending()) return;
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
            memory.busTiming().internal(internal, state.branched());
            if (state.branched()) memory.busTiming().refill(state.programCounter(), state.isThumb() ? 2 : 4);
            instructionCount++;
        } catch (EmulationException error) {
            // A diagnostic stop restores PC, but does not roll back memory side effects.
            state.writeRegister(CpuState.PROGRAM_COUNTER, address);
            memory.busTiming().invalidateFetch();
            throw new EmulationException(error.getMessage() + " [opcode=0x" + Integer.toHexString(opcode)
                    + ", pc=0x" + Integer.toHexString(address) + "]");
        } finally {
            memory.finishInstruction();
        }
    }

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

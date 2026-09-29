package berryboy;

/**
 * ARM7TDMI execution loop. One step executes one guest instruction, not one clock.
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
        var address = state.programCounter();
        var thumbMode = state.isThumb();
        var opcode = thumbMode ? memory.read16(address) : memory.read32(address);
        state.beginInstruction(thumbMode ? 2 : 4);

        try {
            if (thumbMode) {
                thumb.execute(opcode);
            } else if (state.conditionPassed(opcode >>> 28)) {
                arm.execute(opcode);
            }
            instructionCount++;
        } catch (EmulationException error) {
            // A diagnostic stop restores PC, but does not roll back memory side effects.
            state.writeRegister(CpuState.PROGRAM_COUNTER, address);
            throw new EmulationException(error.getMessage()
                    + " [opcode=0x" + Integer.toHexString(opcode)
                    + ", pc=0x" + Integer.toHexString(address) + "]");
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

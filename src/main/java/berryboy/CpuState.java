package berryboy;

/** Registers, status flags and register banks of an ARM7TDMI processor. */
final class CpuState {
    static final int STACK_POINTER = 13;
    static final int LINK_REGISTER = 14;
    static final int PROGRAM_COUNTER = 15;

    private static final int MODE_MASK = 0x1f;
    private static final int IMPLEMENTED_STATUS_BITS = 0xf00000ff;
    private static final int FIRST_BANKED_REGISTER = 8;

    private final int[] registers = new int[16];
    private final int[] userRegisters = new int[7];
    private final int[] fastInterruptRegisters = new int[7];
    private final int[][] exceptionRegisters = new int[6][2];
    private final int[] savedStatus = new int[6];

    private int status = Cpu.SYSTEM;
    private int instructionAddress;
    private boolean branched;

    int register(int index) {
        return registers[index];
    }

    void writeRegister(int index, int value) {
        if (index == PROGRAM_COUNTER) {
            value &= isThumb() ? ~1 : ~3;
            branched = true;
        }
        registers[index] = value;
    }

    int programCounter() {
        return registers[PROGRAM_COUNTER];
    }

    void beginInstruction(int width) {
        branched = false;
        instructionAddress = programCounter();
        registers[PROGRAM_COUNTER] += width;
    }

    boolean branched() { return branched; }

    int instructionAddress() {
        return instructionAddress;
    }

    /** Architectural PC reads include the execution pipeline offset. */
    int operand(int index, int pipelineOffset) {
        return index == PROGRAM_COUNTER
                ? instructionAddress + pipelineOffset
                : registers[index];
    }

    int status() {
        return status;
    }

    void writeStatus(int value) {
        var nextMode = value & MODE_MASK;
        if (!validMode(nextMode)) {
            throw new IllegalArgumentException("Invalid ARM mode");
        }

        switchRegisterBank(mode(), nextMode);
        status = value & IMPLEMENTED_STATUS_BITS;
        registers[PROGRAM_COUNTER] &= isThumb() ? ~1 : ~3;
    }

    int mode() {
        return status & MODE_MASK;
    }

    boolean isThumb() {
        return flag(Cpu.T);
    }

    boolean negative() {
        return flag(Cpu.N);
    }

    boolean zero() {
        return flag(Cpu.Z);
    }

    boolean carry() {
        return flag(Cpu.C);
    }

    boolean overflow() {
        return flag(Cpu.V);
    }

    void setFlag(int mask, boolean enabled) {
        status = enabled ? status | mask : status & ~mask;
    }

    void updateSignAndZero(int result) {
        setFlag(Cpu.N, result < 0);
        setFlag(Cpu.Z, result == 0);
    }

    boolean conditionPassed(int condition) {
        return switch (condition) {
            case 0 -> zero();
            case 1 -> !zero();
            case 2 -> carry();
            case 3 -> !carry();
            case 4 -> negative();
            case 5 -> !negative();
            case 6 -> overflow();
            case 7 -> !overflow();
            case 8 -> carry() && !zero();
            case 9 -> !carry() || zero();
            case 10 -> negative() == overflow();
            case 11 -> negative() != overflow();
            case 12 -> !zero() && negative() == overflow();
            case 13 -> zero() || negative() != overflow();
            case 14 -> true;
            default -> false; // ARMv4 NV: never execute.
        };
    }

    void requireSavedStatus() {
        if (bankIndex(mode()) == 0) {
            throw new EmulationException("User/System mode has no SPSR");
        }
    }

    int savedStatus() {
        requireSavedStatus();
        return savedStatus[bankIndex(mode())];
    }

    void writeSavedStatus(int value) {
        requireSavedStatus();
        savedStatus[bankIndex(mode())] = value;
    }

    void restoreSavedStatus() {
        writeStatus(savedStatus());
    }

    void enterSupervisorCall() {
        var previousStatus = status;
        var returnAddress = programCounter();
        var supervisorStatus = (status & ~0x3f) | Cpu.SUPERVISOR | 0x80;

        writeStatus(supervisorStatus);
        writeSavedStatus(previousStatus);
        writeRegister(LINK_REGISTER, returnAddress);
        writeRegister(PROGRAM_COUNTER, 8);
    }

    int userRegister(int index) {
        if (index == PROGRAM_COUNTER) {
            return instructionAddress + 12;
        }
        return isSharedWithUserBank(index)
                ? registers[index]
                : userRegisters[index - FIRST_BANKED_REGISTER];
    }

    void writeUserRegister(int index, int value) {
        if (isSharedWithUserBank(index)) {
            registers[index] = value;
        } else {
            userRegisters[index - FIRST_BANKED_REGISTER] = value;
        }
    }

    static boolean validMode(int mode) {
        return switch (mode) {
            case Cpu.USER, Cpu.FIQ, Cpu.IRQ, Cpu.SUPERVISOR,
                    Cpu.ABORT, Cpu.UNDEFINED, Cpu.SYSTEM -> true;
            default -> false;
        };
    }

    private boolean flag(int mask) {
        return (status & mask) != 0;
    }

    private boolean isSharedWithUserBank(int index) {
        return index < 8
                || (index < STACK_POINTER && mode() != Cpu.FIQ)
                || usesUserStack(mode());
    }

    private void switchRegisterBank(int previousMode, int nextMode) {
        if (previousMode == nextMode) {
            return;
        }
        saveRegisterBank(previousMode);
        loadRegisterBank(nextMode);
    }

    private void saveRegisterBank(int mode) {
        var generalBank = mode == Cpu.FIQ ? fastInterruptRegisters : userRegisters;
        System.arraycopy(registers, FIRST_BANKED_REGISTER, generalBank, 0, 5);

        if (mode == Cpu.FIQ || usesUserStack(mode)) {
            generalBank[5] = registers[STACK_POINTER];
            generalBank[6] = registers[LINK_REGISTER];
        } else {
            var exceptionBank = exceptionRegisters[bankIndex(mode)];
            exceptionBank[0] = registers[STACK_POINTER];
            exceptionBank[1] = registers[LINK_REGISTER];
        }
    }

    private void loadRegisterBank(int mode) {
        var generalBank = mode == Cpu.FIQ ? fastInterruptRegisters : userRegisters;
        System.arraycopy(generalBank, 0, registers, FIRST_BANKED_REGISTER, 5);

        if (mode == Cpu.FIQ || usesUserStack(mode)) {
            registers[STACK_POINTER] = generalBank[5];
            registers[LINK_REGISTER] = generalBank[6];
        } else {
            var exceptionBank = exceptionRegisters[bankIndex(mode)];
            registers[STACK_POINTER] = exceptionBank[0];
            registers[LINK_REGISTER] = exceptionBank[1];
        }
    }

    private static boolean usesUserStack(int mode) {
        return mode == Cpu.USER || mode == Cpu.SYSTEM;
    }

    private static int bankIndex(int mode) {
        return switch (mode) {
            case Cpu.FIQ -> 1;
            case Cpu.IRQ -> 2;
            case Cpu.SUPERVISOR -> 3;
            case Cpu.ABORT -> 4;
            case Cpu.UNDEFINED -> 5;
            default -> 0;
        };
    }
}

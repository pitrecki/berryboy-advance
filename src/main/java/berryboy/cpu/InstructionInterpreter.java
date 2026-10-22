package berryboy.cpu;

import berryboy.EmulationException;
import berryboy.bus.Memory;

/** Shared architectural operations used by both instruction sets. */
abstract class InstructionInterpreter {
    protected final CpuState state;
    protected final Memory memory;
    protected final ArithmeticLogicUnit arithmetic;
    protected final BarrelShifter shifter;

    InstructionInterpreter(CpuState state, Memory memory) {
        this.state = state;
        this.memory = memory;
        this.arithmetic = new ArithmeticLogicUnit(state);
        this.shifter = new BarrelShifter();
    }

    abstract void execute(int opcode);

    protected int shift(int value, int type, int amount, boolean immediate) {
        return shifter.shift(value, type, amount, immediate, state.carry());
    }

    protected void branchExchange(int target) {
        state.setFlag(Cpu.T, (target & 1) != 0);
        state.writeRegister(CpuState.PROGRAM_COUNTER, target);
    }

    protected int loadWord(int address) {
        if ((address >>> 24) == 14 || (address >>> 24) == 15) return memory.read32(address);
        var word = memory.read32(address & ~3);
        return BarrelShifter.rotateRight(word, (address & 3) * 8);
    }

    protected int loadHalfword(int address) {
        if ((address >>> 24) == 14 || (address >>> 24) == 15) return memory.read16(address);
        var halfword = memory.read16(address & ~1);
        return BarrelShifter.rotateRight(halfword, (address & 1) * 8);
    }

    protected int loadSignedHalfword(int address) {
        // ARM7 treats an odd signed halfword load as a signed byte load.
        return (address & 1) != 0
                ? (byte) memory.read8(address)
                : (short) memory.read16(address);
    }

    protected void softwareInterrupt() {
        if (!memory.hasBios()) {
            throw new EmulationException("SWI needs a supplied BIOS; HLE services are not implemented");
        }
        state.enterSupervisorCall();
    }

    protected static boolean matches(int opcode, int mask, int encoding) {
        return (opcode & mask) == encoding;
    }

    protected static boolean bitSet(int opcode, int mask) {
        return (opcode & mask) != 0;
    }

    protected static int registerCount(int registerList) {
        var count = 0;
        while (registerList != 0) {
            registerList &= registerList - 1;
            count++;
        }
        return count;
    }

    protected static int firstRegister(int registerList) {
        var index = 0;
        while ((registerList & 1) == 0) {
            registerList >>>= 1;
            index++;
        }
        return index;
    }

    protected static void reject(String reason) {
        throw new EmulationException(reason);
    }
}

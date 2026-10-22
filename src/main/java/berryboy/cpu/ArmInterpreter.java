package berryboy.cpu;

import berryboy.bus.Memory;

/** Decoder and execution rules for the 32-bit ARM instruction set. */
final class ArmInterpreter extends InstructionInterpreter {
    private static final int IMMEDIATE_OPERAND = 1 << 25;
    private static final int PRE_INDEXED = 1 << 24;
    private static final int ADD_OFFSET = 1 << 23;
    private static final int BYTE_TRANSFER = 1 << 22;
    private static final int WRITE_BACK = 1 << 21;
    private static final int LOAD = 1 << 20;
    private static final int UPDATE_FLAGS = 1 << 20;
    private static final int SAVED_STATUS = 1 << 22;

    ArmInterpreter(CpuState state, Memory memory) {
        super(state, memory);
    }

    void execute(int opcode) {
        // More specific encodings must precede the broad data-processing family.
        if (matches(opcode, 0x0ffffff0, 0x012fff10)) {
            branchExchange(state.operand(opcode & 15, 8));
        } else if (matches(opcode, 0x0fbf0fff, 0x010f0000)) {
            readStatus(opcode);
        } else if (matches(opcode, 0x0fb0fff0, 0x0120f000)
                || matches(opcode, 0x0fb0f000, 0x0320f000)) {
            writeStatus(opcode);
        } else if (matches(opcode, 0x0fb00ff0, 0x01000090)) {
            swapMemory(opcode);
        } else if (matches(opcode, 0x0f8000f0, 0x00800090)) {
            multiplyLong(opcode);
        } else if (matches(opcode, 0x0fc000f0, 0x00000090)) {
            multiply(opcode);
        } else if (matches(opcode, 0x0e000090, 0x00000090)) {
            transferHalfword(opcode);
        } else if (matches(opcode, 0x0c000000, 0)) {
            processData(opcode);
        } else if (matches(opcode, 0x0c000000, 0x04000000)) {
            transferSingle(opcode);
        } else if (matches(opcode, 0x0e000000, 0x08000000)) {
            transferMultiple(opcode);
        } else if (matches(opcode, 0x0e000000, 0x0a000000)) {
            branch(opcode);
        } else if (matches(opcode, 0x0f000000, 0x0f000000)) {
            softwareInterrupt();
        } else {
            reject("Unsupported ARM instruction (including coprocessor/ARMv5)");
        }
    }

    private void branch(int opcode) {
        if (bitSet(opcode, 1 << 24)) {
            state.writeRegister(CpuState.LINK_REGISTER, state.instructionAddress() + 4);
        }
        var displacement = opcode << 8 >> 6;
        state.writeRegister(CpuState.PROGRAM_COUNTER, state.instructionAddress() + 8 + displacement);
    }

    private void processData(int opcode) {
        var operation = (opcode >>> 21) & 15;
        var destination = (opcode >>> 12) & 15;
        var updateFlags = bitSet(opcode, UPDATE_FLAGS);
        var comparison = operation >= ArithmeticLogicUnit.TST && operation <= ArithmeticLogicUnit.CMN;
        var restoreStatus = !comparison && updateFlags && destination == CpuState.PROGRAM_COUNTER;

        if (comparison && !updateFlags) {
            reject("Reserved ARM encoding");
        }
        if (restoreStatus) {
            state.requireSavedStatus();
        }

        var registerShift = !bitSet(opcode, IMMEDIATE_OPERAND) && bitSet(opcode, 16);
        if (registerShift && bitSet(opcode, 128)) {
            reject("Invalid register shift");
        }
        var pipelineOffset = registerShift ? 12 : 8;
        var left = state.operand((opcode >>> 16) & 15, pipelineOffset);
        var right = decodeOperand(opcode, registerShift, pipelineOffset);
        var writeFlags = updateFlags && (comparison || destination != CpuState.PROGRAM_COUNTER);
        var result = arithmetic.execute(operation, left, right, writeFlags, shifter.carryOut());

        if (comparison) {
            return;
        }
        if (restoreStatus) {
            state.restoreSavedStatus();
        }
        state.writeRegister(destination, result);
    }

    private int decodeOperand(int opcode, boolean registerShift, int pipelineOffset) {
        if (bitSet(opcode, IMMEDIATE_OPERAND)) {
            var rotation = ((opcode >>> 8) & 15) * 2;
            return shifter.rotateImmediate(opcode & 255, rotation, state.carry());
        }

        var shiftRegister = (opcode >>> 8) & 15;
        if (registerShift && shiftRegister == CpuState.PROGRAM_COUNTER) {
            reject("PC used as shift amount");
        }
        var amount = registerShift ? state.register(shiftRegister) & 255 : (opcode >>> 7) & 31;
        var source = state.operand(opcode & 15, pipelineOffset);
        return shift(source, (opcode >>> 5) & 3, amount, !registerShift);
    }

    private void transferSingle(int opcode) {
        var baseRegister = (opcode >>> 16) & 15;
        var destination = (opcode >>> 12) & 15;
        var preIndexed = bitSet(opcode, PRE_INDEXED);
        var load = bitSet(opcode, LOAD);
        var writeBack = !preIndexed || bitSet(opcode, WRITE_BACK);
        var byteAccess = bitSet(opcode, BYTE_TRANSFER);
        validateTransfer(baseRegister, destination, load, writeBack);
        if (byteAccess && destination == CpuState.PROGRAM_COUNTER) {
            reject("Byte transfer using PC");
        }

        var base = state.operand(baseRegister, 8);
        var offset = decodeTransferOffset(opcode);
        var updatedBase = base + (bitSet(opcode, ADD_OFFSET) ? offset : -offset);
        var address = preIndexed ? updatedBase : base;

        if (load) {
            var value = byteAccess ? memory.read8(address) : loadWord(address);
            state.writeRegister(destination, value);
        } else if (byteAccess) {
            memory.write8(address, state.register(destination));
        } else {
            memory.write32(address, state.operand(destination, 12));
        }
        if (writeBack) {
            state.writeRegister(baseRegister, updatedBase);
        }
    }

    private int decodeTransferOffset(int opcode) {
        if (!bitSet(opcode, IMMEDIATE_OPERAND)) {
            return opcode & 0xfff;
        }
        if (bitSet(opcode, 16)) {
            reject("Undefined register-offset load/store");
        }
        var source = state.operand(opcode & 15, 8);
        return shift(source, (opcode >>> 5) & 3, (opcode >>> 7) & 31, true);
    }

    private void transferHalfword(int opcode) {
        var accessType = (opcode >>> 5) & 3;
        var baseRegister = (opcode >>> 16) & 15;
        var destination = (opcode >>> 12) & 15;
        var load = bitSet(opcode, LOAD);
        var preIndexed = bitSet(opcode, PRE_INDEXED);
        var writeBack = !preIndexed || bitSet(opcode, WRITE_BACK);

        if (accessType == 0 || (!load && accessType != 1)) {
            reject("Unsupported halfword encoding");
        }
        if (destination == CpuState.PROGRAM_COUNTER) {
            reject("Invalid halfword PC operand");
        }
        validateTransfer(baseRegister, destination, load, writeBack);

        var offset = decodeHalfwordOffset(opcode);
        var base = state.operand(baseRegister, 8);
        var updatedBase = base + (bitSet(opcode, ADD_OFFSET) ? offset : -offset);
        var address = preIndexed ? updatedBase : base;

        if (load) {
            state.writeRegister(destination, readHalfwordTransfer(address, accessType));
        } else {
            memory.write16(address, state.register(destination));
        }
        if (writeBack) {
            state.writeRegister(baseRegister, updatedBase);
        }
    }

    private int decodeHalfwordOffset(int opcode) {
        if (bitSet(opcode, 1 << 22)) {
            return ((opcode >>> 4) & 0xf0) | (opcode & 15);
        }
        if ((opcode & 0xf00) != 0 || (opcode & 15) == CpuState.PROGRAM_COUNTER) {
            reject("Invalid halfword register offset");
        }
        return state.register(opcode & 15);
    }

    private int readHalfwordTransfer(int address, int accessType) {
        return switch (accessType) {
            case 1 -> loadHalfword(address);
            case 2 -> (byte) memory.read8(address);
            default -> loadSignedHalfword(address);
        };
    }

    private static void validateTransfer(int base, int destination, boolean load, boolean writeBack) {
        if (base == CpuState.PROGRAM_COUNTER && writeBack) {
            reject("PC base with writeback");
        }
        if (load && writeBack && base == destination) {
            reject("Unpredictable load base/destination overlap");
        }
    }

    private void transferMultiple(int opcode) {
        var baseRegister = (opcode >>> 16) & 15;
        var registerList = opcode & 0xffff;
        var load = bitSet(opcode, LOAD);
        var writeBack = bitSet(opcode, WRITE_BACK);
        var includesPc = bitSet(registerList, 1 << CpuState.PROGRAM_COUNTER);
        var specialTransfer = bitSet(opcode, 1 << 22);
        var restoreStatus = specialTransfer && load && includesPc;
        var userBank = specialTransfer && !restoreStatus;
        validateBlockTransfer(baseRegister, registerList, userBank, writeBack, restoreStatus);

        var count = registerCount(registerList);
        var base = state.register(baseRegister);
        var increment = bitSet(opcode, ADD_OFFSET);
        var preIndexed = bitSet(opcode, PRE_INDEXED);
        var updatedBase = base + (increment ? count * 4 : -count * 4);
        var address = increment ? base + (preIndexed ? 4 : 0) : base - count * 4 + (preIndexed ? 0 : 4);
        var first = firstRegister(registerList);
        var loadedPc = 0;

        for (var register = 0; register < 16; register++) {
            if (!bitSet(registerList, 1 << register)) {
                continue;
            }
            if (load) {
                var value = memory.read32(address & ~3);
                if (register == CpuState.PROGRAM_COUNTER) {
                    loadedPc = value;
                } else if (userBank) {
                    state.writeUserRegister(register, value);
                } else {
                    state.writeRegister(register, value);
                }
            } else {
                var value = userBank ? state.userRegister(register) : state.operand(register, 12);
                if (writeBack && register == baseRegister && register != first) {
                    value = updatedBase;
                }
                memory.write32(address, value);
            }
            address += 4;
        }

        var baseWasLoaded = load && bitSet(registerList, 1 << baseRegister);
        if (writeBack && !baseWasLoaded) {
            state.writeRegister(baseRegister, updatedBase);
        }
        if (restoreStatus) {
            state.restoreSavedStatus();
        }
        if (load && includesPc) {
            state.writeRegister(CpuState.PROGRAM_COUNTER, loadedPc);
        }
    }

    private void validateBlockTransfer(int base, int registerList, boolean userBank,
            boolean writeBack, boolean restoreStatus) {
        if (registerList == 0) {
            reject("Empty ARM register list is not implemented");
        }
        if (base == CpuState.PROGRAM_COUNTER) {
            reject("LDM/STM PC base");
        }
        if (restoreStatus) {
            state.requireSavedStatus();
        }
        if (userBank && writeBack) {
            reject("User-bank transfer with writeback");
        }
    }

    private void multiply(int opcode) {
        var destination = (opcode >>> 16) & 15;
        var accumulator = (opcode >>> 12) & 15;
        var multiplier = (opcode >>> 8) & 15;
        var multiplicand = opcode & 15;
        var accumulate = bitSet(opcode, 1 << 21);
        if (destination == 15 || multiplier == 15 || multiplicand == 15 || (accumulate && accumulator == 15)) {
            reject("MUL with PC");
        }
        if (!accumulate && accumulator != 0) {
            reject("Invalid MUL encoding");
        }

        var product = state.register(multiplicand) * state.register(multiplier);
        var result = product + (accumulate ? state.register(accumulator) : 0);
        state.writeRegister(destination, result);
        if (bitSet(opcode, UPDATE_FLAGS)) {
            state.updateSignAndZero(result);
        }
        // C is architecturally unpredictable on ARMv4 MUL; preserve its value.
    }

    private void multiplyLong(int opcode) {
        var highRegister = (opcode >>> 16) & 15;
        var lowRegister = (opcode >>> 12) & 15;
        var multiplier = (opcode >>> 8) & 15;
        var multiplicand = opcode & 15;
        if (highRegister == lowRegister || highRegister == 15 || lowRegister == 15
                || multiplier == 15 || multiplicand == 15) {
            reject("Invalid long multiply registers");
        }

        var left = state.register(multiplicand);
        var right = state.register(multiplier);
        var result = bitSet(opcode, 1 << 22)
                ? (long) left * right
                : (left & 0xffffffffL) * (right & 0xffffffffL);
        if (bitSet(opcode, 1 << 21)) {
            result += ((long) state.register(highRegister) << 32) | (state.register(lowRegister) & 0xffffffffL);
        }
        state.writeRegister(lowRegister, (int) result);
        state.writeRegister(highRegister, (int) (result >>> 32));
        if (bitSet(opcode, UPDATE_FLAGS)) {
            state.setFlag(Cpu.N, result < 0);
            state.setFlag(Cpu.Z, result == 0);
        }
    }

    private void swapMemory(int opcode) {
        var baseRegister = (opcode >>> 16) & 15;
        var destination = (opcode >>> 12) & 15;
        var source = opcode & 15;
        if (baseRegister == 15 || destination == 15 || source == 15) {
            reject("SWP with PC");
        }

        var address = state.register(baseRegister);
        var value = state.register(source);
        var byteAccess = bitSet(opcode, BYTE_TRANSFER);
        var previous = byteAccess ? memory.read8(address) : loadWord(address);
        if (byteAccess) {
            memory.write8(address, value);
        } else {
            memory.write32(address, value);
        }
        state.writeRegister(destination, previous);
    }

    private void readStatus(int opcode) {
        var destination = (opcode >>> 12) & 15;
        if (destination == CpuState.PROGRAM_COUNTER) {
            reject("MRS with PC destination");
        }
        var value = bitSet(opcode, SAVED_STATUS) ? state.savedStatus() : state.status();
        state.writeRegister(destination, value);
    }

    private void writeStatus(int opcode) {
        var value = bitSet(opcode, IMMEDIATE_OPERAND)
                ? BarrelShifter.rotateRight(opcode & 255, ((opcode >>> 8) & 15) * 2)
                : state.operand(opcode & 15, 8);
        var fields = (opcode >>> 16) & 15;
        var mask = (bitSet(fields, 8) ? 0xf0000000 : 0) | (bitSet(fields, 1) ? 0xff : 0);

        if (bitSet(opcode, SAVED_STATUS)) {
            var updatedStatus = (state.savedStatus() & ~mask) | (value & mask);
            // SPSR is storage: BIOS reset code may write a temporary invalid mode.
            // Validate only when it is restored into the live CPSR.
            state.writeSavedStatus(updatedStatus);
            return;
        }

        if (state.mode() == Cpu.USER) {
            mask &= 0xf0000000;
        }
        if (bitSet(mask, Cpu.T) && bitSet(value ^ state.status(), Cpu.T)) {
            reject("MSR changing T; use BX");
        }
        var updatedStatus = (state.status() & ~mask) | (value & mask);
        validateStatusMode(updatedStatus);
        state.writeStatus(updatedStatus);
    }

    private static void validateStatusMode(int status) {
        if (!CpuState.validMode(status & 31)) {
            reject("Invalid status register mode");
        }
    }
}

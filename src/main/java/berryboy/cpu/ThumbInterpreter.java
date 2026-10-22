package berryboy.cpu;

import berryboy.bus.Memory;

/** Decodes and executes the 16-bit Thumb instruction set of ARM7TDMI. */
final class ThumbInterpreter extends InstructionInterpreter {
    private static final int LOAD = 0x0800;
    private static final int INCLUDE_LINK_OR_PC = 0x0100;

    ThumbInterpreter(CpuState state, Memory memory) {
        super(state, memory);
    }

    void execute(int opcode) {
        if (matches(opcode, 0xf800, 0x1800)) {
            addOrSubtract(opcode);
        } else if (matches(opcode, 0xe000, 0x0000)) {
            shiftImmediate(opcode);
        } else if (matches(opcode, 0xe000, 0x2000)) {
            processImmediate(opcode);
        } else if (matches(opcode, 0xfc00, 0x4000)) {
            processRegisters(opcode);
        } else if (matches(opcode, 0xfc00, 0x4400)) {
            processHighRegisters(opcode);
        } else if (matches(opcode, 0xf800, 0x4800)) {
            loadLiteral(opcode);
        } else if (matches(opcode, 0xf000, 0x5000)) {
            transferRegisterOffset(opcode);
        } else if (matches(opcode, 0xe000, 0x6000)) {
            transferImmediateOffset(opcode);
        } else if (matches(opcode, 0xf000, 0x8000)) {
            transferHalfword(opcode);
        } else if (matches(opcode, 0xf000, 0x9000)) {
            transferStackRelative(opcode);
        } else if (matches(opcode, 0xf000, 0xa000)) {
            calculateAddress(opcode);
        } else if (matches(opcode, 0xff00, 0xb000)) {
            adjustStackPointer(opcode);
        } else if (matches(opcode, 0xf600, 0xb400)) {
            transferStack(opcode);
        } else if (matches(opcode, 0xf000, 0xc000)) {
            transferMultiple(opcode);
        } else if (matches(opcode, 0xff00, 0xdf00)) {
            softwareInterrupt();
        } else if (matches(opcode, 0xf000, 0xd000)) {
            branchConditional(opcode);
        } else if (matches(opcode, 0xf800, 0xe000)) {
            branch(opcode);
        } else if (matches(opcode, 0xf800, 0xf000)) {
            prepareBranchWithLink(opcode);
        } else if (matches(opcode, 0xf800, 0xf800)) {
            completeBranchWithLink(opcode);
        } else {
            reject("Unsupported Thumb instruction (including ARMv5 BLX/BKPT)");
        }
    }

    private void addOrSubtract(int opcode) {
        var destination = opcode & 7;
        var source = (opcode >>> 3) & 7;
        var operand = (opcode >>> 6) & 7;
        var right = bitSet(opcode, 0x0400) ? operand : state.register(operand);
        var operation = bitSet(opcode, 0x0200) ? ArithmeticLogicUnit.SUB : ArithmeticLogicUnit.ADD;
        var result = arithmetic.execute(operation, state.register(source), right, true, state.carry());
        state.writeRegister(destination, result);
    }

    private void shiftImmediate(int opcode) {
        var destination = opcode & 7;
        var source = (opcode >>> 3) & 7;
        var shiftType = (opcode >>> 11) & 3;
        var amount = (opcode >>> 6) & 31;
        var result = shift(state.register(source), shiftType, amount, true);
        state.writeRegister(destination, result);
        updateShiftFlags(result);
    }

    private void processImmediate(int opcode) {
        var operation = (opcode >>> 11) & 3;
        var destination = (opcode >>> 8) & 7;
        var immediate = opcode & 0xff;
        var current = state.register(destination);

        switch (operation) {
            case 0: // MOV
                state.writeRegister(destination, immediate);
                state.updateSignAndZero(immediate);
                break;
            case 1: // CMP
                arithmetic.addWithCarry(current, ~immediate, 1, true);
                break;
            case 2: // ADD
                state.writeRegister(destination, arithmetic.addWithCarry(current, immediate, 0, true));
                break;
            default: // SUB
                state.writeRegister(destination, arithmetic.addWithCarry(current, ~immediate, 1, true));
                break;
        }
    }

    private void processRegisters(int opcode) {
        var operation = (opcode >>> 6) & 15;
        var destination = opcode & 7;
        var source = (opcode >>> 3) & 7;
        var left = state.register(destination);
        var right = state.register(source);
        int result;

        switch (operation) {
            case 0: // AND
                result = logical(ArithmeticLogicUnit.AND, left, right);
                break;
            case 1: // EOR
                result = logical(ArithmeticLogicUnit.EOR, left, right);
                break;
            case 2: // LSL
            case 3: // LSR
            case 4: // ASR
            case 7: // ROR
                var shiftType = operation == 7 ? BarrelShifter.ROTATE_RIGHT : operation - 2;
                result = shift(left, shiftType, right & 0xff, false);
                updateShiftFlags(result);
                break;
            case 5: // ADC
                result = arithmetic.addWithCarry(left, right, state.carry() ? 1 : 0, true);
                break;
            case 6: // SBC
                result = arithmetic.addWithCarry(left, ~right, state.carry() ? 1 : 0, true);
                break;
            case 8: // TST
                logical(ArithmeticLogicUnit.TST, left, right);
                return;
            case 9: // NEG
                result = arithmetic.addWithCarry(0, ~right, 1, true);
                break;
            case 10: // CMP
                arithmetic.addWithCarry(left, ~right, 1, true);
                return;
            case 11: // CMN
                arithmetic.addWithCarry(left, right, 0, true);
                return;
            case 12: // ORR
                result = logical(ArithmeticLogicUnit.ORR, left, right);
                break;
            case 13: // MUL
                result = left * right;
                state.updateSignAndZero(result);
                break;
            case 14: // BIC
                result = logical(ArithmeticLogicUnit.BIC, left, right);
                break;
            default: // MVN
                result = logical(ArithmeticLogicUnit.MVN, left, right);
                break;
        }

        state.writeRegister(destination, result);
    }

    private int logical(int operation, int left, int right) {
        return arithmetic.execute(operation, left, right, true, state.carry());
    }

    private void updateShiftFlags(int result) {
        state.updateSignAndZero(result);
        state.setFlag(Cpu.C, shifter.carryOut());
    }

    private void processHighRegisters(int opcode) {
        var operation = (opcode >>> 8) & 3;
        var destination = (opcode & 7) | ((opcode >>> 4) & 8);
        var source = (opcode >>> 3) & 15;
        var right = state.operand(source, 4);

        switch (operation) {
            case 0: // ADD without flag updates
                state.writeRegister(destination, state.operand(destination, 4) + right);
                break;
            case 1: // CMP
                arithmetic.addWithCarry(state.operand(destination, 4), ~right, 1, true);
                break;
            case 2: // MOV without flag updates
                state.writeRegister(destination, right);
                break;
            default: // BX
                if (bitSet(opcode, 0x0087)) {
                    reject("Reserved Thumb BX / ARMv5 BLX");
                }
                branchExchange(right);
                break;
        }
    }

    private void loadLiteral(int opcode) {
        var destination = (opcode >>> 8) & 7;
        var base = (state.instructionAddress() + 4) & ~3;
        var offset = (opcode & 0xff) << 2;
        state.writeRegister(destination, loadWord(base + offset));
    }

    private void transferRegisterOffset(int opcode) {
        var destination = opcode & 7;
        var baseRegister = (opcode >>> 3) & 7;
        var offsetRegister = (opcode >>> 6) & 7;
        var address = state.register(baseRegister) + state.register(offsetRegister);

        switch ((opcode >>> 9) & 7) {
            case 0: // STR
                memory.write32(address, state.register(destination));
                break;
            case 1: // STRH
                memory.write16(address, state.register(destination));
                break;
            case 2: // STRB
                memory.write8(address, state.register(destination));
                break;
            case 3: // LDSB
                state.writeRegister(destination, (byte) memory.read8(address));
                break;
            case 4: // LDR
                state.writeRegister(destination, loadWord(address));
                break;
            case 5: // LDRH
                state.writeRegister(destination, loadHalfword(address));
                break;
            case 6: // LDRB
                state.writeRegister(destination, memory.read8(address));
                break;
            default: // LDSH
                state.writeRegister(destination, loadSignedHalfword(address));
                break;
        }
    }

    private void transferImmediateOffset(int opcode) {
        var destination = opcode & 7;
        var baseRegister = (opcode >>> 3) & 7;
        var byteTransfer = bitSet(opcode, 0x1000);
        var offset = ((opcode >>> 6) & 31) << (byteTransfer ? 0 : 2);
        var address = state.register(baseRegister) + offset;

        if (bitSet(opcode, LOAD)) {
            var value = byteTransfer ? memory.read8(address) : loadWord(address);
            state.writeRegister(destination, value);
        } else if (byteTransfer) {
            memory.write8(address, state.register(destination));
        } else {
            memory.write32(address, state.register(destination));
        }
    }

    private void transferHalfword(int opcode) {
        var destination = opcode & 7;
        var baseRegister = (opcode >>> 3) & 7;
        var offset = ((opcode >>> 6) & 31) << 1;
        var address = state.register(baseRegister) + offset;

        if (bitSet(opcode, LOAD)) {
            state.writeRegister(destination, loadHalfword(address));
        } else {
            memory.write16(address, state.register(destination));
        }
    }

    private void transferStackRelative(int opcode) {
        var destination = (opcode >>> 8) & 7;
        var offset = (opcode & 0xff) << 2;
        var address = state.register(CpuState.STACK_POINTER) + offset;

        if (bitSet(opcode, LOAD)) {
            state.writeRegister(destination, loadWord(address));
        } else {
            memory.write32(address, state.register(destination));
        }
    }

    private void calculateAddress(int opcode) {
        var destination = (opcode >>> 8) & 7;
        var base = bitSet(opcode, 0x0800)
                ? state.register(CpuState.STACK_POINTER)
                : (state.instructionAddress() + 4) & ~3;
        var offset = (opcode & 0xff) << 2;
        state.writeRegister(destination, base + offset);
    }

    private void adjustStackPointer(int opcode) {
        var offset = (opcode & 0x7f) << 2;
        var delta = bitSet(opcode, 0x0080) ? -offset : offset;
        var stackPointer = state.register(CpuState.STACK_POINTER);
        state.writeRegister(CpuState.STACK_POINTER, stackPointer + delta);
    }

    private void transferStack(int opcode) {
        var registerList = opcode & 0xff;
        var pop = bitSet(opcode, LOAD);
        var includeLinkOrPc = bitSet(opcode, INCLUDE_LINK_OR_PC);
        var count = registerCount(registerList) + (includeLinkOrPc ? 1 : 0);
        if (count == 0) {
            reject("Empty Thumb PUSH/POP list is not implemented");
        }

        var stackPointer = state.register(CpuState.STACK_POINTER);
        var address = pop ? stackPointer : stackPointer - count * 4;
        if (!pop) {
            state.writeRegister(CpuState.STACK_POINTER, address);
        }

        for (var register = 0; register < 8; register++) {
            if (!bitSet(registerList, 1 << register)) {
                continue;
            }
            if (pop) {
                state.writeRegister(register, memory.read32(address & ~3));
            } else {
                memory.write32(address, state.register(register));
            }
            address += 4;
        }

        if (includeLinkOrPc) {
            if (pop) {
                // ARMv4 POP writes PC without changing the Thumb state.
                state.writeRegister(CpuState.PROGRAM_COUNTER, memory.read32(address & ~3));
            } else {
                memory.write32(address, state.register(CpuState.LINK_REGISTER));
            }
            address += 4;
        }
        if (pop) {
            state.writeRegister(CpuState.STACK_POINTER, address);
        }
    }

    private void transferMultiple(int opcode) {
        var baseRegister = (opcode >>> 8) & 7;
        var registerList = opcode & 0xff;
        var load = bitSet(opcode, LOAD);
        if (registerList == 0) {
            reject("Empty Thumb register list is not implemented");
        }

        var address = state.register(baseRegister);
        var updatedBase = address + registerCount(registerList) * 4;
        var first = firstRegister(registerList);

        for (var register = 0; register < 8; register++) {
            if (!bitSet(registerList, 1 << register)) {
                continue;
            }
            if (load) {
                state.writeRegister(register, memory.read32(address & ~3));
            } else {
                var value = register == baseRegister && register != first
                        ? updatedBase
                        : state.register(register);
                memory.write32(address, value);
            }
            address += 4;
        }

        // A loaded base register keeps the value read from memory.
        if (!(load && bitSet(registerList, 1 << baseRegister))) {
            state.writeRegister(baseRegister, updatedBase);
        }
    }

    private void branchConditional(int opcode) {
        var condition = (opcode >>> 8) & 15;
        if (condition == 14) {
            reject("Undefined Thumb condition");
        }
        if (state.conditionPassed(condition)) {
            var displacement = (byte) opcode << 1;
            state.writeRegister(CpuState.PROGRAM_COUNTER, state.instructionAddress() + 4 + displacement);
        }
    }

    private void branch(int opcode) {
        var displacement = (opcode & 0x7ff) << 21 >> 20;
        state.writeRegister(CpuState.PROGRAM_COUNTER, state.instructionAddress() + 4 + displacement);
    }

    private void prepareBranchWithLink(int opcode) {
        var displacement = (opcode & 0x7ff) << 21 >> 9;
        state.writeRegister(CpuState.LINK_REGISTER, state.instructionAddress() + 4 + displacement);
    }

    private void completeBranchWithLink(int opcode) {
        var target = state.register(CpuState.LINK_REGISTER) + ((opcode & 0x7ff) << 1);
        var returnAddress = (state.instructionAddress() + 2) | 1;
        state.writeRegister(CpuState.LINK_REGISTER, returnAddress);
        state.writeRegister(CpuState.PROGRAM_COUNTER, target);
    }
}

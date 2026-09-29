package berryboy;

/** Arithmetic and logical results together with their architectural NZCV flags. */
final class ArithmeticLogicUnit {
    static final int AND = 0;
    static final int EOR = 1;
    static final int SUB = 2;
    static final int RSB = 3;
    static final int ADD = 4;
    static final int ADC = 5;
    static final int SBC = 6;
    static final int RSC = 7;
    static final int TST = 8;
    static final int TEQ = 9;
    static final int CMP = 10;
    static final int CMN = 11;
    static final int ORR = 12;
    static final int MOV = 13;
    static final int BIC = 14;
    static final int MVN = 15;

    private final CpuState state;

    ArithmeticLogicUnit(CpuState state) {
        this.state = state;
    }

    int execute(int operation, int left, int right, boolean updateFlags, boolean operandCarry) {
        return switch (operation) {
            case SUB, CMP -> addWithCarry(left, ~right, 1, updateFlags);
            case RSB -> addWithCarry(right, ~left, 1, updateFlags);
            case ADD, CMN -> addWithCarry(left, right, 0, updateFlags);
            case ADC -> addWithCarry(left, right, carryIn(), updateFlags);
            case SBC -> addWithCarry(left, ~right, carryIn(), updateFlags);
            case RSC -> addWithCarry(right, ~left, carryIn(), updateFlags);
            default -> logical(operation, left, right, updateFlags, operandCarry);
        };
    }

    /** Keep the carry input separate so ADC and SBC cannot lose a 33rd bit. */
    int addWithCarry(int left, int right, int carryIn, boolean updateFlags) {
        var result = left + right + carryIn;
        if (updateFlags) {
            var unsignedSum = (left & 0xffffffffL) + (right & 0xffffffffL) + carryIn;
            var signedSum = (long) left + right + carryIn;

            state.updateSignAndZero(result);
            state.setFlag(Cpu.C, unsignedSum > 0xffffffffL);
            state.setFlag(Cpu.V, signedSum > 0x7fffffffL || signedSum < -2147483648L);
        }
        return result;
    }

    private int logical(int operation, int left, int right, boolean updateFlags, boolean operandCarry) {
        var result = logicalResult(operation, left, right);
        if (updateFlags) {
            state.updateSignAndZero(result);
            state.setFlag(Cpu.C, operandCarry);
        }
        return result;
    }

    private static int logicalResult(int operation, int left, int right) {
        return switch (operation) {
            case AND, TST -> left & right;
            case EOR, TEQ -> left ^ right;
            case ORR -> left | right;
            case MOV -> right;
            case BIC -> left & ~right;
            default -> ~right;
        };
    }

    private int carryIn() {
        return state.carry() ? 1 : 0;
    }
}

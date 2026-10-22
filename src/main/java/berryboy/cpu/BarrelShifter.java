package berryboy.cpu;

/** ARM shifts produce a value and carry-out without allocating a result object. */
final class BarrelShifter {
    static final int LOGICAL_LEFT = 0;
    static final int LOGICAL_RIGHT = 1;
    static final int ARITHMETIC_RIGHT = 2;
    static final int ROTATE_RIGHT = 3;

    private boolean carryOut;

    boolean carryOut() {
        return carryOut;
    }

    int rotateImmediate(int value, int distance, boolean carryIn) {
        var result = rotateRight(value, distance);
        carryOut = distance == 0 ? carryIn : result < 0;
        return result;
    }

    int shift(int value, int type, int amount, boolean immediate, boolean carryIn) {
        carryOut = carryIn;
        if (immediate && amount == 0) {
            if (type == LOGICAL_LEFT) {
                return value;
            }
            if (type == ROTATE_RIGHT) {
                return rotateThroughCarry(value, carryIn);
            }
            amount = 32;
        }
        if (amount == 0) {
            return value;
        }

        return switch (type) {
            case LOGICAL_LEFT -> logicalLeft(value, amount);
            case LOGICAL_RIGHT -> logicalRight(value, amount);
            case ARITHMETIC_RIGHT -> arithmeticRight(value, amount);
            default -> rotate(value, amount);
        };
    }

    static int rotateRight(int value, int distance) {
        return distance == 0 ? value : (value >>> distance) | (value << (32 - distance));
    }

    private int rotateThroughCarry(int value, boolean carryIn) {
        carryOut = (value & 1) != 0;
        return (carryIn ? Cpu.N : 0) | (value >>> 1);
    }

    private int logicalLeft(int value, int amount) {
        carryOut = amount <= 32 && ((value >>> (32 - amount)) & 1) != 0;
        return amount >= 32 ? 0 : value << amount;
    }

    private int logicalRight(int value, int amount) {
        carryOut = amount <= 32 && ((value >>> (amount - 1)) & 1) != 0;
        return amount >= 32 ? 0 : value >>> amount;
    }

    private int arithmeticRight(int value, int amount) {
        carryOut = amount >= 32 ? value < 0 : ((value >>> (amount - 1)) & 1) != 0;
        return value >> (amount >= 32 ? 31 : amount);
    }

    private int rotate(int value, int amount) {
        var result = rotateRight(value, amount & 31);
        carryOut = result < 0;
        return result;
    }
}

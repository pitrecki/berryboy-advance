package berryboy.cpu;

import berryboy.state.StateBuffer;
import berryboy.EmulationException;
import berryboy.bus.Memory;

/** Fixed, reusable data-operation sequence for one decoded instruction. */
final class CpuDataTransfers {
    static final int BYTE = 1;
    static final int HALFWORD = 2;
    static final int WORD = 4;
    static final int SIGNED_BYTE = 5;
    static final int SIGNED_HALFWORD = 6;
    static final int ALIGNED_WORD = 7;
    private final CpuState state;
    private final Memory memory;
    private final int[] kinds = new int[16];
    private final int[] addresses = new int[16];
    private final int[] registers = new int[16];
    private final int[] values = new int[16];
    private final boolean[] userBank = new boolean[16];
    private int count;
    private int next;
    private int writeBackRegister = -1;
    private int writeBackValue;
    private boolean restoreStatus;
    private boolean pcLoaded;
    private int loadedPc;
    private boolean locked;
    private int swapRegister = -1;
    private int swapValue;

    CpuDataTransfers(CpuState state, Memory memory) {
        this.state = state;
        this.memory = memory;
    }

    void reset() {
        count = 0;
        next = 0;
        writeBackRegister = -1;
        restoreStatus = false;
        pcLoaded = false;
        locked = false;
        swapRegister = -1;
    }

    void load(int kind, int address, int register) { load(kind, address, register, false); }

    void load(int kind, int address, int register, boolean user) {
        append(kind, address, register, 0, user);
    }

    void store(int width, int address, int value) { append(-width, address, -1, value, false); }

    void writeBack(int register, int value) {
        writeBackRegister = register;
        writeBackValue = value;
    }

    void restoreStatus() { restoreStatus = true; }

    void swap(int width, int address, int value, int destination) {
        locked = true;
        swapRegister = destination;
        load(width, address, -1);
        store(width, address, value);
    }

    boolean hasNext() { return next < count; }

    /** SWP holds the bus only after its read, until the paired write completes. */
    boolean busLocked() { return locked && next != 0 && hasNext(); }

    void transferNext() {
        var kind = kinds[next];
        var address = addresses[next];
        if (kind < 0) {
            if (kind == -BYTE) memory.write8(address, values[next]);
            else if (kind == -HALFWORD) memory.write16(address, values[next]);
            else memory.write32(address, values[next]);
        } else {
            var value = read(kind, address);
            var register = registers[next];
            if (register < 0) swapValue = value;
            else if (register == CpuState.PROGRAM_COUNTER) { loadedPc = value; pcLoaded = true; }
            else if (userBank[next]) state.writeUserRegister(register, value);
            else state.writeRegister(register, value);
        }
        next++;
    }

    void finish() {
        if (writeBackRegister >= 0) state.writeRegister(writeBackRegister, writeBackValue);
        if (restoreStatus) state.restoreSavedStatus();
        if (pcLoaded) state.writeRegister(CpuState.PROGRAM_COUNTER, loadedPc);
        if (swapRegister >= 0) state.writeRegister(swapRegister, swapValue);
    }

    private int read(int kind, int address) {
        if (kind == BYTE) return memory.read8(address);
        if (kind == SIGNED_BYTE) return (byte) memory.read8(address);
        if (kind == SIGNED_HALFWORD) return (address & 1) != 0
                ? (byte) memory.read8(address) : (short) memory.read16(address);
        if (kind == ALIGNED_WORD) return memory.read32(address & ~3);
        var backup = (address >>> 24) == 14 || (address >>> 24) == 15;
        if (kind == HALFWORD) return backup ? memory.read16(address)
                : BarrelShifter.rotateRight(memory.read16(address & ~1), (address & 1) * 8);
        return backup ? memory.read32(address)
                : BarrelShifter.rotateRight(memory.read32(address & ~3), (address & 3) * 8);
    }

    private void append(int kind, int address, int register, int value, boolean user) {
        if (count == kinds.length) throw new EmulationException("CPU transfer sequence exceeds 16 accesses");
        kinds[count] = kind;
        addresses[count] = address;
        registers[count] = register;
        values[count] = value;
        userBank[count++] = user;
    }

    /** Device-owned fields for versioned machine state; call only between CPU steps. */
    public void transferState(StateBuffer state) {
        state.ints(kinds);
        state.ints(addresses);
        state.ints(registers);
        state.ints(values);
        count = state.value(count);
        next = state.value(next);
        writeBackRegister = state.value(writeBackRegister);
        writeBackValue = state.value(writeBackValue);
        loadedPc = state.value(loadedPc);
        swapRegister = state.value(swapRegister);
        swapValue = state.value(swapValue);
        restoreStatus = state.flag(restoreStatus);
        pcLoaded = state.flag(pcLoaded);
        locked = state.flag(locked);
        state.flags(userBank);
    }
}

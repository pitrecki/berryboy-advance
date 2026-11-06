package berryboy;

import berryboy.state.StateBuffer;
import berryboy.state.StateChecksum;
import berryboy.cartridge.CartridgeBackup;

/** Versioned complete machine state between CPU steps, including unfinished work. */
public final class MachineState {
    private static final int MAGIC = 0x53594242; // BBYS, little endian.
    private static final int VERSION = 1;
    private MachineState() { }

    /** Call on the emulation owner; guest cycles and scheduled event order do not change. */
    public static byte[] save(Machine machine) {
        var state = new StateBuffer();
        state.value(MAGIC); state.value(VERSION);
        state.value(machine.cartridge.size());
        state.value(machine.cartridge.checksum());
        state.flag(machine.memory.hasBios()); state.value(machine.memory.biosChecksum());
        state.value(machine.memory.backup().type());
        machine.memory.transferState(state);
        machine.cpu.transferState(state);
        var payload = state.finish();
        state.value(StateChecksum.bytes(payload, payload.length));
        return state.finish();
    }

    /** Validate into a new machine. Failed loads cannot partially mutate the running session. */
    public static Machine load(byte[] rom, byte[] bios, byte[] image) {
        if (image == null || image.length < 29 || image.length > StateBuffer.MAX_BYTES) {
            throw new IllegalArgumentException("Invalid state size");
        }
        var data = new byte[image.length];
        System.arraycopy(image, 0, data, 0, data.length);
        var last = data.length - 4;
        var checksum = (data[last] & 255) | ((data[last + 1] & 255) << 8)
                | ((data[last + 2] & 255) << 16) | ((data[last + 3] & 255) << 24);
        if (checksum != StateChecksum.bytes(data, last)) throw new IllegalArgumentException("State checksum mismatch");
        var state = new StateBuffer(data);
        if (state.value(0) != MAGIC || state.value(0) != VERSION) throw new IllegalArgumentException("Unsupported state format/version");
        var machine = new Machine(rom, bios);
        if (state.value(0) != machine.cartridge.size() || state.value(0) != machine.cartridge.checksum()) {
            throw new IllegalArgumentException("State belongs to another ROM");
        }
        if (state.flag(false) != machine.memory.hasBios() || state.value(0) != machine.memory.biosChecksum()) {
            throw new IllegalArgumentException("State belongs to another BIOS");
        }
        machine.memory.setBackup(new CartridgeBackup(state.value(0)));
        machine.memory.transferState(state);
        machine.cpu.transferState(state);
        state.value(0); // CRC already verified before constructing the replacement.
        state.requireEnd();
        return machine;
    }
}

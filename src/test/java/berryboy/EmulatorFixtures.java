package berryboy;

import berryboy.bus.Memory;
import berryboy.cpu.Cpu;

/** Program images and hardware setup shared by the emulator tests. */
public final class EmulatorFixtures {
    public static final int STACK_TOP = 0x03007f00;
    private static final int ROM_SIZE = 512;

    private EmulatorFixtures() {
    }

    public static byte[] blankRom() {
        return new byte[ROM_SIZE];
    }

    public static Machine arm(int... instructions) {
        var image = blankRom();
        for (var index = 0; index < instructions.length; index++) {
            writeWord(image, index * 4, instructions[index]);
        }
        return new Machine(image);
    }

    public static Machine thumb(int... instructions) {
        var image = blankRom();
        for (var index = 0; index < instructions.length; index++) {
            writeHalfword(image, index * 2, instructions[index]);
        }
        var machine = new Machine(image);
        machine.cpu.setCpsr(Cpu.SYSTEM | Cpu.T);
        return machine;
    }

    public static Machine bitmap(int displayControl) {
        var machine = new Machine(blankRom());
        machine.memory.write16(Memory.DISPLAY_CONTROL, displayControl);
        machine.memory.write16(Memory.BACKGROUND_2_TRANSFORM, 0x100);
        machine.memory.write16(Memory.BACKGROUND_2_TRANSFORM + 6, 0x100);
        return machine;
    }

    public static void writeWord(byte[] image, int offset, int value) {
        writeHalfword(image, offset, value);
        writeHalfword(image, offset + 2, value >>> 16);
    }

    public static void writeHalfword(byte[] image, int offset, int value) {
        image[offset] = (byte) value;
        image[offset + 1] = (byte) (value >>> 8);
    }
}

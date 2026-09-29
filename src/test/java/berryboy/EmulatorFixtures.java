package berryboy;

/** Program images and hardware setup shared by the emulator tests. */
final class EmulatorFixtures {
    static final int STACK_TOP = 0x03007f00;
    private static final int ROM_SIZE = 512;

    private EmulatorFixtures() {
    }

    static byte[] blankRom() {
        return new byte[ROM_SIZE];
    }

    static Machine arm(int... instructions) {
        var image = blankRom();
        for (var index = 0; index < instructions.length; index++) {
            writeWord(image, index * 4, instructions[index]);
        }
        return new Machine(image);
    }

    static Machine thumb(int... instructions) {
        var image = blankRom();
        for (var index = 0; index < instructions.length; index++) {
            writeHalfword(image, index * 2, instructions[index]);
        }
        var machine = new Machine(image);
        machine.cpu.setCpsr(Cpu.SYSTEM | Cpu.T);
        return machine;
    }

    static Machine bitmap(int displayControl) {
        var machine = new Machine(blankRom());
        machine.memory.write16(Memory.DISPLAY_CONTROL, displayControl);
        machine.memory.write16(Memory.BACKGROUND_2_TRANSFORM, 0x100);
        machine.memory.write16(Memory.BACKGROUND_2_TRANSFORM + 6, 0x100);
        return machine;
    }

    static void writeWord(byte[] image, int offset, int value) {
        writeHalfword(image, offset, value);
        writeHalfword(image, offset + 2, value >>> 16);
    }

    static void writeHalfword(byte[] image, int offset, int value) {
        image[offset] = (byte) value;
        image[offset + 1] = (byte) (value >>> 8);
    }
}

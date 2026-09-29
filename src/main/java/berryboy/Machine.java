package berryboy;

/** Owns the components of one emulator instance. */
public final class Machine {
    private static final int DEVELOPMENT_STACK_TOP = 0x03007f00;

    public final Cartridge cartridge;
    public final Memory memory;
    public final Cpu cpu;
    public final BitmapRenderer video;
    public final AudioUnit audio;

    /** Development cartridge entry, not a replacement for the real BIOS boot. */
    public Machine(byte[] image) {
        cartridge = new Cartridge(image);
        memory = new Memory(cartridge);
        cpu = new Cpu(memory);
        video = new BitmapRenderer(memory);
        audio = memory.audio();
        cpu.setPc(Memory.ROM_BASE);
        cpu.setRegister(CpuState.STACK_POINTER, DEVELOPMENT_STACK_TOP);
    }

    public void runInstructions(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative instruction budget");
        }
        for (var instruction = 0; instruction < count; instruction++) {
            cpu.step();
        }
    }
}

package berryboy;

import berryboy.audio.AudioUnit;
import berryboy.bus.Memory;
import berryboy.cartridge.Cartridge;
import berryboy.cpu.Cpu;
import berryboy.timing.CycleScheduler;
import berryboy.video.BitmapRenderer;

/** Owns the components of one emulator instance. */
public final class Machine {
    private static final int DEVELOPMENT_STACK_TOP = 0x03007f00;

    public final Cartridge cartridge;
    public final Memory memory;
    public final Cpu cpu;
    public final BitmapRenderer video;
    public final AudioUnit audio;
    public final CycleScheduler clock;

    /** Development cartridge entry, not a replacement for the real BIOS boot. */
    public Machine(byte[] image) {
        this(image, null);
    }

    /** With a supplied 16 KiB BIOS, start at the ARM reset vector in SVC mode. */
    public Machine(byte[] image, byte[] bios) {
        cartridge = new Cartridge(image);
        memory = new Memory(cartridge);
        cpu = new Cpu(memory);
        video = new BitmapRenderer(memory);
        audio = memory.audio();
        clock = memory.clock();
        if (bios == null) {
            cpu.setPc(Memory.ROM_BASE);
            cpu.setRegister(Cpu.STACK_POINTER, DEVELOPMENT_STACK_TOP);
        } else {
            memory.setBios(bios);
            cpu.setCpsr(0xd3); // Reset: ARM, Supervisor, IRQ/FIQ masked.
            cpu.setPc(0);
        }
    }

    public void runInstructions(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative instruction budget");
        }
        for (var instruction = 0; instruction < count; instruction++) {
            cpu.step();
        }
    }

    /** Run whole instructions/events; the final instruction may cross the budget. */
    public void runCycles(long cycles) {
        if (cycles < 0 || cycles > Long.MAX_VALUE - clock.now()) {
            throw new IllegalArgumentException("Invalid cycle budget");
        }
        var target = clock.now() + cycles;
        while (clock.now() < target) cpu.step();
    }
}

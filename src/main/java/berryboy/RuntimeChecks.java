package berryboy;

import berryboy.bus.Memory;

/** Small portable self-test also executed inside the real MIDP/BlackBerry runtime. */
public final class RuntimeChecks {
    private RuntimeChecks() {
    }

    public static int run() {
        var machine = new Machine(DemoRom.create());
        machine.runInstructions(DemoRom.RUN_BUDGET);
        require(machine.cpu.isThumb(), "BX did not enter Thumb");
        require(machine.cpu.pc() == DemoRom.HALT_ADDRESS, "Demo did not finish");
        require(machine.cpu.register(1) == 0, "Thumb loop counter");
        require(machine.memory.read16(Memory.VRAM_BASE + 76798) == 38399, "VRAM final pixel");
        var pixels = machine.video.render();
        require(pixels[31] == 0xff0000, "RGB red");
        require(pixels[32767] == 0xffffff, "RGB white");
        machine.memory.write32(Memory.EWRAM_BASE, 0x12345678);
        require(machine.memory.read32(Memory.EWRAM_BASE + 0x40000) == 0x12345678, "RAM mirroring");
        machine.memory.setPressedKeys(1);
        require(machine.memory.read16(Memory.KEY_INPUT) == 0x3fe, "KEYINPUT polarity");
        return 8;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new EmulationException("Runtime check: " + message);
        }
    }
}

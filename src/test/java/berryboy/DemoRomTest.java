package berryboy;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
@DisplayName("Demo program and portable runtime checks")
class DemoRomTest {
    @Test
    void completesTheDemoAndRendersItsFramebuffer() {
        var machine = new Machine(DemoRom.create());

        machine.runInstructions(DemoRom.RUN_BUDGET);
        var pixels = machine.video.render();

        assertAll(
                () -> assertEquals(DemoRom.HALT_ADDRESS, machine.cpu.pc()),
                () -> assertEquals(38399, machine.memory.read16(Memory.VRAM_BASE + 38399 * 2)),
                () -> assertEquals(38400, pixels.length),
                () -> assertEquals(0, pixels[0], "black"),
                () -> assertEquals(0xff0000, pixels[31], "red"),
                () -> assertEquals(0xffffff, pixels[32767], "white"));
    }

    @Test
    void passesTheChecksAlsoUsedByTheMidlet() {
        assertEquals(8, RuntimeChecks.run());
    }
}

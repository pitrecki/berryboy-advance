package berryboy;

import berryboy.bus.Memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

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

        assertSoftly(softly -> {
            softly.assertThat(machine.cpu.pc()).isEqualTo(DemoRom.HALT_ADDRESS);
            softly.assertThat(machine.memory.read16(Memory.VRAM_BASE + 38399 * 2)).isEqualTo(38399);
            softly.assertThat(pixels).hasSize(38400);
            softly.assertThat(pixels[0]).as("black").isEqualTo(0);
            softly.assertThat(pixels[31]).as("red").isEqualTo(0xff0000);
            softly.assertThat(pixels[32767]).as("white").isEqualTo(0xffffff);
        });
    }

    @Test
    void passesTheChecksAlsoUsedByTheMidlet() {
        assertThat(RuntimeChecks.run()).isEqualTo(8);
    }
}

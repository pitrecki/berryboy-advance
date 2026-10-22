package berryboy.cpu;

import berryboy.EmulationException;
import berryboy.EmulatorFixtures;
import berryboy.bus.Memory;

import static berryboy.EmulatorFixtures.arm;
import static berryboy.EmulatorFixtures.thumb;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
@DisplayName("CPU diagnostic stops")
class CpuFaultTest {
    @Test
    void reportsOpcodeAndAddressWhenSoftwareInterruptHasNoBios() {
        var machine = arm(0xef000000);

        assertSoftly(softly -> {
            softly.assertThatThrownBy(machine.cpu::step)
                    .isInstanceOf(EmulationException.class)
                    .hasMessageContaining("SWI needs a supplied BIOS")
                    .hasMessageContaining("opcode=0xef000000")
                    .hasMessageContaining("pc=0x8000000");
            softly.assertThat(machine.cpu.pc()).as("faulting PC remains inspectable").isEqualTo(Memory.ROM_BASE);
        });
    }

    @Test
    void rejectsSavedStatusAccessFromSystemMode() {
        var machine = arm(0xe1b0f00e); // MOVS pc,lr

        assertThatThrownBy(machine.cpu::step)
                .isInstanceOf(EmulationException.class)
                .hasMessageContaining("no SPSR");
    }

    @Test
    void rejectsArmV5BranchLinkExchange() {
        var machine = thumb(0x4780);

        assertThatThrownBy(machine.cpu::step)
                .isInstanceOf(EmulationException.class)
                .hasMessageContaining("BLX");
    }
}

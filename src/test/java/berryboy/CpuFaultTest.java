package berryboy;

import static berryboy.EmulatorFixtures.arm;
import static berryboy.EmulatorFixtures.thumb;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
@DisplayName("CPU diagnostic stops")
class CpuFaultTest {
    @Test
    void reportsOpcodeAndAddressWhenSoftwareInterruptHasNoBios() {
        var machine = arm(0xef000000);

        var error = assertThrows(EmulationException.class, machine.cpu::step);

        assertAll(
                () -> assertTrue(error.getMessage().contains("SWI needs a supplied BIOS")),
                () -> assertTrue(error.getMessage().contains("opcode=0xef000000")),
                () -> assertTrue(error.getMessage().contains("pc=0x8000000")),
                () -> assertEquals(Memory.ROM_BASE, machine.cpu.pc(), "faulting PC remains inspectable"));
    }

    @Test
    void rejectsSavedStatusAccessFromSystemMode() {
        var machine = arm(0xe1b0f00e); // MOVS pc,lr

        var error = assertThrows(EmulationException.class, machine.cpu::step);

        assertTrue(error.getMessage().contains("no SPSR"));
    }

    @Test
    void rejectsArmV5BranchLinkExchange() {
        var machine = thumb(0x4780);

        var error = assertThrows(EmulationException.class, machine.cpu::step);

        assertTrue(error.getMessage().contains("BLX"));
    }
}

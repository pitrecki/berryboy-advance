package berryboy;

import static berryboy.EmulatorFixtures.arm;
import static berryboy.EmulatorFixtures.thumb;
import static berryboy.EmulatorFixtures.writeWord;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
@DisplayName("CPU status, register banks and SWI")
class CpuStatusTest {
    @Test
    void readsBackWrittenStatusFlags() {
        var machine = arm(0xe128f000, 0xe10f1000); // MSR CPSR_f,r0; MRS r1,CPSR
        machine.cpu.setRegister(0, Cpu.N | Cpu.C);

        machine.runInstructions(2);

        assertEquals(Cpu.SYSTEM | Cpu.N | Cpu.C, machine.cpu.register(1));
    }

    @Test
    void preservesSystemAndFiqRegisterBanks() {
        var cpu = arm(0xe1a00000).cpu;
        cpu.setRegister(8, 88);
        cpu.setRegister(13, 130);

        cpu.setCpsr(Cpu.FIQ);
        assertEquals(0, cpu.register(8), "FIQ starts with a separate r8");
        cpu.setRegister(8, 888);
        cpu.setRegister(13, 131);

        cpu.setCpsr(Cpu.SYSTEM);
        assertAll(
                () -> assertEquals(88, cpu.register(8), "shared r8"),
                () -> assertEquals(130, cpu.register(13), "shared SP"));

        cpu.setCpsr(Cpu.FIQ);
        assertAll(
                () -> assertEquals(888, cpu.register(8), "FIQ r8"),
                () -> assertEquals(131, cpu.register(13), "FIQ SP"));
    }

    @Test
    void entersSupervisorModeAndReturnsToThumbAfterSoftwareInterrupt() {
        var machine = thumb(0xdf00, 0x2007); // SWI; MOV r0,#7
        var bios = new byte[0x4000];
        writeWord(bios, 8, 0xe1b0f00e); // MOVS pc,lr: return through SPSR
        machine.memory.setBios(bios);
        var initialStatus = Cpu.SYSTEM | Cpu.T | Cpu.C;
        machine.cpu.setCpsr(initialStatus);

        machine.cpu.step();
        assertAll(
                () -> assertEquals(8, machine.cpu.pc(), "SWI vector"),
                () -> assertEquals(Memory.ROM_BASE + 2, machine.cpu.register(14), "return address"),
                () -> assertEquals(Cpu.SUPERVISOR, machine.cpu.cpsr() & 31));

        machine.cpu.step();
        assertEquals(initialStatus, machine.cpu.cpsr(), "SPSR restores CPSR");

        machine.cpu.step();
        assertEquals(7, machine.cpu.register(0), "execution resumes in Thumb");
    }
}

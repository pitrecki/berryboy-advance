package berryboy;

import static berryboy.EmulatorFixtures.arm;
import static berryboy.EmulatorFixtures.thumb;
import static berryboy.EmulatorFixtures.writeWord;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

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

        assertThat(machine.cpu.register(1)).isEqualTo(Cpu.SYSTEM | Cpu.N | Cpu.C);
    }

    @Test
    void preservesSystemAndFiqRegisterBanks() {
        var cpu = arm(0xe1a00000).cpu;
        cpu.setRegister(8, 88);
        cpu.setRegister(13, 130);

        cpu.setCpsr(Cpu.FIQ);
        assertThat(cpu.register(8)).as("FIQ starts with a separate r8").isEqualTo(0);
        cpu.setRegister(8, 888);
        cpu.setRegister(13, 131);

        cpu.setCpsr(Cpu.SYSTEM);
        assertSoftly(softly -> {
            softly.assertThat(cpu.register(8)).as("shared r8").isEqualTo(88);
            softly.assertThat(cpu.register(13)).as("shared SP").isEqualTo(130);
        });

        cpu.setCpsr(Cpu.FIQ);
        assertSoftly(softly -> {
            softly.assertThat(cpu.register(8)).as("FIQ r8").isEqualTo(888);
            softly.assertThat(cpu.register(13)).as("FIQ SP").isEqualTo(131);
        });
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
        assertSoftly(softly -> {
            softly.assertThat(machine.cpu.pc()).as("SWI vector").isEqualTo(8);
            softly.assertThat(machine.cpu.register(14)).as("return address").isEqualTo(Memory.ROM_BASE + 2);
            softly.assertThat(machine.cpu.cpsr() & 31).isEqualTo(Cpu.SUPERVISOR);
        });

        machine.cpu.step();
        assertThat(machine.cpu.cpsr()).as("SPSR restores CPSR").isEqualTo(initialStatus);

        machine.cpu.step();
        assertThat(machine.cpu.register(0)).as("execution resumes in Thumb").isEqualTo(7);
    }
}

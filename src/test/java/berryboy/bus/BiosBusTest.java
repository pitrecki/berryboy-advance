package berryboy.bus;

import berryboy.EmulationException;
import berryboy.cpu.Cpu;

import static berryboy.EmulatorFixtures.arm;
import static berryboy.EmulatorFixtures.writeWord;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("core")
class BiosBusTest {
    @Test
    void leavingBiosProtectsEveryDataWidthAndReentryRestoresActualBytes() {
        var m = arm(0xe5921000, 0xe5d23001, 0xe1d240b2); // LDR, LDRB, LDRH from BIOS.
        var bios = new byte[0x4000];
        writeWord(bios, 0, 0xe12fff10); // BX r0 exits BIOS.
        writeWord(bios, 8, 0x89abcdef); // Prefetched word retained on exit.
        writeWord(bios, 0x20, 0xe5921000); // LDR r1,[r2], now allowed inside BIOS.
        writeWord(bios, 0x30, 0x11223344);
        m.memory.setBios(bios);
        m.cpu.setPc(0);
        m.cpu.setRegister(0, Memory.ROM_BASE);
        m.cpu.setRegister(2, 0x30);
        m.runInstructions(4);
        assertSoftly(softly -> {
            softly.assertThat(m.cpu.register(1)).isEqualTo(0x89abcdef);
            softly.assertThat(m.cpu.register(3)).isEqualTo(0xcd);
            softly.assertThat(m.cpu.register(4)).isEqualTo(0x89ab);
        });
        m.cpu.setPc(0x20);
        m.cpu.step();
        assertThat(m.cpu.register(1)).isEqualTo(0x11223344);
    }

    @ParameterizedTest
    @CsvSource({
            "0xe5901000, 0x4000, 0x1234ab78",
            "0xe5d01000, 0x4001, 0xab",
            "0xe1d010b0, 0x4002, 0x1234",
            "0xe1d010d0, 0x4179, -85",
            "0xe1d010f0, 0x4001, -85"
    })
    void armUnmappedBiosSpaceUsesPrefetchByteLanesAndLoadSignExtension(long opcode, int address, long expected) {
        var m = arm((int) opcode, 0xe1a00000, 0x1234ab78);
        m.memory.setBios(new byte[0x4000]);
        m.cpu.setRegister(0, address);
        m.cpu.step();
        assertThat(m.cpu.register(1)).isEqualTo((int) expected);
    }

    @ParameterizedTest
    @CsvSource({
            "0x08000000, 0x12341234",
            "0x03000000, 0x46c01234",
            "0x03000002, 0x123446c0",
            "0x07000000, 0x56781234",
            "0x00000020, 0x56781234"
    })
    void thumbUnmappedReadsRespectTheInstructionBusWidthAndIwramAlignment(int pc, long expected) {
        var m = arm(0);
        var bios = new byte[0x4000];
        writeWord(bios, 0x20, 0x46c06801);
        writeWord(bios, 0x24, 0x56781234);
        m.memory.setBios(bios);
        if (pc != 0x20) {
            // ROM stores are ignored, so replace its original program via the fixture.
            if (pc == Memory.ROM_BASE) m = arm(0x46c06801, 0x56781234);
            else {
                m.memory.write16(pc, 0x6801); // LDR r1,[r0].
                m.memory.write16(pc + 2, 0x46c0);
                m.memory.write16(pc + 4, 0x1234);
                m.memory.write16(pc + 6, 0x5678);
            }
        }
        m.memory.setBios(bios);
        m.cpu.setCpsr(Cpu.SYSTEM | Cpu.T);
        m.cpu.setPc(pc);
        m.cpu.setRegister(0, 0x4000);
        m.cpu.step();
        assertThat(m.cpu.register(1)).isEqualTo((int) expected);
    }

    @Test
    void openBusSupportDoesNotHideMissingBiosUnknownIoOrUnmappedExecution() {
        var m = arm(0xe5901000);
        m.cpu.setRegister(0, 0x4179);
        assertThatThrownBy(m.cpu::step).isInstanceOf(EmulationException.class).hasMessageContaining("0x4178");
        m.memory.setBios(new byte[0x4000]);
        assertThatThrownBy(() -> m.memory.read16(0x04000412)).isInstanceOf(EmulationException.class);
        m.cpu.setPc(0x4178);
        assertThatThrownBy(m.cpu::step).isInstanceOf(EmulationException.class).hasMessageContaining("outside BIOS");
    }
}

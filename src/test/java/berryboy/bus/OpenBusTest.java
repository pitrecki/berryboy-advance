package berryboy.bus;

import berryboy.EmulationException;
import static berryboy.EmulatorFixtures.arm;
import static berryboy.EmulatorFixtures.thumb;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("core")
class OpenBusTest {
    @ParameterizedTest
    @CsvSource({
            "0xe5901000, 0xe55ec000, 0x1234ab78",
            "0xe5d01000, 0xe55ec003, 0x12",
            "0xe1d010b0, 0xe55ec002, 0x1234",
            "0xe1d010d0, 0xe55ec001, -85",
            "0xe1d010f0, 0xe3a02004, -21640",
            "0xe5901000, 0x01000000, 0x1234ab78",
            "0xe5d01000, 0xffffffff, 0x12"
    })
    void unmappedArmReadsUsePrefetchLanesInsteadOfFaulting(long opcode, long address, long expected) {
        var m = arm((int) opcode, 0xe1a00000, 0x1234ab78);
        m.cpu.setRegister(0, (int) address);
        m.cpu.step();
        assertThat(m.cpu.register(1)).isEqualTo((int) expected);
    }

    @Test
    void thumbReadsRepeatTheRomHalfwordForHighAddresses() {
        var m = thumb(0x784b, 0x46c0, 0xab78); // LDRB r3,[r1,#1].
        m.cpu.setRegister(1, 0xe55ec002);
        m.cpu.step();
        assertThat(m.cpu.register(3)).isEqualTo(0xab);
    }

    @Test
    void unmappedStoresAreIgnoredButUnknownDevicesAndExecutionStillDiagnose() {
        var m = arm(0xe5801000);
        m.memory.write32(0x02000000, 0x12345678);
        m.cpu.setRegister(0, 0xe55ec000);
        m.cpu.setRegister(1, 42);
        m.cpu.step();
        assertThat(m.memory.read32(0x02000000)).isEqualTo(0x12345678);
        assertThat(m.cpu.instructions()).isEqualTo(1);
        assertThatThrownBy(() -> m.memory.read16(0x04000412)).isInstanceOf(EmulationException.class);
        assertThatThrownBy(() -> m.memory.read16(0xe55ec000)).isInstanceOf(EmulationException.class);
        m.cpu.setPc(0xe55ec000);
        assertThatThrownBy(m.cpu::step).isInstanceOf(EmulationException.class).hasMessageContaining("unmapped region");
    }
}

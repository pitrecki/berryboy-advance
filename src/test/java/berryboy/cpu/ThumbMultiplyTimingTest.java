package berryboy.cpu;

import berryboy.EmulatorFixtures;
import berryboy.bus.Memory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

@Tag("core")
class ThumbMultiplyTimingTest {
    @ParameterizedTest
    @CsvSource({
            "0,2147483647,1", "255,305419896,1", "256,1,2", "65535,1,2",
            "65536,224,3", "16777215,1,3", "16777216,1,4", "2147483647,1,4",
            "-1,305419896,1", "-256,5,1", "-257,1,2", "-65536,7,2",
            "-65537,3,3", "-16777216,2,3", "-16777217,1,4", "597275,224,3"
    })
    void earlyTerminationUsesOriginalDestinationRatherThanTheOtherOperand(int destination, int source, int internal) {
        var machine = multiply(destination, source);
        machine.cpu.step();
        var result = destination * source;
        assertSoftly(s -> {
            s.assertThat(machine.clock.now()).as("one IWRAM fetch plus multiplier iterations").isEqualTo(1L + internal);
            s.assertThat(machine.cpu.register(0)).isEqualTo(result);
            s.assertThat(machine.cpu.register(1)).isEqualTo(source);
            s.assertThat(machine.cpu.pc()).isEqualTo(Memory.IWRAM_BASE + 2);
            s.assertThat(machine.cpu.negative()).isEqualTo(result < 0);
            s.assertThat(machine.cpu.zero()).isEqualTo(result == 0);
            s.assertThat(machine.cpu.carry()).isTrue();
            s.assertThat(machine.cpu.overflow()).isTrue();
        });
    }

    @Test
    void correctedMultiplyDurationDispatchesAnInterveningDeviceDeadline() {
        var machine = multiply(597275, 224);
        var observed = new long[]{-1};
        machine.clock.schedule(() -> observed[0] = machine.clock.now(), 3);
        machine.cpu.step();
        assertSoftly(s -> {
            s.assertThat(observed[0]).isEqualTo(3);
            s.assertThat(machine.clock.now()).isEqualTo(4);
        });
    }

    private static berryboy.Machine multiply(int destination, int source) {
        var machine = EmulatorFixtures.thumb();
        machine.memory.write16(Memory.IWRAM_BASE, 0x4348); // MUL r0,r1; original asset-free instruction.
        machine.memory.write16(Memory.IWRAM_BASE + 2, 0x46c0); // NOP.
        machine.cpu.setPc(Memory.IWRAM_BASE);
        machine.cpu.setCpsr(Cpu.SYSTEM | Cpu.T | Cpu.C | Cpu.V);
        machine.cpu.setRegister(0, destination);
        machine.cpu.setRegister(1, source);
        return machine;
    }
}

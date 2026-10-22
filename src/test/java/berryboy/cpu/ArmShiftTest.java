package berryboy.cpu;

import berryboy.EmulatorFixtures;
import berryboy.bus.Memory;

import static berryboy.EmulatorFixtures.arm;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("core")
@DisplayName("ARM barrel shifter")
class ArmShiftTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("registerShifts")
    void shiftsByRegisterAndPreservesOverflow(ShiftCase sample) {
        var machine = arm(0xe1b02110 | (sample.type() << 5)); // MOVS r2,r0,shift r1
        machine.cpu.setRegister(0, 0x80000001);
        machine.cpu.setRegister(1, sample.amount());
        machine.cpu.setCpsr(Cpu.SYSTEM | Cpu.C | Cpu.V);

        machine.cpu.step();

        assertSoftly(softly -> {
            softly.assertThat(machine.cpu.register(2)).isEqualTo(sample.result());
            softly.assertThat(machine.cpu.carry()).as("carry-out").isEqualTo(sample.carry());
            softly.assertThat(machine.cpu.overflow()).as("logical shift preserves V").isTrue();
        });
    }

    @Test
    void rotatesThroughCarry() {
        var machine = arm(0xe1b01060); // MOVS r1,r0,RRX
        machine.cpu.setRegister(0, 2);
        machine.cpu.setCpsr(Cpu.SYSTEM | Cpu.C);

        machine.cpu.step();

        assertSoftly(softly -> {
            softly.assertThat(machine.cpu.register(1)).isEqualTo(0x80000001);
            softly.assertThat(machine.cpu.carry()).isFalse();
        });
    }

    @Test
    void readsPcWithTheCorrectPipelineOffset() {
        var machine = arm(0xe1a0000f, 0xe1a0111f); // MOV r0,pc; MOV r1,pc,LSL r1

        machine.runInstructions(2);

        assertSoftly(softly -> {
            softly.assertThat(machine.cpu.register(0)).as("ordinary PC operand").isEqualTo(Memory.ROM_BASE + 8);
            softly.assertThat(machine.cpu.register(1)).as("register-shifted PC operand").isEqualTo(Memory.ROM_BASE + 16);
        });
    }

    private static Stream<ShiftCase> registerShifts() {
        return Stream.of(
                new ShiftCase(0, 0, 0x80000001, true),
                new ShiftCase(0, 1, 2, true),
                new ShiftCase(0, 31, 0x80000000, false),
                new ShiftCase(0, 32, 0, true),
                new ShiftCase(0, 33, 0, false),
                new ShiftCase(1, 0, 0x80000001, true),
                new ShiftCase(1, 1, 0x40000000, true),
                new ShiftCase(1, 32, 0, true),
                new ShiftCase(1, 255, 0, false),
                new ShiftCase(2, 1, 0xc0000000, true),
                new ShiftCase(2, 31, -1, false),
                new ShiftCase(2, 32, -1, true),
                new ShiftCase(2, 255, -1, true),
                new ShiftCase(3, 0, 0x80000001, true),
                new ShiftCase(3, 1, 0xc0000000, true),
                new ShiftCase(3, 32, 0x80000001, true),
                new ShiftCase(3, 33, 0xc0000000, true));
    }

    private record ShiftCase(int type, int amount, int result, boolean carry) {
        @Override
        public String toString() {
            var name = switch (type) {
                case 0 -> "LSL";
                case 1 -> "LSR";
                case 2 -> "ASR";
                default -> "ROR";
            };
            return name + " by " + amount;
        }
    }
}

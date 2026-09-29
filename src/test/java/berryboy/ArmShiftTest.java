package berryboy;

import static berryboy.EmulatorFixtures.arm;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertAll(
                () -> assertEquals(sample.result(), machine.cpu.register(2)),
                () -> assertEquals(sample.carry(), machine.cpu.carry(), "carry-out"),
                () -> assertTrue(machine.cpu.overflow(), "logical shift preserves V"));
    }

    @Test
    void rotatesThroughCarry() {
        var machine = arm(0xe1b01060); // MOVS r1,r0,RRX
        machine.cpu.setRegister(0, 2);
        machine.cpu.setCpsr(Cpu.SYSTEM | Cpu.C);

        machine.cpu.step();

        assertAll(
                () -> assertEquals(0x80000001, machine.cpu.register(1)),
                () -> assertFalse(machine.cpu.carry()));
    }

    @Test
    void readsPcWithTheCorrectPipelineOffset() {
        var machine = arm(0xe1a0000f, 0xe1a0111f); // MOV r0,pc; MOV r1,pc,LSL r1

        machine.runInstructions(2);

        assertAll(
                () -> assertEquals(Memory.ROM_BASE + 8, machine.cpu.register(0), "ordinary PC operand"),
                () -> assertEquals(Memory.ROM_BASE + 16, machine.cpu.register(1), "register-shifted PC operand"));
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

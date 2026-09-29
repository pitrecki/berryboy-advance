package berryboy;

import static berryboy.EmulatorFixtures.arm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("core")
@DisplayName("ARM arithmetic and flags")
class ArmArithmeticTest {
    private static final int UNCHANGED_DESTINATION = 0x13579bdf;

    @ParameterizedTest(name = "{0}")
    @MethodSource("arithmeticCases")
    void computesResultAndFlags(ArithmeticCase sample) {
        var opcode = 0xe0100000 | (sample.operation().encoding << 21) | (2 << 12) | 1;
        var machine = arm(opcode);
        var cpu = machine.cpu;
        cpu.setRegister(0, sample.left());
        cpu.setRegister(1, sample.right());
        cpu.setRegister(2, UNCHANGED_DESTINATION);
        cpu.setCpsr(Cpu.SYSTEM | (sample.carryIn() ? Cpu.C : 0));
        var expected = expectedResult(sample);

        cpu.step();

        assertSoftly(softly -> {
            softly.assertThat(cpu.register(2)).as("destination register")
                    .isEqualTo(sample.operation().writesResult ? expected.value() : UNCHANGED_DESTINATION);
            softly.assertThat(cpu.negative()).as("N flag").isEqualTo(expected.value() < 0);
            softly.assertThat(cpu.zero()).as("Z flag").isEqualTo(expected.value() == 0);
            softly.assertThat(cpu.carry()).as("C flag").isEqualTo(expected.carry());
            softly.assertThat(cpu.overflow()).as("V flag").isEqualTo(expected.overflow());
        });
    }

    @ParameterizedTest(name = "MOV immediate {1}")
    @CsvSource({"0xe3a00090, 0x90", "0xe3a000ff, 0xff"})
    void acceptsImmediateBitsThatResembleMultiplyEncoding(long opcode, int immediate) {
        var machine = arm((int) opcode);

        machine.cpu.step();

        assertThat(machine.cpu.register(0)).isEqualTo(immediate);
    }

    @Test
    void appliesLogicalOperationsAndComparisonFlags() {
        var machine = arm(
                0xe1e02000, // MVN r2,r0
                0xe1c03001, // BIC r3,r0,r1
                0xe1100001, // TST r0,r1
                0xe1300000  // TEQ r0,r0
        );
        machine.cpu.setRegister(0, 0x55);
        machine.cpu.setRegister(1, 0x0f);

        machine.runInstructions(4);

        assertSoftly(softly -> {
            softly.assertThat(machine.cpu.register(2)).as("MVN").isEqualTo(~0x55);
            softly.assertThat(machine.cpu.register(3)).as("BIC").isEqualTo(0x50);
            softly.assertThat(machine.cpu.zero()).as("TEQ compares equal operands").isTrue();
        });
    }

    private static Stream<ArithmeticCase> arithmeticCases() {
        var random = new Random(0xbba11);
        var extremes = new int[]{0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x7ffffffe};
        var samples = Stream.<ArithmeticCase>builder();
        for (var operation : Operation.values()) {
            for (var index = 0; index < 64; index++) {
                var left = index < extremes.length ? extremes[index] : random.nextInt();
                var right = index < extremes.length ? extremes[extremes.length - 1 - index] : random.nextInt();
                samples.add(new ArithmeticCase(operation, left, right, (index & 1) != 0));
            }
        }
        return samples.build();
    }

    /** Wide mathematical addition/subtraction provides an oracle independent of the CPU ALU. */
    private static ExpectedResult expectedResult(ArithmeticCase sample) {
        var operation = sample.operation();
        long left = operation.reversed ? sample.right() : sample.left();
        long right = operation.reversed ? sample.left() : sample.right();
        var carry = sample.carryIn() ? 1 : 0;
        var adjustment = switch (operation) {
            case ADC -> carry;
            case SBC, RSC -> carry - 1;
            default -> 0;
        };
        var signed = operation.subtract ? left - right + adjustment : left + right + adjustment;
        var unsignedLeft = left & 0xffffffffL;
        var unsignedRight = right & 0xffffffffL;
        var unsigned = operation.subtract
                ? unsignedLeft - unsignedRight + adjustment
                : unsignedLeft + unsignedRight + adjustment;
        var carryOut = operation.subtract ? unsigned >= 0 : unsigned > 0xffffffffL;
        var overflow = signed < Integer.MIN_VALUE || signed > Integer.MAX_VALUE;
        return new ExpectedResult((int) signed, carryOut, overflow);
    }

    private enum Operation {
        SUB(2, true, false, true),
        RSB(3, true, true, true),
        ADD(4, false, false, true),
        ADC(5, false, false, true),
        SBC(6, true, false, true),
        RSC(7, true, true, true),
        CMP(10, true, false, false),
        CMN(11, false, false, false);

        final int encoding;
        final boolean subtract;
        final boolean reversed;
        final boolean writesResult;

        Operation(int encoding, boolean subtract, boolean reversed, boolean writesResult) {
            this.encoding = encoding;
            this.subtract = subtract;
            this.reversed = reversed;
            this.writesResult = writesResult;
        }
    }

    private record ArithmeticCase(Operation operation, int left, int right, boolean carryIn) {
        @Override
        public String toString() {
            return "%s: left=%08x, right=%08x, C=%s".formatted(operation, left, right, carryIn);
        }
    }

    private record ExpectedResult(int value, boolean carry, boolean overflow) {
    }
}

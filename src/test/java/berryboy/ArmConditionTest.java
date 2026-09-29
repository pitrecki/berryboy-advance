package berryboy;

import static berryboy.EmulatorFixtures.arm;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("core")
@DisplayName("ARM conditional execution")
class ArmConditionTest {
    @ParameterizedTest(name = "{0}, NZCV={2}")
    @MethodSource("conditions")
    void executesOnlyWhenConditionMatches(String name, int condition, int flags, boolean expected) {
        var machine = arm((condition << 28) | 0x03a00001); // Conditional MOV r0,#1
        machine.cpu.setCpsr(Cpu.SYSTEM | (flags << 28));

        machine.cpu.step();

        assertEquals(expected ? 1 : 0, machine.cpu.register(0));
    }

    private static Stream<Arguments> conditions() {
        return IntStream.range(0, 16).boxed().flatMap(flags -> {
            var negative = (flags & 8) != 0;
            var zero = (flags & 4) != 0;
            var carry = (flags & 2) != 0;
            var overflow = (flags & 1) != 0;
            var expected = new boolean[]{
                    zero, !zero, carry, !carry, negative, !negative, overflow, !overflow,
                    carry && !zero, !carry || zero, negative == overflow, negative != overflow,
                    !zero && negative == overflow, zero || negative != overflow, true, false
            };
            var names = new String[]{"EQ", "NE", "CS", "CC", "MI", "PL", "VS", "VC",
                    "HI", "LS", "GE", "LT", "GT", "LE", "AL", "NV"};
            return IntStream.range(0, 16).mapToObj(condition ->
                    Arguments.of(names[condition], condition, flags, expected[condition]));
        });
    }
}

package berryboy.cpu;

import berryboy.EmulatorFixtures;

import static berryboy.EmulatorFixtures.arm;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
@DisplayName("ARM multiplication")
class ArmMultiplyTest {
    @Test
    void multipliesAndAccumulates() {
        var machine = arm(
                0xe0020190, // MUL r2,r0,r1
                0xe0232190  // MLA r3,r0,r1,r2
        );
        machine.cpu.setRegister(0, -7);
        machine.cpu.setRegister(1, 6);

        machine.runInstructions(2);

        assertSoftly(softly -> {
            softly.assertThat(machine.cpu.register(2)).isEqualTo(-42);
            softly.assertThat(machine.cpu.register(3)).isEqualTo(-84);
        });
    }

    @Test
    void distinguishesSignedAndUnsignedLongProducts() {
        var machine = arm(
                0xe0832190, // UMULL r2,r3,r0,r1
                0xe0c54190  // SMULL r4,r5,r0,r1
        );
        machine.cpu.setRegister(0, -1);
        machine.cpu.setRegister(1, 2);

        machine.runInstructions(2);

        assertSoftly(softly -> {
            softly.assertThat(machine.cpu.register(2)).as("UMULL low").isEqualTo(-2);
            softly.assertThat(machine.cpu.register(3)).as("UMULL high").isEqualTo(1);
            softly.assertThat(machine.cpu.register(4)).as("SMULL low").isEqualTo(-2);
            softly.assertThat(machine.cpu.register(5)).as("SMULL high").isEqualTo(-1);
        });
    }
}

package berryboy;

import static berryboy.EmulatorFixtures.arm;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

        assertAll(
                () -> assertEquals(-42, machine.cpu.register(2)),
                () -> assertEquals(-84, machine.cpu.register(3)));
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

        assertAll(
                () -> assertEquals(-2, machine.cpu.register(2), "UMULL low"),
                () -> assertEquals(1, machine.cpu.register(3), "UMULL high"),
                () -> assertEquals(-2, machine.cpu.register(4), "SMULL low"),
                () -> assertEquals(-1, machine.cpu.register(5), "SMULL high"));
    }
}

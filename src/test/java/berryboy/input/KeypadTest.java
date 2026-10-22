package berryboy.input;

import berryboy.EmulatorFixtures;
import berryboy.bus.Memory;

import static berryboy.EmulatorFixtures.arm;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("core")
@DisplayName("GBA keypad input")
class KeypadTest {
    @ParameterizedTest(name = "button bit {0}")
    @ValueSource(ints = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512})
    void exposesAllTenButtonsAsActiveLowAndReadOnly(int button) {
        var machine = arm(0xe1a00000);
        machine.memory.setPressedKeys(button);
        machine.memory.write16(Memory.KEY_INPUT, 0);

        assertThat(machine.memory.read16(Memory.KEY_INPUT)).isEqualTo(0x3ff ^ button);
    }

    @Test
    void guestInstructionsObservePressesChordsAndReleases() {
        var machine = arm(0xe1d010b0, 0xeafffffd); // LDRH r1,[r0]; B back to LDRH
        machine.cpu.setRegister(0, Memory.KEY_INPUT);
        machine.memory.setPressedKeys(Keypad.RIGHT | Keypad.A | Keypad.L);

        machine.runInstructions(2);
        assertThat(machine.cpu.register(1)).isEqualTo(0x1ee);

        machine.memory.setPressedKeys(0);
        machine.runInstructions(2);
        assertThat(machine.cpu.register(1)).isEqualTo(0x3ff);
    }

    @Test
    void ignoresBitsOutsideTheKeypad() {
        var keypad = new Keypad();
        keypad.setPressed(~Keypad.ALL_BUTTONS);

        assertThat(keypad.readInput()).isEqualTo(0x3ff);
    }
}

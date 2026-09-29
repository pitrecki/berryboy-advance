package berryboy;

import static berryboy.EmulatorFixtures.blankRom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("core")
@DisplayName("GBA memory bus")
class MemoryTest {
    private Memory memory;

    @BeforeEach
    void createMemory() {
        memory = new Memory(new Cartridge(blankRom()));
    }

    @Test
    void readsLittleEndianWordsAndExternalRamMirror() {
        memory.write32(Memory.EWRAM_BASE, 0x12345678);

        assertSoftly(softly -> {
            softly.assertThat(memory.read8(Memory.EWRAM_BASE)).isEqualTo(0x78);
            softly.assertThat(memory.read32(Memory.EWRAM_BASE + 0x40000)).isEqualTo(0x12345678);
        });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "IWRAM mirror, 0x03000000, 0x03008000, 0xabcd",
            "VRAM mirror, 0x06018000, 0x06010000, 0x42"
    })
    void mirrorsHalfwordWrites(String name, int original, int mirror, int value) {
        memory.write16(original, value);

        assertThat(memory.read16(mirror)).isEqualTo(value);
    }

    @Test
    void ignoresRomWrites() {
        memory.write8(Memory.ROM_BASE, 0x11);

        assertThat(memory.read8(Memory.ROM_BASE)).isEqualTo(0);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "palette repeats byte across both lanes, 0x05000001, 0x05000000, 0x12, 0x1212",
            "background VRAM repeats byte, 0x06000000, 0x06000000, 0x34, 0x3434",
            "OAM ignores byte stores, 0x07000000, 0x07000000, 0x56, 0",
            "object VRAM ignores byte stores, 0x06010000, 0x06010000, 0x56, 0"
    })
    void appliesVideoBusByteWriteRules(String name, int address, int readAddress, int value, int expected) {
        memory.write8(address, value);

        assertThat(memory.read16(readAddress)).isEqualTo(expected);
    }

    @Test
    void exposesActiveLowKeysAndIgnoresWritesToKeyInput() {
        memory.setPressedKeys(0x11);
        assertThat(memory.read16(Memory.KEY_INPUT)).as("pressed keys are low").isEqualTo(0x3ee);

        memory.write16(Memory.KEY_INPUT, 0);

        assertThat(memory.read16(Memory.KEY_INPUT)).as("KEYINPUT is read-only").isEqualTo(0x3ee);
    }

    @Test
    void rejectsUnimplementedTimerRegisters() {
        assertThatThrownBy(() -> memory.read16(Memory.IO_BASE + 0x100))
                .isInstanceOf(EmulationException.class);
    }
}

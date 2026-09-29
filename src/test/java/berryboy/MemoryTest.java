package berryboy;

import static berryboy.EmulatorFixtures.blankRom;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        assertAll(
                () -> assertEquals(0x78, memory.read8(Memory.EWRAM_BASE)),
                () -> assertEquals(0x12345678, memory.read32(Memory.EWRAM_BASE + 0x40000)));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "IWRAM mirror, 0x03000000, 0x03008000, 0xabcd",
            "VRAM mirror, 0x06018000, 0x06010000, 0x42"
    })
    void mirrorsHalfwordWrites(String name, int original, int mirror, int value) {
        memory.write16(original, value);

        assertEquals(value, memory.read16(mirror));
    }

    @Test
    void ignoresRomWrites() {
        memory.write8(Memory.ROM_BASE, 0x11);

        assertEquals(0, memory.read8(Memory.ROM_BASE));
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

        assertEquals(expected, memory.read16(readAddress));
    }

    @Test
    void exposesActiveLowKeysAndIgnoresWritesToKeyInput() {
        memory.setPressedKeys(0x11);
        assertEquals(0x3ee, memory.read16(Memory.KEY_INPUT), "pressed keys are low");

        memory.write16(Memory.KEY_INPUT, 0);

        assertEquals(0x3ee, memory.read16(Memory.KEY_INPUT), "KEYINPUT is read-only");
    }

    @Test
    void rejectsUnimplementedTimerRegisters() {
        assertThrows(EmulationException.class, () -> memory.read16(Memory.IO_BASE + 0x100));
    }
}

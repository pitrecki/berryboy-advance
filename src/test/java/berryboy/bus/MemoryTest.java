package berryboy.bus;

import berryboy.EmulationException;
import berryboy.EmulatorFixtures;
import berryboy.cartridge.Cartridge;

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
    void exposesDisabledTimerAndStillRejectsUnsupportedIo() {
        assertThat(memory.read16(Memory.IO_BASE + 0x100)).isZero();
        assertThatThrownBy(() -> memory.read16(Memory.IO_BASE + 0x412))
                .isInstanceOf(EmulationException.class);
    }

    @Test
    void reservedIoWritesDoNotCorruptNeighborRegistersOrHideUnsupportedDevices() {
        memory.write16(0x04000200, 0x1234);
        memory.write32(0x04000204, 0xdead0018); // Upper halfword is reserved.
        memory.write32(0x040000e0, 0xffffffff);
        memory.write8(0x04000410, 255);
        assertThat(memory.read16(0x04000200)).isEqualTo(0x1234);
        assertThat(memory.read16(0x04000204)).isEqualTo(0x18);
        assertThatThrownBy(() -> memory.write16(0x04000128, 0x3080)).isInstanceOf(EmulationException.class);
    }

    @Test
    void internalMemoryControlMirrorsAndDisabledEwramUsesInternalRam() {
        assertThat(memory.read32(0x04000800)).isEqualTo(0x0d000020);
        memory.write32(0x04010800, 0x0e000020);
        assertThat(memory.busTiming().accessCycles(0x02000000, 4, false)).isEqualTo(4);
        memory.write32(0x04000800, 0x0d000000);
        memory.write32(0x02000010, 42);
        assertThat(memory.read32(0x03000010)).isEqualTo(42);
        assertThat(memory.busTiming().accessCycles(0x02000000, 4, false)).isEqualTo(1);
        assertThatThrownBy(() -> memory.write32(0x04000800, 0x0f000020)).isInstanceOf(EmulationException.class);
    }
}

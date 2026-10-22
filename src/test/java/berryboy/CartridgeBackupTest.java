package berryboy;

import static berryboy.EmulatorFixtures.arm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("core")
class CartridgeBackupTest {
    @Test
    void sramMirrorsAndWideBusReadsRepeatOneByteWithoutChangingNeighbors() {
        var m = arm(0xe1a00000);
        m.memory.write32(0x0e000001, 0x12345678);
        assertThat(m.memory.read8(0x0e008001)).isEqualTo(0x56);
        assertThat(m.memory.read32(0x0e000001)).isEqualTo(0x56565656);
        assertThat(m.memory.read8(0x0e000002)).isEqualTo(255);
        assertThat(m.memory.backup().dirty()).isTrue();
    }

    @Test
    void flashUnlockProgramIdentifyBankSwitchAndSectorErasePreserveOtherSectors() {
        var b = new CartridgeBackup(CartridgeBackup.FLASH_128K);
        b.write8(123, 1); // Locked writes ignored.
        assertThat(b.read8(123)).isEqualTo(255);
        command(b, 0x90);
        assertThat(b.read8(0)).isEqualTo(0xc2);
        assertThat(b.read8(1)).isEqualTo(9);
        b.write8(0, 0xf0);
        program(b, 0x123, 0x42);
        program(b, 0x1123, 0x24);
        command(b, 0xb0); b.write8(0, 1);
        assertThat(b.read8(0x123)).isEqualTo(255);
        program(b, 0x123, 0x81);
        command(b, 0xb0); b.write8(0, 0);
        assertThat(b.read8(0x123)).isEqualTo(0x42);
        command(b, 0x80); unlock(b); b.write8(0x123, 0x30);
        assertThat(b.read8(0x123)).isEqualTo(255);
        assertThat(b.read8(0x1123)).isEqualTo(0x24);
        command(b, 0xb0); b.write8(0, 1);
        assertThat(b.read8(0x123)).isEqualTo(0x81);
        command(b, 0x80); command(b, 0x10);
        assertThat(b.snapshot()).containsOnly((byte) 255);
    }

    @ParameterizedTest
    @ValueSource(ints = {CartridgeBackup.EEPROM_512, CartridgeBackup.EEPROM_8K})
    void serialEepromWritesAndReadsAWholeBlockIncludingFourDummyBits(int type) {
        var b = new CartridgeBackup(type);
        int bits = type == CartridgeBackup.EEPROM_512 ? 6 : 14;
        byte[] payload = {(byte) 0x80, 1, 2, 4, 8, 16, 32, (byte) 255};
        serialPrefix(b, false, 5, bits);
        for (byte value : payload) for (int i = 7; i >= 0; i--) b.writeSerial((value >>> i) & 1);
        b.writeSerial(0);
        assertThat(b.readSerial()).isEqualTo(1);
        serialPrefix(b, true, 5, bits); b.writeSerial(0);
        for (int i = 0; i < 4; i++) assertThat(b.readSerial()).isZero();
        for (byte value : payload) {
            int actual = 0;
            for (int i = 0; i < 8; i++) actual = (actual << 1) | b.readSerial();
            assertThat(actual).isEqualTo(value & 255);
        }
        assertThat(b.readSerial()).isEqualTo(1);
        var restored = new CartridgeBackup(type);
        restored.restore(b.snapshot());
        assertThat(restored.snapshot()).containsExactly(b.snapshot());
        assertThat(restored.dirty()).isFalse();
    }

    @Test
    void sizeMismatchDoesNotDestroyExistingSaveAndSnapshotsDoNotAliasStorage() {
        var b = new CartridgeBackup(CartridgeBackup.SRAM);
        b.write8(1, 42);
        var image = b.snapshot();
        image[1] = 0;
        assertThat(b.read8(1)).isEqualTo(42);
        assertThatThrownBy(() -> b.restore(new byte[1])).isInstanceOf(IllegalArgumentException.class);
        assertThat(b.read8(1)).isEqualTo(42);
    }

    @Test
    void eepromSignatureAndDmaCommandLengthSelectSmallDeviceAndSerialBus() {
        var image = EmulatorFixtures.blankRom();
        var marker = "EEPROM_V124";
        for (int i = 0; i < marker.length(); i++) image[0x100 + i] = (byte) marker.charAt(i);
        var m = new Machine(image);
        // Read command: 11, six zero address bits, stop 0.
        m.memory.write16(0x03000000, 1);
        m.memory.write16(0x03000002, 1);
        m.memory.write32(0x040000d4, 0x03000000);
        m.memory.write32(0x040000d8, 0x0d000000);
        m.memory.write16(0x040000dc, 9);
        m.memory.write16(0x040000de, 0x8000);
        m.cpu.step();
        assertThat(m.memory.backup().type()).isEqualTo(CartridgeBackup.EEPROM_512);
        m.memory.write32(0x040000d4, 0x0d000000);
        m.memory.write32(0x040000d8, 0x03000100);
        m.memory.write16(0x040000dc, 68);
        m.memory.write16(0x040000de, 0x8100); // Fixed EEPROM source.
        m.cpu.step();
        for (int i = 0; i < 68; i++) {
            assertThat(m.memory.read16(0x03000100 + i * 2)).isEqualTo(i < 4 ? 0 : 1);
        }
    }

    private void serialPrefix(CartridgeBackup b, boolean read, int address, int bits) {
        b.writeSerial(1); b.writeSerial(read ? 1 : 0);
        for (int i = bits - 1; i >= 0; i--) b.writeSerial((address >>> i) & 1);
    }
    private void unlock(CartridgeBackup b) { b.write8(0x5555, 0xaa); b.write8(0x2aaa, 0x55); }
    private void command(CartridgeBackup b, int value) { unlock(b); b.write8(0x5555, value); }
    private void program(CartridgeBackup b, int address, int value) { command(b, 0xa0); b.write8(address, value); }
}

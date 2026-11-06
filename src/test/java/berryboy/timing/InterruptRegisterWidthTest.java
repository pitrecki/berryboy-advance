package berryboy.timing;

import berryboy.EmulationException;
import berryboy.EmulatorFixtures;
import berryboy.Machine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@Tag("core")
class InterruptRegisterWidthTest {
    private static final int IME = 0x04000208;
    @ParameterizedTest
    @CsvFileSource(resources = "/berryboy/timing/ime-width.csv", numLinesToSkip = 1)
    void preservesBitZeroAndReadsReservedLanesAsZero(int width, long value, int byte0, int byte1,
            int upperHalfword, long word, int ie, int irq) {
        var m = new Machine(new byte[512]);
        m.memory.write16(0x04000200, 0x0200); m.memory.interrupts().request(0x0200);
        if (width == 1) m.memory.write8(IME, (int)value);
        else if (width == 2) m.memory.write16(IME, (int)value);
        else m.memory.write32(IME, (int)value);
        m.memory.write8(IME + 1, 0xaa); m.memory.write16(IME + 2, 0xbeef);
        assertSoftly(s -> {
            s.assertThat(m.memory.read8(IME)).isEqualTo(byte0);
            s.assertThat(m.memory.read8(IME + 1)).isEqualTo(byte1);
            s.assertThat(m.memory.read16(IME + 2)).isEqualTo(upperHalfword);
            s.assertThat(Integer.toUnsignedLong(m.memory.read32(IME))).isEqualTo(word);
            s.assertThat(m.memory.read16(0x04000200)).isEqualTo(ie);
            s.assertThat(m.memory.read16(0x04000202)).isEqualTo(irq);
            s.assertThat(m.memory.interrupts().irqPending()).isEqualTo(byte0 != 0);
        });
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wordLoadUsedByInterruptLibrariesWorksInArmAndThumb(boolean thumb) {
        var m = thumb ? EmulatorFixtures.thumb(0x6820) : EmulatorFixtures.arm(0xe5940000);
        m.cpu.setRegister(4, IME); m.memory.write16(IME, 1);
        m.cpu.step();
        assertThat(m.cpu.register(0)).isEqualTo(1);
        assertThat(m.cpu.register(4)).isEqualTo(IME);
        assertThat(m.cpu.instructions()).isEqualTo(1);
    }
    @Test
    void doesNotBroadenOtherUnsupportedIoReads() {
        var m = new Machine(new byte[512]);
        assertThatThrownBy(() -> m.memory.read16(IME + 4)).isInstanceOf(EmulationException.class);
    }
}

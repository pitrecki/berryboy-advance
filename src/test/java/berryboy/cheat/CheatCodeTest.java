package berryboy.cheat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import berryboy.DemoRom;
import berryboy.Machine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("core")
class CheatCodeTest {
    @Test
    void appliesRawAndCodeBreakerWritesWithoutAdvancingGuestTime() {
        var m = new Machine(DemoRom.create()); var cheats = new CheatList();
        cheats.add("Counter", new CheatCode("02000100:32:DEADBEEF", CheatCode.RAW));
        cheats.add("Halfword", new CheatCode("82000104 03E7", CheatCode.CODE_BREAKER));
        cheats.add("Byte", new CheatCode("33000010 0042", CheatCode.CODE_BREAKER));
        cheats.apply(m.memory);
        assertSoftly(s -> {
            s.assertThat(m.memory.read32(0x02000100)).isEqualTo(0xdeadbeef);
            s.assertThat(m.memory.read16(0x02000104)).isEqualTo(999);
            s.assertThat(m.memory.read8(0x03000010)).isEqualTo(0x42);
            s.assertThat(m.clock.now()).isZero();
        });
        cheats.toggle(1); m.memory.write16(0x02000104, 3); cheats.apply(m.memory);
        assertThat(m.memory.read16(0x02000104)).isEqualTo(3);
        var copy = cheats.copy(); cheats.remove(0);
        assertThat(copy.name(0)).isEqualTo("Counter");
        assertThat(copy.enabled(1)).isFalse();
    }
    @ParameterizedTest
    @ValueSource(strings = {"04000000:32:1234", "08000000:16:0010", "02000001:16:1234", "02000000:8:100", "02000000:16:GG", "02000000:7:01"})
    void rejectsUnsafeOrInvalidRawCodes(String code) {
        assertThatThrownBy(() -> new CheatCode(code, CheatCode.RAW)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {"D2000000 0000", "02000000 0000", "32000000 FFFF", "82000001 0010", "1234"})
    void rejectsUnsupportedCodeBreakerCommandsInsteadOfGuessing(String code) {
        assertThatThrownBy(() -> new CheatCode(code, CheatCode.CODE_BREAKER)).isInstanceOf(IllegalArgumentException.class);
    }
}

package berryboy.mobile.input;

import berryboy.input.Keypad;
import berryboy.state.StateBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@Tag("core")
class KeyBindingsTest {
    @Test
    void normalizesAliasesAndKeepsEveryButtonIndependentlyAddressable() {
        var bindings = new KeyBindings(); bindings.bind(0, 'Q'); bindings.bind(3, '\r');
        assertSoftly(s -> {
            s.assertThat(bindings.buttonsFor('Q')).isEqualTo(Keypad.A);
            s.assertThat(bindings.buttonsFor('q')).isEqualTo(Keypad.A);
            s.assertThat(bindings.buttonsFor('\r')).isEqualTo(Keypad.START);
            s.assertThat(bindings.buttonsFor('\n')).isEqualTo(Keypad.START);
            for (var i = 0; i < 10; i++) s.assertThat(bindings.buttonsFor(bindings.key(i))).isEqualTo(1 << i);
        });
    }

    @Test
    void invalidAssignmentsLeaveTheExistingBindingsUntouched() {
        var bindings = new KeyBindings(); var before = encode(bindings);
        for (var reserved : new int[]{0, -11, 27, '#'}) {
            assertThatThrownBy(() -> bindings.bind(0, reserved)).hasMessageContaining("reserved");
        }
        assertThatThrownBy(() -> bindings.bind(0, 'X')).hasMessageContaining("already assigned");
        assertThatThrownBy(() -> bindings.bind(-1, 'q')).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bindings.bind(10, 'q')).isInstanceOf(IllegalArgumentException.class);
        assertThat(encode(bindings)).isEqualTo(before);
    }

    @Test
    void copiesAndDecodedBindingsOwnTheirMutableState() {
        var source = new KeyBindings(); source.bind(0, 'q');
        var copy = new KeyBindings(); copy.copyFrom(source); copy.bind(0, 'w');
        var image = encode(source); var decoded = new KeyBindings();
        var state = new StateBuffer(image); decoded.transferState(state); state.requireEnd();
        decoded.bind(0, 'e');
        assertSoftly(s -> {
            s.assertThat(source.buttonsFor('q')).isEqualTo(Keypad.A);
            s.assertThat(copy.buttonsFor('w')).isEqualTo(Keypad.A);
            s.assertThat(decoded.buttonsFor('e')).isEqualTo(Keypad.A);
            s.assertThat(source.buttonsFor('w')).isZero();
            s.assertThat(source.buttonsFor('e')).isZero();
        });
    }

    @Test
    void preservesTheLegacyTenLittleEndianWordsAndRejectsDuplicateDecodedKeys() {
        var bindings = new KeyBindings();
        var expected = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        for (var key : new int[]{'z', 'x', 8, 10, 'l', 'j', 'i', 'k', 's', 'a'}) expected.putInt(key);
        assertThat(encode(bindings)).isEqualTo(expected.array());
        expected.putInt(0, 'x');
        assertThatThrownBy(() -> new KeyBindings().transferState(new StateBuffer(expected.array())))
                .hasMessageContaining("already assigned");
    }

    private static byte[] encode(KeyBindings bindings) {
        var state = new StateBuffer(); bindings.transferState(state); return state.finish();
    }
}

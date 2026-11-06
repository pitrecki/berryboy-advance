package berryboy.desktop.scenario;

import berryboy.Machine;
import berryboy.MachineState;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordedInputsTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "-1 0 0 0 0 0", "0 1024 0 0 0 0", "0 -1 0 0 0 0",
        "0 0 -1 0 0 0", "0 0 4096 0 0 0", "0 0 0 -1 0 0", "0 0 0 4096 0 0",
        "0 0 0 0 -1 0", "0 0 0 0 4096 0", "0 0 0 0 0 -1", "0 0 0 0 0 256",
        "0 0 0 0 0", "0 nope 0 0 0 0", "9223372036854775808 0 0 0 0 0",
        "1 0 0 0 0 0\n1 0 0 0 0 0", "2 0 0 0 0 0\n1 0 0 0 0 0"
    })
    void rejectsMalformedEventsBeforeTheyCanBeInstalled(String input) {
        assertThatThrownBy(() -> RecordedInputs.parse(bytes(input))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line");
    }
    @Test
    void ownsInputAndAppliesKeysAndEverySensorAtTheOriginalGuestDeadlines() {
        var source = bytes("  # recording\n\n0 1 4095 0 4095 255\n10 0x300 0 4095 0 0\n");
        var recording = RecordedInputs.parse(source); Arrays.fill(source, (byte)'x');
        var actual = new Machine(new byte[512]); var expected = new Machine(new byte[512]);
        assertThat(recording.lastCycle()).isEqualTo(10);
        recording.install(actual);
        assertThat(actual.memory.read16(0x04000130)).isEqualTo(0x3ff);
        actual.clock.advance(0); expected.memory.setPressedKeys(1); expected.memory.gpio().setSensors(4095,0,4095,255);
        assertThat(actual.memory.read16(0x04000130)).isEqualTo(0x3fe);
        actual.clock.advance(9); assertThat(actual.memory.read16(0x04000130)).isEqualTo(0x3fe);
        actual.clock.advance(1); expected.clock.advance(10);
        expected.memory.setPressedKeys(0x300); expected.memory.gpio().setSensors(0,4095,0,0);
        assertThat(actual.memory.read16(0x04000130)).isEqualTo(0xff);
        assertThat(MachineState.save(actual)).isEqualTo(MachineState.save(expected));
        var replay = new Machine(new byte[512]); recording.install(replay); replay.clock.advance(10);
        assertThat(MachineState.save(replay)).isEqualTo(MachineState.save(actual));
    }
    @Test
    void emptyRecordingsDoNotChangeTheGuestAndInspectionDoesNotInstallAnything() {
        var m = new Machine(new byte[512]); var before = MachineState.save(m);
        var recording = RecordedInputs.parse(bytes(" \n  # empty\n"));
        assertThat(recording.lastCycle()).isEqualTo(-1); recording.install(m);
        assertThat(MachineState.save(m)).isEqualTo(before);
    }
    @Test
    void rejectsInstallationIntoThePastWithoutChangingTheMachine() {
        var m = new Machine(new byte[512]); m.clock.advance(5); var before = MachineState.save(m);
        var recording = RecordedInputs.parse(bytes("0 1 0 0 0 0\n10 0 0 0 0 0"));
        assertThatThrownBy(() -> recording.install(m)).hasMessageContaining("before");
        assertThat(MachineState.save(m)).isEqualTo(before);
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}

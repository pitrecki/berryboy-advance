package berryboy.desktop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import berryboy.DemoRom;
import berryboy.Machine;
import berryboy.MachineState;
import berryboy.video.LcdTiming;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FrameInputsTest {
    @Test
    void deliversValidatedBoundariesToIndependentUiAdaptersWithoutTouchingAGuest() {
        var recording = FrameInputs.parse(bytes("0 0\n1 64\n3 0\n"));
        var first = new java.util.ArrayList<Integer>(); var second = new java.util.ArrayList<Integer>();
        var a = recording.playbackTo(first::add); var b = recording.playbackTo(second::add);
        assertThat(first).isEmpty();
        for (int frame = 0; frame <= 4; frame++) a.afterFrame(frame);
        assertThat(first).containsExactly(0,64,0); assertThat(second).isEmpty();
        b.afterFrame(0); b.afterFrame(1);
        assertThat(second).containsExactly(0,64);
        assertThatThrownBy(() -> recording.playbackTo(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1 0", "2147483648 0", "0 -1", "0 1024", "0 nope", "0", "0 0 0",
            "1 0\n1 1", "2 0\n1 0"})
    void rejectsMalformedOrUnorderedControls(String text) {
        assertThatThrownBy(() -> FrameInputs.parse(bytes(text)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line");
    }

    @Test
    void ownedRecordingMatchesManualButtonsWithoutAddingGuestClockCallbacks() {
        var source = bytes(" # recording\n\n0 1\n2 0x300\n3 0\n");
        var inputs = FrameInputs.parse(source);
        Arrays.fill(source, (byte) 'x');
        var actual = new Machine(DemoRom.create()); var manual = new Machine(DemoRom.create());
        var before = MachineState.save(actual); var playback = inputs.playback(actual);
        assertThat(inputs.firstFrame()).isZero(); assertThat(inputs.lastFrame()).isEqualTo(3);
        assertThat(MachineState.save(actual)).isEqualTo(before);
        for (var frame = 1; frame <= 4; frame++) {
            playback.afterFrame(frame - 1);
            var keys = frame <= 2 ? 1 : frame == 3 ? 0x300 : 0;
            manual.memory.setPressedKeys(keys);
            assertThat(actual.memory.peek16(0x04000130)).isEqualTo(0x3ff ^ keys);
            actual.runCycles(frame * (long) LcdTiming.FRAME_CYCLES - actual.clock.now());
            manual.runCycles(frame * (long) LcdTiming.FRAME_CYCLES - manual.clock.now());
            assertThat(MachineState.save(actual)).as("frame %s, including hardware deadline order", frame)
                    .isEqualTo(MachineState.save(manual));
        }
        var independent = new Machine(DemoRom.create()); var second = inputs.playback(independent);
        second.afterFrame(0);
        assertThat(independent.memory.peek16(0x04000130)).isEqualTo(0x3fe);
    }

    @Test
    void sparsePresentationBoundariesAreAllowedButMissedInputsFailBeforeChangingTheGuest() {
        var machine = new Machine(DemoRom.create());
        var playback = FrameInputs.parse(bytes("1 1\n5 0")).playback(machine);
        playback.afterFrame(0); var before = MachineState.save(machine);
        assertThatThrownBy(() -> playback.afterFrame(2)).hasMessageContaining("Missed frame input boundary 1");
        assertThat(MachineState.save(machine)).isEqualTo(before);
        playback.afterFrame(1);
        assertThat(machine.memory.peek16(0x04000130)).isEqualTo(0x3fe);
        var pressed = MachineState.save(machine);
        assertThatThrownBy(() -> playback.afterFrame(1)).hasMessageContaining("repeat");
        assertThatThrownBy(() -> playback.afterFrame(0)).hasMessageContaining("backwards");
        assertThat(MachineState.save(machine)).isEqualTo(pressed);
        playback.afterFrame(5);
        assertThat(machine.memory.peek16(0x04000130)).isEqualTo(0x3ff);
    }

    @Test
    void anEmptyRecordingDoesNotChangeTheMachine() {
        var inputs = FrameInputs.parse(bytes("\n# no controls\n"));
        var machine = new Machine(DemoRom.create()); var before = MachineState.save(machine);
        assertThat(inputs.firstFrame()).isEqualTo(-1); assertThat(inputs.lastFrame()).isEqualTo(-1);
        var playback = inputs.playback(machine); playback.afterFrame(0); playback.afterFrame(100);
        assertThat(MachineState.save(machine)).isEqualTo(before);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}

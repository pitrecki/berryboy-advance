package berryboy.desktop.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import berryboy.DemoRom;
import berryboy.Machine;
import berryboy.MachineState;
import berryboy.desktop.scenario.FrameInputs;
import berryboy.video.LcdTiming;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class PlaybackProfilerTest {
    @Test
    void snapshotsOwnTheFullImageAndOnlyTheValidPcmRange() {
        var rgb = new int[240 * 160]; rgb[0] = 0x123456; rgb[rgb.length - 1] = 0x654321;
        var pcm = new short[]{1, -2, 3, -4, 99, 99};
        var owned = new ProfileOutput.Frame(rgb, pcm, 2);
        Arrays.fill(rgb, 0); Arrays.fill(pcm, (short) 0);
        assertThat(owned.rgb()[0]).isEqualTo(0x123456);
        assertThat(owned.rgb()[owned.rgb().length - 1]).isEqualTo(0x654321);
        assertThat(owned.pcm()).containsExactly((short) 1, (short) -2, (short) 3, (short) -4);
        assertThatThrownBy(() -> new ProfileOutput.Frame(new int[3], pcm, 1)).hasMessageContaining("Invalid");
        assertThatThrownBy(() -> new ProfileOutput.Frame(rgb, pcm, 4)).hasMessageContaining("Invalid");
    }

    @Test
    void outputHashesInOrderDrainsFullyAndRetainsItsClosedResult() throws Exception {
        var first = new int[240 * 160]; var second = first.clone(); Arrays.fill(second, 0xabcdef);
        var sink = new ProfileOutput();
        assertThatThrownBy(sink::result).hasMessageContaining("Close");
        sink.submit(first, new short[]{0x1234, -2}, 1);
        sink.submit(second, new short[]{3, 4, 999, 999}, 1);
        sink.close(); var result = sink.result(); sink.close();
        var chain = MessageDigest.getInstance("SHA-256");
        chain.update(MessageDigest.getInstance("SHA-256").digest(new byte[240 * 160 * 3]));
        var pixels = new byte[240 * 160 * 3];
        for (int i = 0; i < pixels.length; i += 3) { pixels[i] = (byte) 0xab; pixels[i + 1] = (byte) 0xcd; pixels[i + 2] = (byte) 0xef; }
        chain.update(MessageDigest.getInstance("SHA-256").digest(pixels));
        assertThat(result.get("rgb_chain_sha256")).isEqualTo(HexFormat.of().formatHex(chain.digest()));
        var pcm = new byte[]{0x34, 0x12, (byte) 0xfe, (byte) 0xff, 3, 0, 4, 0};
        assertThat(result.get("pcm_sha256")).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pcm)));
        assertThat(result.get("frames")).isEqualTo(2L); assertThat(result.get("audio_frames")).isEqualTo(2L);
        assertThat(result.get("submitted")).isEqualTo(2L); assertThat(result.get("processed")).isEqualTo(2L);
        assertThat(sink.result()).isSameAs(result);
    }

    @Test
    void repeatedPacedWindowsRetainEveryOutputAndDoNotChangeTheSourceSnapshot() throws Exception {
        var rom = DemoRom.create(); var machine = new Machine(rom); machine.memory.clockedAudio().setCapture(true);
        var pcm = new short[2048];
        for (int f = 1; f <= 6; f++) {
            machine.runCycles(f * (long) LcdTiming.FRAME_CYCLES - machine.clock.now());
            machine.memory.clockedAudio().drain(pcm, machine.memory.clockedAudio().availableFrames());
        }
        var state = MachineState.save(machine); var unchanged = state.clone();
        var inputs = FrameInputs.parse("6 16\n7 0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var first = PlaybackProfiler.run(rom, null, state, inputs, 6, 3);
        var second = PlaybackProfiler.run(rom, null, state, inputs, 6, 3);
        var a = (java.util.Map<?, ?>) first.get("output"); var b = (java.util.Map<?, ?>) second.get("output");
        assertThat(a.get("rgb_chain_sha256")).isEqualTo(b.get("rgb_chain_sha256"));
        assertThat(a.get("pcm_sha256")).isEqualTo(b.get("pcm_sha256"));
        assertThat(a.get("frames")).isEqualTo(3L);
        assertThat(first.get("final_state_sha256")).isEqualTo(second.get("final_state_sha256"));
        assertThat(first.get("produced_audio_frames")).isEqualTo(first.get("consumed_audio_frames"));
        assertThat(state).isEqualTo(unchanged);
        assertThat((long) first.get("wall_ns")).isGreaterThanOrEqualTo(3L * LcdTiming.FRAME_CYCLES * 1000000000L / 16777216);
    }

    @Test
    void rejectsWrongFrameAndDisabledCaptureWithoutResettingOrNormalizingTheState() {
        var rom = DemoRom.create(); var state = MachineState.save(new Machine(rom));
        var inputs = FrameInputs.parse(new byte[0]);
        assertThatThrownBy(() -> PlaybackProfiler.run(rom, null, state, inputs, 1, 1)).hasMessageContaining("frame");
        assertThatThrownBy(() -> PlaybackProfiler.run(rom, null, state, inputs, 0, 1)).hasMessageContaining("PCM capture");
    }
}

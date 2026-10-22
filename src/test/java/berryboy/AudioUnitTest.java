package berryboy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("core")
@DisplayName("Channel 2 audio and stereo registers")
class AudioUnitTest {
    private static final int FREQUENCY_512_HZ = 1792;
    private static final int BOTH_SPEAKERS = AudioUnit.CHANNEL_2_LEFT | AudioUnit.CHANNEL_2_RIGHT;
    private static final int MAX_STEREO_VOLUME = 0x77;
    private static final int MAX_AMPLITUDE = 7680;

    @ParameterizedTest(name = "duty {0}: {1} positive samples per 64-sample period")
    @CsvSource({"0, 8", "1, 16", "2, 32", "3, 48"})
    void producesTheDocumentedFrequencyAndDutyCycle(int duty, int positiveSamples) {
        var machine = tone((15 << 12) | (duty << 6), false);
        var samples = render(machine, 128);
        var left = leftChannel(samples);

        assertSoftly(softly -> {
            softly.assertThat(Arrays.stream(left, 0, 64).filter(value -> value > 0).count())
                    .as("duty cycle").isEqualTo(positiveSamples);
            softly.assertThat(Arrays.copyOfRange(left, 0, 64))
                    .as("512 Hz repeats after 64 samples at 32768 Hz")
                    .containsExactly(Arrays.copyOfRange(left, 64, 128));
            softly.assertThat(left).containsOnly(-MAX_AMPLITUDE, MAX_AMPLITUDE);
        });
    }

    @ParameterizedTest(name = "mixer ratio {0}")
    @CsvSource({"0, 1920", "1, 3840", "2, 7680"})
    void appliesPsgVolumeRatios(int ratio, int amplitude) {
        var machine = tone(0xf080, false);
        machine.memory.write16(AudioUnit.MIX_CONTROL, ratio);

        assertThat(render(machine, 1)).containsExactly((short) amplitude, (short) amplitude);
    }

    @ParameterizedTest(name = "stereo routes {0}")
    @CsvSource({"0x2000, 7680, 0", "0x0200, 0, 7680", "0, 0, 0"})
    void routesEachSpeakerIndependently(int routes, int left, int right) {
        var machine = tone(0xf080, false);
        machine.memory.write16(AudioUnit.STEREO_CONTROL, routes | MAX_STEREO_VOLUME);

        assertThat(render(machine, 1)).containsExactly((short) left, (short) right);
    }

    @Test
    void stopsAtTheLengthBoundaryAndCanRestart() {
        var machine = tone(0xf0bf, true); // Length=63: one 1/256-second tick.

        assertThat(render(machine, 128)).contains((short) MAX_AMPLITUDE);
        assertSoftly(softly -> {
            softly.assertThat(render(machine, 32)).containsOnly((short) 0);
            softly.assertThat(machine.memory.read16(AudioUnit.MASTER_CONTROL)).isEqualTo(0x80);
        });

        machine.memory.write16(AudioUnit.CHANNEL_2_FREQUENCY,
                AudioUnit.RESTART_CHANNEL | AudioUnit.ENABLE_LENGTH | FREQUENCY_512_HZ);

        assertThat(render(machine, 1)).containsExactly((short) MAX_AMPLITUDE, (short) MAX_AMPLITUDE);
    }

    @Test
    void disablingLengthKeepsTheToneActive() {
        var machine = tone(0xf0bf, false);
        render(machine, AudioUnit.SAMPLE_RATE);

        assertThat(render(machine, 64)).contains((short) MAX_AMPLITUDE, (short) -MAX_AMPLITUDE);
        assertThat(machine.memory.read16(AudioUnit.MASTER_CONTROL)).isEqualTo(0x82);
    }

    @Test
    void decreasingEnvelopeReachesSilenceWithoutClearingChannelStatus() {
        var machine = tone(0x2180, false); // Volume 2, decrease once every 1/64 second.

        assertThat(render(machine, 512)).contains((short) 1024);
        assertThat(render(machine, 512)).contains((short) 512);
        assertThat(render(machine, 512)).containsOnly((short) 0);
        assertThat(machine.memory.read16(AudioUnit.MASTER_CONTROL)).isEqualTo(0x82);
    }

    @Test
    void increasingEnvelopeSaturatesAtTheMaximumVolume() {
        var machine = tone(0xe980, false); // Volume 14, increase every 1/64 second.

        render(machine, 512 * 3);

        assertThat(render(machine, 64)).containsOnly((short) MAX_AMPLITUDE, (short) -MAX_AMPLITUDE);
    }

    @Test
    void renderingInChunksPreservesPhaseAndEnvelope() {
        var whole = tone(0xf280, false);
        var chunked = tone(0xf280, false);
        var expected = render(whole, 1024);
        var first = render(chunked, 333);
        var second = render(chunked, 691);
        var actual = new short[expected.length];
        System.arraycopy(first, 0, actual, 0, first.length);
        System.arraycopy(second, 0, actual, first.length, second.length);

        assertThat(actual).containsExactly(expected);
    }

    @Test
    void masterDisableResetsPsgStateButKeepsMixAndBias() {
        var machine = tone(0xf080, false);
        machine.memory.write16(AudioUnit.MASTER_CONTROL, 0);
        machine.memory.write16(AudioUnit.CHANNEL_2_CONTROL, 0xf080);
        machine.memory.write16(AudioUnit.CHANNEL_2_FREQUENCY, 0x8700);

        assertSoftly(softly -> {
            softly.assertThat(render(machine, 64)).containsOnly((short) 0);
            softly.assertThat(machine.memory.read16(AudioUnit.CHANNEL_2_CONTROL)).isZero();
            softly.assertThat(machine.memory.read16(AudioUnit.STEREO_CONTROL)).isZero();
            softly.assertThat(machine.memory.read16(AudioUnit.MASTER_CONTROL)).isZero();
            softly.assertThat(machine.memory.read16(AudioUnit.MIX_CONTROL)).isEqualTo(2);
            softly.assertThat(machine.memory.read16(AudioUnit.BIAS_CONTROL)).isEqualTo(0x200);
        });
    }

    @Test
    void masksWriteOnlyFieldsAndStopsWhenTheDacIsDisabled() {
        var machine = tone(0xf0bf, true);

        assertSoftly(softly -> {
            softly.assertThat(machine.memory.read16(AudioUnit.CHANNEL_2_CONTROL)).isEqualTo(0xf080);
            softly.assertThat(machine.memory.read16(AudioUnit.CHANNEL_2_FREQUENCY)).isEqualTo(0x4000);
        });
        machine.memory.write8(AudioUnit.CHANNEL_2_CONTROL + 1, 0);

        assertThat(render(machine, 32)).containsOnly((short) 0);
        assertThat(machine.memory.read16(AudioUnit.MASTER_CONTROL)).isEqualTo(0x80);
    }

    @Test
    void rejectsUnsupportedSoundHardwareAndIncompleteFrames() {
        var machine = tone(0xf080, false);

        assertSoftly(softly -> {
            softly.assertThatThrownBy(() -> machine.memory.write16(AudioUnit.MIX_CONTROL, 3))
                    .isInstanceOf(EmulationException.class).hasMessageContaining("ratio");
            softly.assertThatThrownBy(() -> machine.memory.write32(Memory.IO_BASE + 0xac, 1))
                    .isInstanceOf(EmulationException.class);
            softly.assertThatThrownBy(() -> machine.memory.write16(Memory.IO_BASE + 0x8c, 1))
                    .isInstanceOf(EmulationException.class);
        });
        assertThatThrownBy(() -> machine.audio.render(new short[3]))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stereo");
    }

    private static Machine tone(int control, boolean lengthEnabled) {
        var machine = new Machine(EmulatorFixtures.blankRom());
        machine.memory.write16(AudioUnit.MASTER_CONTROL, AudioUnit.MASTER_ENABLE);
        machine.memory.write16(AudioUnit.STEREO_CONTROL, BOTH_SPEAKERS | MAX_STEREO_VOLUME);
        machine.memory.write16(AudioUnit.MIX_CONTROL, AudioUnit.FULL_PSG_VOLUME);
        machine.memory.write16(AudioUnit.CHANNEL_2_CONTROL, control);
        machine.memory.write16(AudioUnit.CHANNEL_2_FREQUENCY,
                AudioUnit.RESTART_CHANNEL | (lengthEnabled ? AudioUnit.ENABLE_LENGTH : 0) | FREQUENCY_512_HZ);
        return machine;
    }

    private static short[] render(Machine machine, int frames) {
        var samples = new short[frames * 2];
        machine.audio.render(samples);
        return samples;
    }

    private static int[] leftChannel(short[] stereo) {
        var samples = new int[stereo.length / 2];
        for (var index = 0; index < samples.length; index++) {
            samples[index] = stereo[index * 2];
        }
        return samples;
    }
}

package berryboy.bus;

import berryboy.EmulatorFixtures;
import berryboy.Machine;
import berryboy.audio.AudioUnit;
import berryboy.cpu.Cpu;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

@Tag("core")
class DmaFifoInvariantTest {
    private static final int DMA_BASE = 0x040000b0;
    private static final int FIFO_A = 0x040000a0;
    private static final int CODE = 0x03000000;
    private static final int FIFO_CONTROL = 0xb640;
    private static final int BYTES_PER_BURST = 16;
    private static final int BURSTS = 2;

    static Stream<Arguments> fifoCounts() {
        return Stream.of(0x03000000, 0x02000000, 0x08000000)
                .flatMap(region -> Stream.of(1, 2).flatMap(channels ->
                        Stream.of(0, 1, 2, 3, 4, 5, 8, 0x3fff).flatMap(count ->
                                Stream.of(0, 1, 2, 3).map(late -> Arguments.of(region, channels, count, late)))));
    }

    @ParameterizedTest(name = "source={0}, channels={1}, ignoredCount={2}, serviceDelay={3}")
    @MethodSource("fifoCounts")
    void fifoCountDoesNotChangeBurstContentsProgressOrLatency(int region, int channels, int count, int late) {
        // Compare against count=4, without treating either emulator's absolute
        // latency as hardware evidence. FIFO count must be ignored on every burst.
        var baseline = capture(region, channels, 4, late);
        var actual = capture(region, channels, count, late);
        assertSoftly(softly -> {
            softly.assertThat(actual.latency()).containsExactly(baseline.latency());
            softly.assertThat(actual.pcm()).containsExactly(baseline.pcm());
        });
    }

    private Capture capture(int region, int channels, int count, int late) {
        var image = EmulatorFixtures.blankRom();
        for (var channel = 1; channel <= 2; channel++) {
            for (var sample = 0; sample < BURSTS * BYTES_PER_BURST; sample++) {
                image[sourceOffset(channel) + sample] = (byte) sampleValue(channel, sample);
            }
        }
        var machine = new Machine(image);
        if (region != 0x08000000) {
            for (var offset = 0x100; offset < image.length; offset++) {
                machine.memory.write8(region + offset, image[offset]);
            }
        }
        machine.memory.write16(CODE, 0x3001); // ADD r0,#1, after DMA yields the bus.
        machine.cpu.setCpsr(Cpu.SYSTEM | Cpu.T);
        machine.cpu.setPc(CODE);
        machine.memory.write16(AudioUnit.MASTER_CONTROL, AudioUnit.MASTER_ENABLE);
        // A on right, B on left, full FIFO volume; distinguish both byte streams.
        machine.memory.write16(AudioUnit.MIX_CONTROL, 0x210c);
        for (var channel = 1; channel <= channels; channel++) {
            var base = DMA_BASE + channel * 12;
            machine.memory.write32(base, region + sourceOffset(channel));
            machine.memory.write32(base + 4, FIFO_A + (channel - 1) * 4);
            machine.memory.write16(base + 8, count);
            machine.memory.write16(base + 10, FIFO_CONTROL);
        }
        var latency = new long[BURSTS];
        var pcm = new short[BURSTS * BYTES_PER_BURST * 2];
        var frame = new short[2];
        for (var burst = 0; burst < BURSTS; burst++) {
            var requestAt = machine.clock.now() + (burst == 0 ? 0 : 64);
            machine.clock.schedule(() -> {
                for (var channel = 1; channel <= channels; channel++) {
                    machine.memory.dma().requestFifo(FIFO_A + (channel - 1) * 4);
                }
            }, requestAt);
            machine.clock.advanceTo(requestAt + late);
            var before = machine.clock.now();
            for (var word = 0; word < channels * 4; word++) machine.cpu.step();
            latency[burst] = machine.clock.now() - before;
            assertSoftly(softly -> {
                softly.assertThat(machine.cpu.instructions()).isZero();
                softly.assertThat(machine.cpu.pc()).isEqualTo(CODE);
                softly.assertThat(machine.memory.dma().runPending()).isFalse();
                for (var channel = 1; channel <= channels; channel++) {
                    softly.assertThat(machine.memory.read16(DMA_BASE + channel * 12 + 10))
                            .isEqualTo(FIFO_CONTROL);
                }
            });
            var expectedRight = new short[BYTES_PER_BURST];
            var expectedLeft = new short[BYTES_PER_BURST];
            var right = new short[BYTES_PER_BURST];
            var left = new short[BYTES_PER_BURST];
            for (var sample = 0; sample < BYTES_PER_BURST; sample++) {
                // Observe the FIFO directly without requesting another DMA.
                machine.audio.timerOverflow(0);
                machine.audio.render(frame);
                left[sample] = frame[0];
                right[sample] = frame[1];
                var index = burst * BYTES_PER_BURST + sample;
                pcm[index * 2] = frame[0];
                pcm[index * 2 + 1] = frame[1];
                expectedRight[sample] = (short) (sampleValue(1, index) * 256);
                expectedLeft[sample] = channels == 2 ? (short) (sampleValue(2, index) * 256) : 0;
            }
            assertSoftly(softly -> {
                softly.assertThat(right).containsExactly(expectedRight);
                softly.assertThat(left).containsExactly(expectedLeft);
            });
            machine.audio.timerOverflow(0);
            machine.audio.render(frame);
            assertSoftly(softly -> softly.assertThat(frame).containsOnly((short) 0));
        }
        machine.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(machine.cpu.instructions()).isEqualTo(1);
            softly.assertThat(machine.cpu.register(0)).isEqualTo(1);
            softly.assertThat(machine.cpu.pc()).isEqualTo(CODE + 2);
        });
        return new Capture(latency, pcm);
    }

    private static int sourceOffset(int channel) { return 0x100 + (channel - 1) * 0x40; }
    private static int sampleValue(int channel, int sample) { return (channel - 1) * 64 + sample + 1; }
    private record Capture(long[] latency, short[] pcm) { }
}

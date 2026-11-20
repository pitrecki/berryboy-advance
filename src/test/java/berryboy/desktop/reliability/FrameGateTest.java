package berryboy.desktop.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FrameGateTest {
    @Test
    void capturesOwnedBuffersAndKeepsTheProducerBehindTheConsumerUntilRelease() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor(); var gate = new FrameGate()) {
            var rgb = new int[240 * 160]; rgb[0] = 0x123456;
            var pcm = new short[]{123, -456};
            var producer = executor.submit(() -> { gate.audio(pcm); gate.present(rgb); });
            var value = gate.await(Duration.ofSeconds(2));
            rgb[0] = 0; pcm[0] = 0;
            assertThat(value.rgb()[0]).isEqualTo(0x123456);
            assertThat(value.pcm()).containsExactly((short) 123, (short) -456);
            assertThat(value.sequence()).isEqualTo(1);
            assertThat(producer).isNotDone();
            assertThat(gate.queuedFrames()).isZero(); assertThat(gate.pendingAudioPackets()).isZero();
            assertThatThrownBy(() -> gate.await(Duration.ZERO)).hasMessageContaining("previous frame still held");
            gate.proceed(); producer.get(2, TimeUnit.SECONDS);
            assertThat(gate.presented()).isEqualTo(1);
            assertThatThrownBy(gate::proceed).hasMessageContaining("No held frame");
        }
    }

    @Test
    void closingAConsumedGateUnblocksTheProducerWithoutRequiringAnotherFrame() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor(); var gate = new FrameGate()) {
            var producer = executor.submit(() -> { gate.audio(new short[2]); gate.present(new int[38400]); });
            gate.await(Duration.ofSeconds(2)); gate.close(); gate.close(); producer.get(2, TimeUnit.SECONDS);
            assertThatThrownBy(() -> gate.await(Duration.ZERO)).hasMessageContaining("closed");
        }
    }

    @Test
    void failsFastOnMissingDuplicateOrMalformedOutputInsteadOfDroppingIt() {
        try (var gate = new FrameGate()) {
            assertThatThrownBy(() -> gate.present(new int[38400])).hasMessageContaining("no PCM");
            assertThatThrownBy(() -> gate.audio(new short[1])).isInstanceOf(IllegalArgumentException.class);
            gate.audio(new short[2]);
            assertThatThrownBy(() -> gate.audio(new short[2])).hasMessageContaining("Unconsumed PCM");
            assertThat(gate.pendingAudioPackets()).isEqualTo(1);
            assertThatThrownBy(() -> gate.present(new int[1])).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void reportsAMissingPresentationRatherThanHanging() {
        try (var gate = new FrameGate()) {
            assertThatThrownBy(() -> gate.await(Duration.ofMillis(1))).hasMessageContaining("No presented RGB/PCM");
        }
    }
}

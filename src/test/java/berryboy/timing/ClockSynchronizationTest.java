package berryboy.timing;

import berryboy.EmulatorFixtures;

import static berryboy.EmulatorFixtures.arm;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class ClockSynchronizationTest {
    @Test
    void wholeInstructionsPreserveAbsoluteAudioDeadlinesAcrossManyBatches() {
        var m = arm(0xeafffffe); // Self branch with refill costs.
        var stream = m.memory.clockedAudio();
        stream.setCapture(true);
        var output = new short[128];
        long target = 64L * 512;
        for (int batch = 0; batch < 100; batch++) {
            m.runCycles(target - m.clock.now());
            assertThat(stream.drain(output)).isEqualTo(64);
            target += 64L * 512;
        }
        assertThat(stream.producedFrames()).isEqualTo(6400);
        assertThat(stream.consumedFrames()).isEqualTo(6400);
        assertThat(stream.underruns()).isZero();
        assertThat(stream.overruns()).isZero();
        assertThat(m.memory.raster().completedFrames()).isEqualTo(11);
    }
}

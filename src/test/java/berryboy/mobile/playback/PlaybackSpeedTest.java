package berryboy.mobile.playback;

import berryboy.timing.CycleScheduler;
import java.math.BigInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("core")
class PlaybackSpeedTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8})
    void deadlinesMatchAnIndependentRationalCalculationEvenInLongSessions(int factor) {
        for (var cycles : new long[]{0, 1, 280896, CycleScheduler.CLOCK_HZ, 1000L * CycleScheduler.CLOCK_HZ, Long.MAX_VALUE}) {
            var expected = BigInteger.valueOf(cycles).multiply(BigInteger.valueOf(1000))
                    .divide(BigInteger.valueOf(16777216L * factor)).longValueExact();
            assertThat(PlaybackSpeed.wallMillis(cycles, factor)).isEqualTo(expected);
        }
    }
    @Test
    void cyclesBothDirectionsAndRejectsUnsupportedSettings() {
        var factor = 1;
        for (var expected : new int[]{2, 4, 8, 1}) {
            factor = PlaybackSpeed.cycle(factor, 1); assertThat(factor).isEqualTo(expected);
        }
        for (var expected : new int[]{8, 4, 2, 1}) {
            factor = PlaybackSpeed.cycle(factor, -1); assertThat(factor).isEqualTo(expected);
        }
        for (var invalid : new int[]{0, -1, 3, 16}) {
            assertThatThrownBy(() -> PlaybackSpeed.wallMillis(100, invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> PlaybackSpeed.presentationInterval(0, invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> PlaybackSpeed.cycle(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlaybackSpeed.wallMillis(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlaybackSpeed.presentationInterval(6, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlaybackSpeed.presentationInterval(-1, 8)).isInstanceOf(IllegalArgumentException.class);
    }
}

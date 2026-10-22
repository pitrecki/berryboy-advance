package berryboy.timing;

import berryboy.EmulationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class CycleSchedulerTest {
    @Test
    void dispatchesExactDeadlinesAndStableTiesIncludingNewEvents() {
        var clock = new CycleScheduler();
        var trace = new ArrayList<String>();
        clock.schedule(() -> {
            trace.add("first@" + clock.now());
            clock.schedule(() -> trace.add("new@" + clock.now()), clock.now());
        }, 10);
        clock.schedule(() -> trace.add("second@" + clock.now()), 10);
        clock.schedule(() -> trace.add("early@" + clock.now()), 4);
        clock.advanceTo(9);
        assertThat(trace).containsExactly("early@4");
        clock.advanceTo(10);
        assertThat(trace).containsExactly("early@4", "first@10", "second@10", "new@10");
        assertThat(clock.hasEvents()).isFalse();
    }

    @Test
    void replacesCancelsAndGrowsWithoutLosingOrder() {
        var clock = new CycleScheduler();
        var trace = new ArrayList<Integer>();
        CycleEvent replaced = () -> trace.add(-1);
        clock.schedule(replaced, 1);
        clock.schedule(replaced, 100);
        CycleEvent cancelled = () -> trace.add(-2);
        clock.schedule(cancelled, 1);
        clock.cancel(cancelled);
        for (int i = 0; i < 40; i++) {
            int value = i;
            clock.schedule(() -> trace.add(value), 2);
        }
        clock.advanceTo(2);
        assertThat(trace).containsExactlyElementsOf(java.util.stream.IntStream.range(0, 40).boxed().toList());
        assertThat(clock.nextDeadline()).isEqualTo(100);
        clock.advanceTo(100);
        assertThat(trace).endsWith(-1);
    }

    @Test
    void repeatingDeviceIsIndependentOfAdvanceBatchSizeAndResetClearsEvents() {
        assertThat(replay(1)).isEqualTo(replay(100));
        var clock = new CycleScheduler();
        clock.schedule(() -> { throw new AssertionError("stale callback"); }, 20);
        clock.advance(10);
        clock.reset();
        assertThat(clock.now()).isZero();
        assertThat(clock.hasEvents()).isFalse();
        clock.advance(30);
    }

    @Test
    void supportsLongCycleCountsAndRejectsOverflowAndPastDeadlines() {
        var clock = new CycleScheduler();
        var fired = new ArrayList<Long>();
        clock.schedule(() -> fired.add(clock.now()), (long) Integer.MAX_VALUE + 7);
        clock.advanceTo((long) Integer.MAX_VALUE + 8);
        assertThat(fired).containsExactly((long) Integer.MAX_VALUE + 7);
        assertThatThrownBy(() -> clock.advance(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clock.schedule(() -> {}, 1)).isInstanceOf(IllegalArgumentException.class);
        clock.advanceTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> clock.advance(1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedCallbackLeavesClockAtFailureAndAllowsRecovery() {
        var clock = new CycleScheduler();
        clock.schedule(() -> clock.advance(1), 5);
        assertThatThrownBy(() -> clock.advanceTo(10)).isInstanceOf(EmulationException.class);
        assertThat(clock.now()).isEqualTo(5);
        clock.advanceTo(10);
        assertThat(clock.now()).isEqualTo(10);
    }

    private List<Long> replay(int batch) {
        var clock = new CycleScheduler();
        var trace = new ArrayList<Long>();
        CycleEvent repeating = new CycleEvent() {
            public void fire() {
                trace.add(clock.now());
                clock.schedule(this, clock.now() + 7);
            }
        };
        clock.schedule(repeating, 7);
        for (int i = 0; i < 100; i += batch) clock.advance(batch);
        return trace;
    }
}

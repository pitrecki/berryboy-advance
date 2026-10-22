package berryboy.desktop.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedWorkerTest {
    @Test
    void processesOnAnotherThreadInOrderAndBackpressuresWithoutDropping() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempting = new CountDownLatch(1);
        var output = new ArrayList<Integer>();
        var names = new ArrayList<String>();
        var worker = new BoundedWorker<Integer>("test-output", 1, value -> {
            if (value == 1) { entered.countDown(); release.await(); }
            output.add(value); names.add(Thread.currentThread().getName());
        });
        try (var producer = Executors.newSingleThreadExecutor()) {
            try {
            worker.submit(1);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.submit(2);
            var third = producer.submit(() -> { attempting.countDown(); worker.submit(3); return true; });
            assertThat(attempting.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(third.isDone()).isFalse();
            release.countDown();
            assertThat(third.get(5, TimeUnit.SECONDS)).isTrue();
            worker.close();
            } finally { release.countDown(); }
        } finally { worker.close(); }
        assertThat(output).containsExactly(1, 2, 3);
        assertThat(names).containsOnly("test-output");
        assertThatThrownBy(() -> worker.submit(4)).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
    }

    @Test
    void consumerFailureReachesCloseAndFutureProducers() throws Exception {
        var original = new java.io.IOException("disk unavailable");
        var worker = new BoundedWorker<Integer>("failed-output", 1, value -> { throw original; });
        worker.submit(1);
        assertThatThrownBy(worker::close).isInstanceOf(IllegalStateException.class).hasCause(original);
        assertThatThrownBy(() -> worker.submit(2)).isInstanceOf(IllegalStateException.class).hasCause(original);
    }

    @Test
    void closeDrainsAndPreservesCallerInterruptStatus() throws Exception {
        var output = new ArrayList<Integer>();
        var worker = new BoundedWorker<Integer>("draining-output", 2, output::add);
        worker.submit(1); worker.submit(2);
        Thread.currentThread().interrupt();
        try {
            worker.close();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(output).isEqualTo(List.of(1, 2));
        } finally { Thread.interrupted(); }
    }

    @Test
    void failedConsumerWakesAProducerBlockedOnCapacity() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new BoundedWorker<Integer>("blocked-failure", 1, value -> {
            entered.countDown(); release.await(); throw new java.io.IOException("disk failure");
        });
        try (var producer = Executors.newSingleThreadExecutor()) {
            try {
                worker.submit(1);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                worker.submit(2);
                var third = producer.submit(() -> { worker.submit(3); return true; });
                release.countDown();
                assertThatThrownBy(() -> third.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class).hasRootCauseMessage("disk failure");
            } finally { release.countDown(); }
        } finally {
            assertThatThrownBy(worker::close).isInstanceOf(IllegalStateException.class).hasRootCauseMessage("disk failure");
        }
    }
}

package berryboy.desktop.concurrent;

import java.util.ArrayDeque;
import java.util.Objects;

/** Ordered host work with bounded buffering, producer backpressure and failure propagation. */
public final class BoundedWorker<T> implements AutoCloseable {
    @FunctionalInterface
    public interface Processor<T> {
        void process(T value) throws Exception;
    }

    private final ArrayDeque<T> queue = new ArrayDeque<>();
    private final int capacity;
    private final Processor<T> processor;
    private final Thread worker;
    private boolean closing;
    private Throwable failure;

    public BoundedWorker(String name, int capacity, Processor<T> processor) {
        if (capacity < 1) throw new IllegalArgumentException("Positive queue capacity required");
        this.capacity = capacity;
        this.processor = Objects.requireNonNull(processor);
        worker = Thread.ofPlatform().daemon().name(name).unstarted(this::run);
        worker.start();
    }

    /** Ownership transfers to the consumer; callers must supply immutable/owned data. */
    public synchronized void submit(T value) throws InterruptedException {
        Objects.requireNonNull(value);
        while (queue.size() == capacity && !closing && failure == null) wait();
        checkFailure();
        if (closing) throw new IllegalStateException("Host worker is closed");
        queue.addLast(value);
        notifyAll();
    }

    public synchronized void checkFailure() {
        if (failure != null) throw new IllegalStateException("Host output worker failed", failure);
    }

    private void run() {
        try {
            while (true) {
                T value;
                synchronized (this) {
                    while (queue.isEmpty() && !closing) wait();
                    if (queue.isEmpty()) return;
                    value = queue.removeFirst();
                    notifyAll();
                }
                processor.process(value);
            }
        } catch (Throwable error) {
            synchronized (this) {
                failure = error;
                closing = true;
                queue.clear();
                notifyAll();
            }
        }
    }

    /** Stop accepting, drain accepted work, join, then propagate any consumer error. */
    @Override
    public void close() {
        if (Thread.currentThread() == worker) throw new IllegalStateException("Worker cannot join itself");
        synchronized (this) { closing = true; notifyAll(); }
        boolean interrupted = false;
        while (worker.isAlive()) {
            try { worker.join(); } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        checkFailure();
    }
}

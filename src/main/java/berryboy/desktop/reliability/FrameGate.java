package berryboy.desktop.reliability;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Host capture barrier: one owned RGB/PCM frame, released explicitly before the next guest frame. */
public final class FrameGate implements AutoCloseable {
    /** Arrays belong to the consumer, never the live guest or the presentation adapter. */
    public record Frame(long sequence, int[] rgb, short[] pcm) { }
    private final ArrayBlockingQueue<Frame> ready = new ArrayBlockingQueue<>(1);
    private final AtomicReference<short[]> audio = new AtomicReference<>();
    private final AtomicBoolean held = new AtomicBoolean(), closed = new AtomicBoolean();
    private final Semaphore next = new Semaphore(0);
    private volatile long presented;

    public void audio(short[] ownedPcm) {
        if (closed.get()) return;
        if ((ownedPcm.length & 1) != 0 || ownedPcm.length == 0 || ownedPcm.length > 2048)
            throw new IllegalArgumentException("Expected one nonempty stereo PCM frame");
        if (!audio.compareAndSet(null, ownedPcm.clone())) throw new IllegalStateException("Unconsumed PCM packet");
    }
    /** Runs at the platform-double presentation boundary on the unchanged emulation owner. */
    public void present(int[] ownedRgb) {
        if (closed.get()) return;
        if (ownedRgb.length != 240 * 160) throw new IllegalArgumentException("Expected a complete GBA image");
        var pcm = audio.getAndSet(null);
        if (pcm == null) throw new IllegalStateException("Presentation has no PCM packet");
        if (!ready.offer(new Frame(++presented, ownedRgb.clone(), pcm)))
            throw new IllegalStateException("Unconsumed presented frame");
        try {
            if (!next.tryAcquire(30, TimeUnit.SECONDS)) throw new IllegalStateException("Presentation barrier timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Presentation interrupted", error);
        }
    }
    public Frame await(Duration timeout) throws InterruptedException {
        if (closed.get() || held.get()) throw new IllegalStateException("Gate closed or previous frame still held");
        var frame = ready.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        if (frame == null) throw new IllegalStateException("No presented RGB/PCM frame before timeout");
        held.set(true); return frame;
    }
    public void proceed() {
        if (closed.get() || !held.compareAndSet(true, false)) throw new IllegalStateException("No held frame to release");
        next.release();
    }
    public long presented() { return presented; }
    public int pendingAudioPackets() { return audio.get() == null ? 0 : 1; }
    public int queuedFrames() { return ready.size(); }
    @Override public void close() {
        if (closed.compareAndSet(false, true)) { audio.set(null); next.release(); }
    }
}

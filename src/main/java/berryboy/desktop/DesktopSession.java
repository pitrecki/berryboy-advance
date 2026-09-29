package berryboy.desktop;

import berryboy.AudioUnit;
import berryboy.Machine;
import berryboy.Memory;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import javax.sound.sampled.LineUnavailableException;
import javax.swing.SwingUtilities;

/** Owns the machine on one worker thread; Swing only receives frame snapshots and input. */
final class DesktopSession implements AutoCloseable {
    private static final int AUDIO_FRAMES_PER_BATCH = 512;
    private static final int INSTRUCTIONS_PER_BATCH = 10000;
    private static final long BATCH_NANOS = 1_000_000_000L * AUDIO_FRAMES_PER_BATCH / AudioUnit.SAMPLE_RATE;

    private final Machine machine;
    private final KeyboardInput input;
    private final DesktopPanel panel;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;
    private volatile PcmAudio audio;

    DesktopSession(Machine machine, KeyboardInput input, DesktopPanel panel) {
        this.machine = machine;
        this.input = input;
        this.panel = panel;
        worker = Thread.ofPlatform().daemon().name("berryboy-emulation").unstarted(this::run);
    }

    void start() {
        worker.start();
    }

    private void run() {
        var samples = new short[AUDIO_FRAMES_PER_BATCH * 2];
        var audioStatus = openAudio();
        try {
            while (running.get()) {
                var started = System.nanoTime();
                machine.memory.setPressedKeys(input.pressedButtons());
                machine.runInstructions(INSTRUCTIONS_PER_BATCH);
                machine.audio.render(samples);
                if (audio != null) {
                    audio.write(samples);
                }
                presentFrame(audioStatus);
                var remaining = BATCH_NANOS - (System.nanoTime() - started);
                if (remaining > 0) {
                    LockSupport.parkNanos(remaining);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException failure) {
            reportFailure(failure.getCause());
        } catch (RuntimeException failure) {
            reportFailure(failure);
        } finally {
            running.set(false);
            input.releaseAll();
            machine.memory.setPressedKeys(0);
            if (audio != null) {
                audio.close();
            }
        }
    }

    private String openAudio() {
        try {
            audio = PcmAudio.open();
            return "Audio: channel 2 / stereo PCM";
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException unavailable) {
            return "Audio device unavailable / emulation continues silently";
        }
    }

    private void presentFrame(String audioStatus) throws InterruptedException, InvocationTargetException {
        var pixels = machine.video.render().clone();
        var keys = machine.memory.read16(Memory.KEY_INPUT);
        // Apply backpressure instead of skipping presentations or building an unbounded queue.
        SwingUtilities.invokeAndWait(() ->
                panel.present(pixels, keys, audioStatus + " / experimental instruction pacing"));
    }

    private void reportFailure(Throwable failure) {
        SwingUtilities.invokeLater(() -> panel.setStatus("Stopped: " + failure.getMessage()));
    }

    @Override
    public void close() {
        running.set(false);
        input.releaseAll();
        worker.interrupt();
        if (audio != null) {
            audio.close();
        }
    }
}

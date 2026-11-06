package berryboy.phone;

import berryboy.mobile.audio.PcmWave;
import java.io.ByteArrayInputStream;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;

/** Bounded MMAPI packet backend. Gapless output and latency require device qualification. */
final class PhoneAudio implements Runnable, PlayerListener {
    private final byte[][] queue = new byte[2][];
    private final short[] pending = new short[PcmWave.MAX_FRAMES * 2];
    private int frames;
    private int head;
    private int size;
    private boolean alive = true;
    private boolean ended;
    private int generation;
    private Player current;
    private String failure;

    PhoneAudio() { new Thread(this).start(); }
    synchronized String failure() { return failure; }
    void append(short[] pcm, int count) throws InterruptedException {
        for (int i = 0; i < count; i++) {
            pending[frames * 2] = pcm[i * 2]; pending[frames * 2 + 1] = pcm[i * 2 + 1];
            if (++frames == PcmWave.MAX_FRAMES) {
                byte[] packet = PcmWave.encode(pending, frames); frames = 0;
                synchronized (this) {
                    while (size == queue.length && alive && failure == null) wait();
                    if (!alive || failure != null) return;
                    queue[(head + size) % queue.length] = packet; size++; notifyAll();
                }
            }
        }
    }
    void reset() {
        Player player;
        synchronized (this) {
            generation++; frames = 0; size = 0; head = 0;
            failure = null;
            queue[0] = null; queue[1] = null;
            player = current; current = null; ended = true; notifyAll();
        }
        if (player != null) player.close();
    }
    void close() { synchronized (this) { alive = false; notifyAll(); } reset(); }
    public void playerUpdate(Player player, String event, Object data) {
        synchronized (this) {
            if (player == current && (PlayerListener.END_OF_MEDIA.equals(event) || PlayerListener.ERROR.equals(event))) {
                if (PlayerListener.ERROR.equals(event)) failure = "Audio: " + data;
                ended = true; notifyAll();
            }
        }
    }
    public void run() {
        while (true) {
            byte[] packet; int epoch;
            synchronized (this) {
                try { while (alive && size == 0) wait(); } catch (InterruptedException ignored) { }
                if (!alive) return;
                packet = queue[head]; queue[head] = null; head = (head + 1) % queue.length; size--;
                epoch = generation; notifyAll();
            }
            Player player = null;
            try {
                player = Manager.createPlayer(new ByteArrayInputStream(packet), "audio/x-wav");
                player.addPlayerListener(this);
                synchronized (this) {
                    if (!alive || epoch != generation) { player.close(); continue; }
                    current = player; ended = false;
                }
                player.start();
                long deadline = System.currentTimeMillis() + 3000;
                synchronized (this) {
                    while (alive && current == player && !ended && System.currentTimeMillis() < deadline) wait(100);
                    if (alive && current == player && !ended) throw new IllegalStateException("Audio playback timed out");
                }
            } catch (Exception error) {
                synchronized (this) {
                    if (alive && epoch == generation) { failure = "Audio unavailable: " + error.toString(); size = 0; queue[0] = null; queue[1] = null; }
                    notifyAll();
                }
            } finally {
                if (player != null) player.close();
                synchronized (this) { if (current == player) current = null; }
            }
        }
    }
}

package berryboy.desktop.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioCaptureTest {
    @TempDir Path directory;

    @Test
    void frameSnapshotOwnsBothBuffersBeforeProducerCanReuseThem() {
        var pixels = new int[240 * 160]; pixels[0] = 0xff0000;
        byte[] pcm = {1, 2, 3, 4, 9, 9, 9, 9};
        var frame = new ScenarioCapture.Frame(1, 10, 20, 31, pixels, pcm, 4, true, true);
        pixels[0] = 0; pcm[0] = 0;
        assertThat(frame.pixels()[0]).isEqualTo(0xff0000);
        assertThat(frame.pcm()).containsExactly(1, 2, 3, 4);
        assertThat(frame.pixels()).isNotSameAs(pixels);
        assertThat(frame.pcm()).isNotSameAs(pcm);
    }

    @Test
    void videoCheckpointOwnsStateBeforeProducerReusesTheBuffer() throws Exception {
        var state = new byte[]{1, 2, 3};
        var frame = new ScenarioCapture.Frame(1, 0, 0, 0, new int[240 * 160], new byte[0], 0, false, false, state);
        state[0] = 9;
        assertThat(frame.videoState()).containsExactly(1, 2, 3);
        var capture = new ScenarioCapture(directory);
        capture.submit(1, 0, 0, 0, frame.pixels(), frame.pcm(), 0, false, false, frame.videoState());
        frame.videoState()[1] = 9;
        capture.close();
        assertThat(Files.readAllBytes(directory.resolve("video-state-1.bin"))).containsExactly(1, 2, 3);
        assertThat(directory.resolve("frame-1.png")).exists();
    }

    @Test
    void closeJoinsCaptureAndFinalizesWaveAndBothSingleFrameCheckpoints() throws Exception {
        var pixels = new int[240 * 160]; pixels[0] = 0xff0000;
        var capture = new ScenarioCapture(directory);
        capture.submit(1, 10, 20, 31, pixels, new byte[]{1, 2, 3, 4}, 4, true, true);
        capture.close();
        assertThat(Files.size(directory.resolve("audio.wav"))).isEqualTo(48);
        assertThat(ImageIO.read(directory.resolve("first.png").toFile()).getRGB(0, 0) & 0xffffff).isEqualTo(0xff0000);
        assertThat(Files.readAllBytes(directory.resolve("first.png"))).containsExactly(Files.readAllBytes(directory.resolve("last.png")));
        assertThat(Files.readAllLines(directory.resolve("frames.jsonl"))).hasSize(1);
        assertThat(capture.result().videoSha256()).hasSize(64);
        assertThat(capture.result().audioSha256()).hasSize(64);
    }
}

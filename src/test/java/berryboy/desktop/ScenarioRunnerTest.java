package berryboy.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import berryboy.DemoRom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioRunnerTest {
    @TempDir Path directory;

    @Test
    void repeatedOriginalRomScenarioHasIdenticalFramesAudioAndGuestTrace() throws Exception {
        var config = config("first");
        var first = ScenarioRunner.run(config, directory);
        config.setProperty("output", "second");
        var second = ScenarioRunner.run(config, directory);
        assertThat(first.get("video_sha256")).isEqualTo(second.get("video_sha256"));
        assertThat(first.get("audio_sha256")).isEqualTo(second.get("audio_sha256"));
        assertThat(Files.readString(directory.resolve("first/frames.jsonl")))
                .isEqualTo(Files.readString(directory.resolve("second/frames.jsonl")));
        assertThat(first).containsEntry("status", "captured").containsEntry("correctness", "unverified")
                .containsEntry("audio_frames", 1645L).containsEntry("audio_underruns", 0L)
                .containsEntry("audio_overruns", 0L).containsEntry("physically_presented_frames", null);
        try (var wave = AudioSystem.getAudioInputStream(directory.resolve("first/audio.wav").toFile())) {
            assertThat(wave.getFrameLength()).isEqualTo(1645);
            assertThat(wave.getFormat().getSampleRate()).isEqualTo(32768);
        }
        config.setProperty("output", "reference");
        config.setProperty("reference_video_sha256", first.get("video_sha256").toString());
        config.setProperty("reference_audio_sha256", first.get("audio_sha256").toString());
        assertThat(ScenarioRunner.run(config, directory)).containsEntry("correctness", "reference-matched");
    }

    @Test
    void wrongRomOrInputHashFailsBeforeCreatingOutputs() throws Exception {
        var romConfig = config("bad"); romConfig.setProperty("rom_sha256", "0".repeat(64));
        assertThatThrownBy(() -> ScenarioRunner.run(romConfig, directory)).hasMessageContaining("SHA-256");
        assertThat(directory.resolve("bad")).doesNotExist();
        var config = config("bad");
        Files.writeString(directory.resolve("input.tsv"), "0 1 0 0 0 0\n");
        config.setProperty("input", "input.tsv"); config.setProperty("input_sha256", "0".repeat(64));
        var inputConfig = config;
        assertThatThrownBy(() -> ScenarioRunner.run(inputConfig, directory)).hasMessageContaining("SHA-256");
    }

    @Test
    void unorderedInputStaleOutputsAndMismatchingReferencesFailExplicitly() throws Exception {
        var config = config("stale");
        Files.createDirectory(directory.resolve("stale"));
        Files.writeString(directory.resolve("stale/report.json"), "old");
        assertThatThrownBy(() -> ScenarioRunner.run(config, directory)).hasMessageContaining("empty");
        assertThat(Files.readString(directory.resolve("stale/report.json"))).isEqualTo("old");
        var input = "10 0 0 0 0 0\n0 0 0 0 0 0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(directory.resolve("input.tsv"), input);
        config.setProperty("output", "bad-input"); config.setProperty("input", "input.tsv");
        config.setProperty("input_sha256", ScenarioRunner.hash(input));
        assertThatThrownBy(() -> ScenarioRunner.run(config, directory)).hasMessageContaining("input event");
        config.remove("input"); config.remove("input_sha256"); config.setProperty("output", "bad-reference");
        config.setProperty("reference_video_sha256", "0".repeat(64));
        config.setProperty("reference_audio_sha256", "0".repeat(64));
        assertThatThrownBy(() -> ScenarioRunner.run(config, directory)).hasMessageContaining("Reference capture mismatch");
    }

    private Properties config(String output) throws Exception {
        var rom = DemoRom.create(); Files.write(directory.resolve("demo.gba"), rom);
        var properties = new Properties();
        properties.setProperty("rom", "demo.gba"); properties.setProperty("rom_sha256", ScenarioRunner.hash(rom));
        properties.setProperty("frames", "3"); properties.setProperty("output", output);
        return properties;
    }
}

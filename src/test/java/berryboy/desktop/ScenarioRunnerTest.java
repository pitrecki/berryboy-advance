package berryboy.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import berryboy.DemoRom;
import berryboy.EmulatorFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioRunnerTest {
    @TempDir Path directory;

    @Test
    void eventDiagnosticsAreRepeatableAndPreserveFramesPcmAndCpuCheckpoints() throws Exception {
        var config = config("untraced");
        var untraced = ScenarioRunner.run(config, directory);
        config.setProperty("output", "traced"); config.setProperty("event_trace", "true");
        config.setProperty("trace_events", "all"); config.setProperty("trace_end_cycle", "10000");
        var traced = ScenarioRunner.run(config, directory);
        assertThat(traced.get("video_sha256")).isEqualTo(untraced.get("video_sha256"));
        assertThat(traced.get("audio_sha256")).isEqualTo(untraced.get("audio_sha256"));
        assertThat(Files.readString(directory.resolve("traced/frames.jsonl")))
                .isEqualTo(Files.readString(directory.resolve("untraced/frames.jsonl")));
        var events = Files.readString(directory.resolve("traced/events.tsv"));
        assertThat(events).contains("\tbefore\t", "\tafter\t", "berryboy.video.LcdTiming");
        config.setProperty("output", "repeated");
        ScenarioRunner.run(config, directory);
        assertThat(Files.readString(directory.resolve("repeated/events.tsv"))).isEqualTo(events);
        assertThat(traced.get("event_trace").toString()).contains("status=complete", "dropped_rows=0");
    }

    @Test
    void boundedTraceReportsTruncationWithoutChangingTheGuestCapture() throws Exception {
        var config = config("bounded");
        config.setProperty("event_trace", "true"); config.setProperty("trace_capacity", "1");
        config.setProperty("trace_start_cycle", "960"); config.setProperty("trace_end_cycle", "961");
        var report = ScenarioRunner.run(config, directory);
        var lines = Files.readAllLines(directory.resolve("bounded/events.tsv"));
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).startsWith("960\t960\tbefore\t");
        assertThat(report.get("event_trace").toString()).contains("status=truncated", "rows=1", "dropped_rows=1");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "event_trace,yes", "trace_capacity,0", "trace_capacity,1000001",
            "trace_start_cycle,-1", "trace_end_cycle,0", "trace_end_cycle,999999999", "trace_events,cpu"
    })
    void invalidEventDiagnosticsFailBeforeCreatingOutputs(String key, String value) throws Exception {
        var config = config("invalid-trace");
        config.setProperty("event_trace", "true"); config.setProperty(key, value);
        assertThatThrownBy(() -> ScenarioRunner.run(config, directory)).isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("invalid-trace")).doesNotExist();
    }


    @Test
    void selectedCheckpointsPreserveCaptureHashesAndIncludeOwnedVideoState() throws Exception {
        var config = config("baseline");
        var baseline = ScenarioRunner.run(config, directory);
        config.setProperty("output", "checkpoints"); config.setProperty("checkpoints", "2,3");
        var captured = ScenarioRunner.run(config, directory);
        assertThat(captured.get("video_sha256")).isEqualTo(baseline.get("video_sha256"));
        assertThat(captured.get("audio_sha256")).isEqualTo(baseline.get("audio_sha256"));
        assertThat(directory.resolve("checkpoints/frame-1.png")).doesNotExist();
        assertThat(directory.resolve("checkpoints/frame-2.png")).exists();
        assertThat(Files.readAllBytes(directory.resolve("checkpoints/frame-3.png")))
                .isEqualTo(Files.readAllBytes(directory.resolve("checkpoints/last.png")));
        var bytes = Files.readAllBytes(directory.resolve("checkpoints/video-state-2.bin"));
        assertThat(bytes).hasSize(100478);
        assertThat(new String(bytes, 0, 8, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("BBVID001");
        var regions = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        regions.position(8);
        assertThat(regions.getInt()).isEqualTo(0x04000000);
        assertThat(regions.getInt()).isEqualTo(0x56);
        assertThat(Files.readAllLines(directory.resolve("checkpoints/frames.jsonl")).get(1))
                .contains("\"video_state_sha256\":\"" + ScenarioRunner.hash(bytes) + "\"");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "4", "2,2", "2,", "-1", "x"})
    void invalidCheckpointsFailBeforeCreatingOutputs(String checkpoints) throws Exception {
        var config = config("bad-checkpoints"); config.setProperty("checkpoints", checkpoints);
        assertThatThrownBy(() -> ScenarioRunner.run(config, directory)).isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("bad-checkpoints")).doesNotExist();
    }

    @Test
    void gameWrittenBackupPersistsAndNextMachineReportsTheExactLoadedImage() throws Exception {
        var config = config("write");
        var rom = EmulatorFixtures.blankRom();
        EmulatorFixtures.writeWord(rom, 0, 0xe59f0008); // LDR r0,[pc,#8].
        EmulatorFixtures.writeWord(rom, 4, 0xe3a0102a); // MOV r1,#42.
        EmulatorFixtures.writeWord(rom, 8, 0xe5c01000); // STRB r1,[r0].
        EmulatorFixtures.writeWord(rom, 12, 0xeafffffe); // Wait without further writes.
        EmulatorFixtures.writeWord(rom, 16, 0x0e000007);
        Files.write(directory.resolve("demo.gba"), rom);
        config.setProperty("rom_sha256", ScenarioRunner.hash(rom));
        config.setProperty("save", "game.sav");
        var first = ScenarioRunner.run(config, directory);
        var saved = Files.readAllBytes(directory.resolve("game.sav"));
        assertThat(saved).hasSize(32768);
        assertThat(saved[7]).isEqualTo((byte) 42);
        assertThat(first).containsEntry("initial_save_sha256", null).containsEntry("save_written", true)
                .containsEntry("backup_sha256", ScenarioRunner.hash(saved)).containsEntry("backup_bytes", 32768);
        EmulatorFixtures.writeWord(rom, 0, 0xeafffffe); // Fresh machine only waits.
        Files.write(directory.resolve("demo.gba"), rom);
        config.setProperty("rom_sha256", ScenarioRunner.hash(rom));
        config.setProperty("output", "reload");
        var second = ScenarioRunner.run(config, directory);
        assertThat(second).containsEntry("initial_save_sha256", ScenarioRunner.hash(saved))
                .containsEntry("backup_sha256", ScenarioRunner.hash(saved)).containsEntry("save_written", false);
        assertThat(Files.readAllBytes(directory.resolve("game.sav"))).isEqualTo(saved);
    }

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

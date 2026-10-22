package berryboy.desktop.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import berryboy.EmulatorFixtures;
import berryboy.Machine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuestEventTraceTest {
    @TempDir Path directory;

    @Test
    void capturesExactLcdTransitionsAndSerializesOnlyCopiedState() throws Exception {
        var rom = EmulatorFixtures.blankRom();
        EmulatorFixtures.writeWord(rom, 0, 0xeafffffe);
        var machine = new Machine(rom);
        var config = new Properties(); config.setProperty("event_trace", "true");
        var trace = GuestEventTrace.from(config, machine, 2000);
        machine.clock.setObserver(trace); machine.runCycles(1500); machine.clock.setObserver(null);
        machine.memory.lcd().write8(0x04000004, 0x38); // Must not rewrite earlier snapshots.
        trace.write(directory.resolve("events.tsv"));
        var lcd = Files.readAllLines(directory.resolve("events.tsv")).stream()
                .filter(line -> line.contains("berryboy.video.LcdTiming")).map(line -> line.split("\t")).toList();
        assertThat(lcd).hasSize(4);
        assertSoftly(softly -> {
            softly.assertThat(lcd.get(0)[0]).isEqualTo("960");
            softly.assertThat(lcd.get(0)[2]).isEqualTo("before");
            softly.assertThat(lcd.get(0)[7]).isEqualTo("0");
            softly.assertThat(lcd.get(0)[8]).isEqualTo("4");
            softly.assertThat(lcd.get(1)[8]).isEqualTo("6");
            softly.assertThat(lcd.get(2)[0]).isEqualTo("1232");
            softly.assertThat(lcd.get(2)[7]).isEqualTo("0");
            softly.assertThat(lcd.get(3)[7]).isEqualTo("1");
            softly.assertThat(lcd.get(3)[8]).isEqualTo("0");
        });
    }

    @Test
    void disabledTracingDoesNotAllocateAndRejectsOrphanSettings() {
        var config = new Properties();
        assertThat(GuestEventTrace.from(config, null, 100)).isNull();
        config.setProperty("trace_capacity", "1");
        assertThatThrownBy(() -> GuestEventTrace.from(config, null, 100))
                .hasMessageContaining("requires event_trace=true");
    }
}

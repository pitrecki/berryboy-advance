package berryboy.desktop.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import berryboy.EmulatorFixtures;
import berryboy.Machine;
import berryboy.MachineState;
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
    void affineSnapshotsPreserveUnsignedRegistersWithoutChangingGuestState() throws Exception {
        var rom = EmulatorFixtures.blankRom(); EmulatorFixtures.writeWord(rom, 0, 0xeafffffe);
        var m = new Machine(rom);
        m.memory.write16(0x0400000c, 0x1234);
        m.memory.write16(0x04000020, 0x0100); m.memory.write16(0x04000022, 0xff80);
        m.memory.write16(0x04000024, 0x0010); m.memory.write16(0x04000026, 0x0200);
        m.memory.write32(0x04000028, 0x00123456); m.memory.write32(0x0400002c, 0x0fedcba9);
        var control = MachineState.load(rom, null, MachineState.save(m));
        var config = new Properties(); config.setProperty("event_trace", "true");
        var trace = GuestEventTrace.from(config, m, 2000);
        m.clock.setObserver(trace); m.runCycles(1400); m.clock.setObserver(null);
        control.runCycles(1400);
        assertThat(MachineState.save(m)).isEqualTo(MachineState.save(control));
        m.memory.write32(0x04000028, 0); m.memory.write16(0x04000022, 0);
        var output = directory.resolve("affine.tsv"); trace.write(output);
        var lines = Files.readAllLines(output);
        var header = java.util.List.of(lines.get(0).split("\t"));
        var row = lines.get(1).split("\t");
        assertSoftly(s -> {
            s.assertThat(row[header.indexOf("bg2cnt")]).isEqualTo("4660");
            s.assertThat(row[header.indexOf("bg2pa")]).isEqualTo("256");
            s.assertThat(row[header.indexOf("bg2pb")]).isEqualTo("65408");
            s.assertThat(row[header.indexOf("bg2pc")]).isEqualTo("16");
            s.assertThat(row[header.indexOf("bg2pd")]).isEqualTo("512");
            s.assertThat(row[header.indexOf("bg2x")]).isEqualTo("1193046");
            s.assertThat(row[header.indexOf("bg2y")]).isEqualTo("267242409");
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

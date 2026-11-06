package berryboy.bus;

import berryboy.EmulatorFixtures;
import berryboy.Machine;
import berryboy.MachineState;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@Tag("core")
class DmaRepeatCountTest {
    private static final int DMA = 0x040000b0;
    private static final int SOURCE = 0x03000100;
    private static final int DESTINATION = 0x03001000;
    private static final int IRQ_FLAGS = 0x04000202;

    static Stream<Arguments> repeats() {
        return Stream.of(0, 1, 2, 3).flatMap(channel ->
                Stream.of(2, 4).flatMap(width ->
                        Stream.of(1, 3, 0, 0x4002).flatMap(count ->
                                Stream.of(0, 1, 2).map(stage -> Arguments.of(channel, width, count, stage)))));
    }

    @ParameterizedTest(name = "channel={0}, width={1}, newCount={2}, writeStage={3}")
    @MethodSource("repeats")
    void repeatLatchesCurrentCountAtCompletionWithoutChangingTheActiveBurst(
            int channel, int width, int count, int stage) {
        // Independently observed by tools/nba-dma-repeat-count-probe.cc:
        // changes before/during completion affect the next burst, but a write
        // between completed bursts only affects the following reload.
        var m = new Machine(EmulatorFixtures.blankRom());
        var base = DMA + channel * 12;
        var control = width == 4 ? 0xe740 : 0xe340; // HBlank repeat, IRQ, fixed addresses.
        m.memory.write32(SOURCE, 0x04030201);
        m.memory.write32(base, SOURCE);
        m.memory.write32(base + 4, DESTINATION);
        m.memory.write16(base + 8, 2);
        m.memory.write16(base + 10, control);
        if (stage == 0) m.memory.write16(base + 8, count);
        var nextCount = count & (channel == 3 ? 0xffff : 0x3fff);
        if (nextCount == 0) nextCount = channel == 3 ? 0x10000 : 0x4000;
        for (var burst = 0; burst < 3; burst++) {
            var expected = burst == 0 || (stage == 2 && burst == 1) ? 2 : nextCount;
            m.memory.write16(IRQ_FLAGS, 0xffff);
            m.memory.write32(DESTINATION, 0);
            m.memory.dma().request(2);
            m.clock.advance(2); // Readiness-normalized; activation is a separate gate.
            var start = m.clock.now();
            if (stage == 1 && burst == 0) {
                m.clock.schedule(() -> m.memory.write16(base + 8, count), start + 4);
            }
            for (var unit = 0; unit < expected; unit++) {
                assertThat(m.memory.dma().runPending()).as("transfer unit %s of %s", unit, expected).isTrue();
                if (unit + 1 < expected) assertThat(m.memory.dma().ownsBus()).isTrue();
            }
            assertSoftly(softly -> {
                softly.assertThat(m.memory.dma().ownsBus()).as("burst releases ownership").isFalse();
                softly.assertThat(m.memory.dma().runPending()).as("no extra transfer").isFalse();
                softly.assertThat(m.clock.now() - start).isEqualTo(2L + 2L * expected);
                softly.assertThat(m.memory.read32(DESTINATION)).isEqualTo(width == 4 ? 0x04030201 : 0x0201);
                softly.assertThat(m.memory.read16(base + 10)).isEqualTo(control);
                softly.assertThat(m.memory.read16(IRQ_FLAGS)).isEqualTo(0x100 << channel);
                softly.assertThat(m.cpu.instructions()).isZero();
            });
            if (stage == 2 && burst == 0) m.memory.write16(base + 8, count);
        }
    }

    static Stream<Arguments> suspendedBursts() {
        return Stream.of(0, 1, 2, 3).flatMap(channel ->
                Stream.of(0, 1, 2).map(units -> Arguments.of(channel, units)));
    }

    @ParameterizedTest(name = "channel={0}, completedUnits={1}")
    @MethodSource("suspendedBursts")
    void stateKeepsProgrammedCountSeparateFromTheCurrentBurst(int channel, int units) {
        var rom = EmulatorFixtures.blankRom();
        var original = new Machine(rom);
        var base = DMA + channel * 12;
        original.memory.write32(base, SOURCE);
        original.memory.write32(base + 4, DESTINATION);
        original.memory.write16(base + 8, 2);
        original.memory.write16(base + 10, 0xe740);
        original.memory.dma().request(2);
        original.clock.advance(2);
        for (var unit = 0; unit < units; unit++) original.memory.dma().runPending();
        original.memory.write16(base + 8, 1);
        var snapshot = MachineState.save(original);
        var restored = MachineState.load(rom, null, snapshot);
        assertThat(MachineState.save(restored)).isEqualTo(snapshot);
        for (var m : new Machine[]{original, restored}) {
            for (var unit = units; unit < 2; unit++) assertThat(m.memory.dma().runPending()).isTrue();
            assertThat(m.memory.dma().runPending()).isFalse();
            for (var burst = 0; burst < 2; burst++) {
                // If saved after completion, that burst's count was already
                // latched before the count write; the later reload sees one.
                var expected = units == 2 && burst == 0 ? 2 : 1;
                m.memory.dma().request(2);
                m.clock.advance(2);
                for (var unit = 0; unit < expected; unit++) assertThat(m.memory.dma().runPending()).isTrue();
                assertThat(m.memory.dma().runPending()).isFalse();
            }
        }
        assertThat(MachineState.save(restored)).isEqualTo(MachineState.save(original));
    }
}

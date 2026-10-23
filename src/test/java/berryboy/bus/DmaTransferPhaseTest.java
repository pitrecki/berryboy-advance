package berryboy.bus;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import berryboy.EmulatorFixtures;
import berryboy.Machine;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("core")
class DmaTransferPhaseTest {
    private static final int DMA = 0x040000b0;

    static Stream<Arguments> transfers() {
        return Stream.of(0x03000000, 0x02000000, 0x08000000).flatMap(source ->
                Stream.of(0x03001000, 0x02001000).flatMap(destination ->
                        Stream.of(2, 4).flatMap(width -> Stream.of(1, 2).flatMap(channels ->
                                Stream.of(1, 2, 4).map(count -> Arguments.of(source, destination, width, channels, count))))));
    }

    @ParameterizedTest(name = "source={0}, destination={1}, width={2}, channels={3}, count={4}")
    @MethodSource("transfers")
    void activatedTransfersUseOneAcquisitionCycleAndOneReleaseCycle(
            int source, int destination, int width, int channels, int count) {
        // Expected phases come from the original NBA probe, after activation.
        // This does not certify request activation or equal-deadline IRQ priority.
        var machine = createMachine();
        for (var c = 1; c <= channels; c++) {
            configure(machine, c, source + 0x100, destination + (c - 1) * 0x100,
                    count, width == 4 ? 0xe400 : 0xe000);
        }
        machine.memory.dma().request(2);
        machine.clock.advance(2); // Match independent reference's activated boundary.
        var writeAt = 3L; // Absolute cycle: service starts at 2, then acquisition 1.
        for (var c = 1; c <= channels; c++) {
            for (var word = 0; word < count; word++) {
                writeAt += readCycles(source, width, word) + writeCycles(destination, width);
                var target = destination + (c - 1) * 0x100 + word * width;
                var expected = width == 4 ? 0x04030201 + word * 0x04040404 : 0x0201 + word * 0x0202;
                machine.clock.schedule(() -> assertSoftly(softly -> {
                    softly.assertThat(read(machine, target, width)).as("DMA data at the write boundary").isZero();
                    softly.assertThat(machine.cpu.instructions()).isZero();
                }), writeAt);
                machine.clock.schedule(() -> assertSoftly(softly -> {
                    softly.assertThat(read(machine, target, width)).as("DMA data one cycle after write").isEqualTo(expected);
                    softly.assertThat(machine.cpu.instructions()).isZero();
                }), writeAt + 1);
            }
        }
        var writesDone = writeAt;
        var completionSeen = new boolean[1];
        machine.clock.schedule(() -> {
            completionSeen[0] = true;
            assertSoftly(softly -> {
                softly.assertThat(read(machine, destination + (channels - 1) * 0x100 + (count - 1) * width, width))
                        .isNotZero();
                softly.assertThat(machine.memory.read16(DMA + channels * 12 + 10) & 0x8000).isZero();
                softly.assertThat(machine.memory.read16(0x04000202) & (0x100 << channels)).isNotZero();
                softly.assertThat(machine.cpu.instructions()).isZero();
            });
        }, writesDone + 1);
        for (var word = 0; word < channels * count; word++) machine.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(machine.clock.now()).isEqualTo(writesDone + 1);
            softly.assertThat(completionSeen[0]).isTrue();
            softly.assertThat(machine.cpu.instructions()).isZero();
            softly.assertThat(machine.memory.dma().runPending()).isFalse();
        });
    }

    static Stream<Arguments> mutableSources() {
        return Stream.of(0x03000000, 0x02000000).flatMap(source ->
                Stream.of(0x03001000, 0x02001000).flatMap(destination ->
                        Stream.of(2, 4).map(width -> Arguments.of(source, destination, width))));
    }

    @ParameterizedTest
    @MethodSource("mutableSources")
    void sourceChangesAfterTheReadBoundaryDoNotReplaceTheLatchedValue(int source, int destination, int width) {
        var machine = createMachine();
        configure(machine, 1, source + 0x100, destination, 1, width == 4 ? 0xa400 : 0xa000);
        machine.memory.dma().request(2);
        machine.clock.advance(2);
        // Read completes at service + acquisition(1) + source access. Mutate at
        // the following cycle, while the destination access is in flight.
        machine.clock.schedule(() -> machine.memory.write32(source + 0x100, 0x76543210),
                2L + 2 + readCycles(source, width, 0));
        machine.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(read(machine, destination, width)).isEqualTo(width == 4 ? 0x04030201 : 0x0201);
            softly.assertThat(machine.memory.read32(source + 0x100)).isEqualTo(0x76543210);
            softly.assertThat(machine.cpu.instructions()).isZero();
        });
    }

    @Test
    void requestArrivingDuringReleaseStartsANewOwnershipInterval() {
        var machine = createMachine();
        configure(machine, 1, 0x03000100, 0x03001000, 1, 0xa000);
        machine.memory.dma().request(2);
        // IWRAM halfword write at 3, release at 4. A newly enabled immediate
        // channel during release belongs to a fresh ownership interval.
        machine.clock.schedule(() -> configure(machine, 0, 0x03000102, 0x03001002, 1, 0x8000), 4);
        machine.cpu.step();
        var afterFirst = machine.clock.now();
        machine.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(afterFirst).isEqualTo(4);
            softly.assertThat(machine.clock.now()).isEqualTo(8);
            softly.assertThat(machine.memory.read16(0x03001000)).isEqualTo(0x0201);
            softly.assertThat(machine.memory.read16(0x03001002)).isEqualTo(0x0403);
            softly.assertThat(machine.cpu.instructions()).isZero();
        });
    }

    @Test
    void acquisitionRechecksPriorityAfterADeviceCallback() {
        var machine = createMachine();
        configure(machine, 3, 0x03000100, 0x03001000, 1, 0x8000);
        machine.clock.schedule(() -> configure(machine, 0, 0x03000102, 0x03001002, 1, 0x8000), 1);
        machine.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(machine.memory.read16(0x03001000)).isZero();
            softly.assertThat(machine.memory.read16(0x03001002)).isEqualTo(0x0403);
            softly.assertThat(machine.clock.now()).isEqualTo(3);
        });
        machine.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(machine.memory.read16(0x03001000)).isEqualTo(0x0201);
            softly.assertThat(machine.clock.now()).isEqualTo(6);
            softly.assertThat(machine.cpu.instructions()).isZero();
        });
    }

    private static Machine createMachine() {
        var image = EmulatorFixtures.blankRom();
        for (var i = 0; i < 16; i++) image[0x100 + i] = (byte) (i + 1);
        var machine = new Machine(image);
        for (var i = 0; i < 16; i++) {
            machine.memory.write8(0x03000100 + i, i + 1);
            machine.memory.write8(0x02000100 + i, i + 1);
        }
        return machine;
    }

    private static int read(Machine machine, int address, int width) {
        return width == 4 ? machine.memory.read32(address) : machine.memory.read16(address);
    }

    private static int readCycles(int source, int width, int word) {
        if (source == 0x08000000) return width == 4 ? (word == 0 ? 8 : 6) : (word == 0 ? 5 : 3);
        return source == 0x02000000 ? (width == 4 ? 6 : 3) : 1;
    }

    private static int writeCycles(int destination, int width) {
        return destination == 0x02001000 ? (width == 4 ? 6 : 3) : 1;
    }

    private static void configure(Machine machine, int channel, int source, int destination, int count, int control) {
        var base = DMA + channel * 12;
        machine.memory.write32(base, source);
        machine.memory.write32(base + 4, destination);
        machine.memory.write16(base + 8, count);
        machine.memory.write16(base + 10, control);
    }
}

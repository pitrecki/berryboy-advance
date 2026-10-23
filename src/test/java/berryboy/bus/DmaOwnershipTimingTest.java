package berryboy.bus;

import berryboy.EmulatorFixtures;
import berryboy.Machine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

@Tag("core")
class DmaOwnershipTimingTest {
    private static final int DMA_BASE = 0x040000b0;
    private static final int FIFO_A = 0x040000a0;

    @ParameterizedTest
    @CsvSource({
        "0x03000000,1,10", "0x03000000,2,18",
        "0x02000000,1,30", "0x02000000,2,58",
        "0x08000000,1,32", "0x08000000,2,62"
    })
    void fifoChannelsShareOwnershipOverheadAndEachNewGroupPaysItAgain(int source, int channels, int cycles) {
        // Independently measured by tools/nba-dma-probe.cc. These totals do not
        // assert the separately unqualified activation or individual write phase.
        var machine = new Machine(EmulatorFixtures.blankRom());
        for (var channel = 1; channel <= channels; channel++) {
            configure(machine, channel, source + 0x100, FIFO_A + (channel - 1) * 4, 0, 0xb640);
        }
        for (var group = 0; group < 2; group++) {
            for (var channel = 1; channel <= channels; channel++) {
                machine.memory.dma().requestFifo(FIFO_A + (channel - 1) * 4);
            }
            var before = machine.clock.now();
            for (var word = 0; word < channels * 4; word++) machine.cpu.step();
            assertSoftly(softly -> {
                softly.assertThat(machine.clock.now() - before).isEqualTo(cycles);
                softly.assertThat(machine.cpu.instructions()).isZero();
            });
            // No CPU instruction or diagnostic runPending call releases ownership.
            machine.clock.advance(11);
        }
    }

    @Test
    void higherPriorityChannelPreemptsBetweenWordsWithoutAnotherOwnershipCharge() {
        var machine = EmulatorFixtures.arm(0xe1a00000);
        machine.memory.write16(0x03000100, 0x1111);
        machine.memory.write16(0x03000102, 0x2222);
        configure(machine, 3, 0x03000100, 0x03000200, 2, 0x8000);
        machine.cpu.step();
        configure(machine, 0, 0x03000200, 0x03000210, 1, 0x8000);
        machine.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(machine.memory.read16(0x03000210)).isEqualTo(0x1111);
            softly.assertThat(machine.memory.read16(0x03000202)).isZero();
        });
        machine.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(machine.clock.now()).isEqualTo(8); // Ownership 2 + three read/write pairs.
            softly.assertThat(machine.memory.read16(0x03000202)).isEqualTo(0x2222);
            softly.assertThat(machine.cpu.instructions()).isZero();
        });
    }

    @Test
    void cpuResumptionSeparatesOwnershipIntervals() {
        var machine = new Machine(EmulatorFixtures.blankRom());
        machine.memory.write32(0x03000000, 0xe1a00000);
        machine.cpu.setPc(0x03000000);
        machine.memory.write16(0x03000100, 0x1234);
        configure(machine, 0, 0x03000100, 0x03000200, 1, 0x8000);
        machine.cpu.step();
        machine.cpu.step(); // One IWRAM NOP between DMA requests.
        machine.memory.write16(0x03000100, 0x5678);
        configure(machine, 0, 0x03000100, 0x03000200, 1, 0x8000);
        machine.cpu.step();
        assertSoftly(softly -> {
            softly.assertThat(machine.clock.now()).isEqualTo(9); // Two ownership intervals of 4, plus NOP.
            softly.assertThat(machine.cpu.instructions()).isEqualTo(1);
            softly.assertThat(machine.memory.read16(0x03000200)).isEqualTo(0x5678);
        });
    }

    private static void configure(Machine machine, int channel, int source, int destination, int count, int control) {
        var base = DMA_BASE + channel * 12;
        machine.memory.write32(base, source);
        machine.memory.write32(base + 4, destination);
        machine.memory.write16(base + 8, count);
        machine.memory.write16(base + 10, control);
    }
}

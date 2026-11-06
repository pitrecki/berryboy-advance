package berryboy.boot;

import berryboy.EmulationException;
import berryboy.EmulatorFixtures;
import berryboy.Machine;
import berryboy.MachineState;
import berryboy.video.LcdTiming;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("core")
class BiosStartupTest {
    @Test
    void runsOriginalInitializationAndPreservesCompleteStateAtEveryBudget() {
        var rom = EmulatorFixtures.blankRom(); EmulatorFixtures.writeWord(rom, 0, 0xeafffffe);
        var bios = new byte[16384];
        EmulatorFixtures.writeWord(bios, 0, 0xe59f0018); EmulatorFixtures.writeWord(bios, 0x20, 0x02000000);
        EmulatorFixtures.writeWord(bios, 4, 0xe3a0102a); EmulatorFixtures.writeWord(bios, 8, 0xe5801000);
        EmulatorFixtures.writeWord(bios, 12, 0xe59ff010); EmulatorFixtures.writeWord(bios, 0x24, 0x08000000);
        var hidden = new Machine(rom, bios); var visible = new Machine(rom, bios);
        var startup = new BiosStartup(hidden); var budgets = 0;
        while (!startup.complete()) {
            var target = (hidden.clock.now() / LcdTiming.FRAME_CYCLES + 1) * LcdTiming.FRAME_CYCLES;
            startup.runCycles(target - hidden.clock.now());
            visible.runCycles(target - visible.clock.now());
            assertThat(MachineState.save(hidden)).isEqualTo(MachineState.save(visible));
            assertThat(++budgets).isLessThan(5);
        }
        assertThat(hidden.memory.read32(0x02000000)).isEqualTo(42);
        assertThat(hidden.memory.raster().completedFrames()).isGreaterThanOrEqualTo(2);
        var before = hidden.clock.now(); startup.runCycles(100);
        assertThat(hidden.clock.now()).isEqualTo(before);
    }
    @Test
    void completionInspectionDoesNotExecuteGuestWork() {
        var m = new Machine(EmulatorFixtures.blankRom(), new byte[16384]); var startup = new BiosStartup(m);
        var before = MachineState.save(m);
        assertThat(startup.complete()).isFalse(); startup.runCycles(0);
        assertThat(MachineState.save(m)).isEqualTo(before);
    }
    @Test
    void directEntryCartridgesNeedNoHiddenStartup() {
        var m = new Machine(EmulatorFixtures.blankRom()); var startup = new BiosStartup(m);
        assertThat(startup.complete()).isTrue(); startup.runCycles(1000);
        assertThat(m.clock.now()).isZero();
    }
    @Test
    void reportsABiosThatNeverTransfersControlInsteadOfHidingItForever() {
        var bios = new byte[16384]; EmulatorFixtures.writeWord(bios, 0, 0xeafffffe);
        var m = new Machine(EmulatorFixtures.blankRom(), bios); var startup = new BiosStartup(m, 16);
        assertThatThrownBy(() -> startup.runCycles(32)).isInstanceOf(EmulationException.class).hasMessageContaining("did not reach the cartridge");
    }
    @Test
    void rejectsNegativeAndOverflowingBudgets() {
        var m = new Machine(EmulatorFixtures.blankRom(), new byte[16384]); var startup = new BiosStartup(m);
        assertThatThrownBy(() -> startup.runCycles(-1)).isInstanceOf(IllegalArgumentException.class);
        m.clock.advance(1);
        assertThatThrownBy(() -> startup.runCycles(Long.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
    }
}

package berryboy.bus;

import berryboy.EmulatorFixtures;
import berryboy.timing.CycleScheduler;

import static berryboy.EmulatorFixtures.arm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class BusTimingTest {
    @Test
    void modelsBusWidthsAndDefaultGamePakWaits() {
        var timing = new BusTiming(new CycleScheduler());
        assertSoftly(s -> {
            s.assertThat(timing.accessCycles(0x02000000, 4, false)).isEqualTo(6);
            s.assertThat(timing.accessCycles(0x03000000, 4, false)).isEqualTo(1);
            s.assertThat(timing.accessCycles(0x06000000, 4, false)).isEqualTo(2);
            s.assertThat(timing.accessCycles(0x08000004, 4, false)).isEqualTo(8);
            s.assertThat(timing.accessCycles(0x08000004, 4, true)).isEqualTo(6);
            s.assertThat(timing.accessCycles(0x08020000, 2, true)).isEqualTo(5);
            s.assertThat(timing.accessCycles(0x0a000004, 2, true)).isEqualTo(5);
            s.assertThat(timing.accessCycles(0x0c000004, 2, true)).isEqualTo(9);
        });
    }

    @Test
    void waitControlChangesActualCpuFetchesAndEnablesPrefetch() {
        var machine = arm(0xe1a00000, 0xe1a00000);
        machine.memory.write16(0x04000204, 0x18); // WS0 N=2 waits, S=1 wait.
        machine.runInstructions(2);
        assertThat(machine.memory.clock().now()).isEqualTo(5 + 4);
        machine.memory.write16(0x04000204, 0x4000);
        assertThat(machine.memory.read16(0x04000204)).isEqualTo(0x4000);
    }

    @Test
    void distinguishesFailedConditionsRegisterShiftsAndBranchRefills() {
        var machine = arm(0x01a00110, 0xe1a00110, 0xeafffffe); // failed MOVEQ, register LSL, B self.
        machine.cpu.setRegister(1, 1);
        machine.cpu.step();
        assertThat(machine.memory.clock().now()).isEqualTo(8);
        machine.cpu.step();
        assertThat(machine.memory.clock().now()).isEqualTo(15); // 6S + 1I.
        machine.cpu.step();
        assertThat(machine.memory.clock().now()).isEqualTo(37); // Prefetch-disable bug: 8N + 8N + 6S.
        assertThat(machine.cpu.pc()).isEqualTo(Memory.ROM_BASE + 8);
    }

    @Test
    void prefetchFillsDuringOffCartridgeDataAndInternalCycles() {
        var machine = arm(0xe5901000, 0xe1a00000);
        machine.cpu.setRegister(0, Memory.EWRAM_BASE);
        machine.memory.write32(Memory.EWRAM_BASE, 42);
        machine.memory.write16(0x04000204, 0x4000);
        machine.cpu.step();
        assertThat(machine.clock.now()).isEqualTo(15);
        machine.cpu.step();
        assertThat(machine.clock.now()).isEqualTo(16); // Both halves were buffered; one core cycle.
        assertThat(machine.cpu.register(1)).isEqualTo(42);
    }

    @Test
    void prefetchNeverExceedsEightHalfwordsAndBranchFlushesIt() {
        var clock = new CycleScheduler();
        var bus = new BusTiming(clock);
        bus.setWaitControl(0x4000);
        bus.fetch(0x08000000, 2);
        bus.internal(100, false);
        long filled = clock.now();
        for (int i = 1; i <= 8; i++) bus.fetch(0x08000000 + i * 2, 2);
        assertThat(clock.now() - filled).isEqualTo(8);
        bus.invalidateFetch();
        long before = clock.now();
        bus.fetch(0x08000100, 2);
        assertThat(clock.now() - before).isEqualTo(5);
    }

    @Test
    void loadAdvancesDeviceEventsAndDoesNotChargeDiagnosticReads() {
        var machine = arm(0xe5901000, 0xe1a00000); // LDR r1,[r0]; NOP.
        machine.cpu.setRegister(0, Memory.EWRAM_BASE);
        machine.memory.write32(Memory.EWRAM_BASE, 42);
        long[] eventTime = {-1};
        machine.memory.clock().schedule(() -> eventTime[0] = machine.memory.clock().now(), 10);
        machine.cpu.step();
        assertSoftly(s -> {
            s.assertThat(machine.cpu.register(1)).isEqualTo(42);
            s.assertThat(machine.memory.clock().now()).isEqualTo(15); // 8 fetch + 6 data + 1I.
            s.assertThat(eventTime[0]).isEqualTo(10);
        });
        machine.memory.read32(Memory.EWRAM_BASE);
        assertThat(machine.memory.clock().now()).isEqualTo(15);
        machine.cpu.step();
        assertThat(machine.memory.clock().now()).isEqualTo(23); // Data broke sequential fetch.
    }
}

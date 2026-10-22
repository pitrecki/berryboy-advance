package berryboy;

import static berryboy.EmulatorFixtures.arm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class HardwareTimingTest {
    private static final int DISPSTAT = 0x04000004;
    private static final int VCOUNT = 0x04000006;
    private static final int TIMER = 0x04000100;
    private static final int IE = 0x04000200;
    private static final int IF = IE + 2;
    private static final int IME = IE + 8;

    @Test
    void lcdTransitionsAtExactBoundariesAndRaisesConfiguredInterrupts() {
        var m = arm(0xe1a00000);
        m.memory.write16(DISPSTAT, (1 << 8) | 0x38);
        m.memory.clock().advanceTo(959);
        assertThat(m.memory.read16(DISPSTAT) & 7).isZero();
        m.memory.clock().advance(1);
        assertThat(m.memory.read16(DISPSTAT) & 7).isEqualTo(2);
        assertThat(m.memory.read16(IF)).isEqualTo(2);
        m.memory.write16(IF, 2);
        m.memory.clock().advanceTo(1232);
        assertSoftly(s -> {
            s.assertThat(m.memory.read16(VCOUNT)).isEqualTo(1);
            s.assertThat(m.memory.read16(DISPSTAT) & 7).isEqualTo(4);
            s.assertThat(m.memory.read16(IF)).isEqualTo(4);
        });
        m.memory.clock().advanceTo(160L * 1232);
        assertThat(m.memory.read16(DISPSTAT) & 1).isEqualTo(1);
        assertThat(m.memory.read16(IF) & 1).isEqualTo(1);
        m.memory.clock().advanceTo(227L * 1232);
        assertThat(m.memory.read16(DISPSTAT) & 1).isZero();
        m.memory.clock().advanceTo(228L * 1232);
        assertThat(m.memory.read16(VCOUNT)).isZero();
        assertThat(m.memory.lcd().frames()).isEqualTo(1);
    }

    @Test
    void lcdHasNoDriftAndCountIsReadOnly() {
        var m = arm(0xe1a00000);
        m.memory.write16(VCOUNT, 100);
        m.memory.clock().advanceTo(100L * LcdTiming.FRAME_CYCLES + 3 * 1232 + 960);
        assertThat(m.memory.lcd().frames()).isEqualTo(100);
        assertThat(m.memory.read16(VCOUNT)).isEqualTo(3);
        assertThat(m.memory.read16(DISPSTAT) & 2).isEqualTo(2);
    }

    @Test
    void timersPrescaleReloadAndCascadeAtTheOverflowCycle() {
        var m = arm(0xe1a00000);
        m.memory.write16(TIMER, 0xfffe);
        m.memory.write16(TIMER + 4, 0xffff);
        m.memory.write16(TIMER + 6, 0xc4); // Timer 1, cascade + IRQ.
        m.memory.write16(TIMER + 2, 0xc1); // Timer 0, /64 + IRQ.
        m.memory.clock().advanceTo(127);
        assertThat(m.memory.read16(TIMER)).isEqualTo(0xffff);
        assertThat(m.memory.read16(IF)).isZero();
        m.memory.clock().advance(1);
        assertSoftly(s -> {
            s.assertThat(m.memory.read16(TIMER)).isEqualTo(0xfffe);
            s.assertThat(m.memory.read16(TIMER + 4)).isEqualTo(0xffff);
            s.assertThat(m.memory.read16(IF)).isEqualTo(0x18);
        });
        m.memory.write16(IF, 0x18);
        m.memory.clock().advanceTo(256);
        assertThat(m.memory.read16(IF)).isEqualTo(0x18);
    }

    @Test
    void reloadWritesAffectNextOverflowAndDisablingFreezesCounter() {
        var m = arm(0xe1a00000);
        m.memory.write16(TIMER, 0xfffc);
        m.memory.write16(TIMER + 2, 0x80);
        m.memory.clock().advance(2);
        m.memory.write16(TIMER, 0xfff0);
        assertThat(m.memory.read16(TIMER)).isEqualTo(0xfffe);
        m.memory.clock().advance(2);
        assertThat(m.memory.read16(TIMER)).isEqualTo(0xfff0);
        m.memory.write16(TIMER + 2, 0);
        m.memory.clock().advance(1000);
        assertThat(m.memory.read16(TIMER)).isEqualTo(0xfff0);
        m.memory.write16(TIMER + 2, 0x80);
        assertThat(m.memory.read16(TIMER)).isEqualTo(0xfff0);
    }

    @Test
    void interruptPendingRespectsIeAndImeAndIfIsWriteOneToClear() {
        var m = arm(0xe1a00000);
        m.memory.interrupts().request(0x108);
        m.memory.write16(IF, 8);
        assertThat(m.memory.read16(IF)).isEqualTo(0x100);
        assertThat(m.memory.interrupts().wakePending()).isFalse();
        m.memory.write16(IE, 0x100);
        assertThat(m.memory.interrupts().wakePending()).isTrue();
        assertThat(m.memory.interrupts().irqPending()).isFalse();
        m.memory.write16(IME, 1);
        assertThat(m.memory.interrupts().irqPending()).isTrue();
        m.memory.write16(IF, 0x100);
        assertThat(m.memory.interrupts().irqPending()).isFalse();
    }

    @Test
    void guestExecutionDrivesTimersWithoutManualClockAdvancement() {
        var m = arm(0xe1a00000, 0xe1a00000);
        m.memory.write16(TIMER, 0xfff8);
        m.memory.write16(TIMER + 2, 0xc0);
        m.cpu.step();
        assertThat(m.memory.read16(IF) & 8).isEqualTo(8);
        assertThat(m.memory.read16(TIMER)).isEqualTo(0xfff8);
        m.cpu.step();
        assertThat(m.memory.read16(TIMER)).isEqualTo(0xfffe);
    }
}

package berryboy;

import static berryboy.EmulatorFixtures.arm;
import static berryboy.EmulatorFixtures.blankRom;
import static berryboy.EmulatorFixtures.thumb;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class InterruptExecutionTest {
    @Test
    void irqBanksStatusAndLinkThenReturnsToThumb() {
        var machine = thumb(0x2007);
        var bios = new byte[0x4000];
        word(bios, 0x18, 0xe25ef004); // SUBS pc,lr,#4.
        machine.memory.setBios(bios);
        machine.memory.write16(0x04000200, 1);
        machine.memory.write16(0x04000208, 1);
        machine.memory.interrupts().request(1);
        int original = machine.cpu.cpsr();
        machine.cpu.step();
        assertSoftly(s -> {
            s.assertThat(machine.cpu.pc()).isEqualTo(0x18);
            s.assertThat(machine.cpu.cpsr() & 0xff).isEqualTo(0x92);
            s.assertThat(machine.cpu.register(14)).isEqualTo(Memory.ROM_BASE + 4);
            s.assertThat(machine.cpu.instructions()).isZero();
        });
        machine.memory.write16(0x04000202, 1);
        machine.cpu.step();
        assertThat(machine.cpu.cpsr()).isEqualTo(original);
        assertThat(machine.cpu.pc()).isEqualTo(Memory.ROM_BASE);
        machine.cpu.step();
        assertThat(machine.cpu.register(0)).isEqualTo(7);
    }

    @Test
    void haltWakesOnEnabledRequestEvenWithImeAndCpuIrqMasked() {
        var m = arm(0xe3a00007);
        m.cpu.setCpsr(Cpu.SYSTEM | 0x80);
        m.memory.write16(0x04000200, 8);
        m.memory.write16(0x04000100, 0xfffc);
        m.memory.write16(0x04000102, 0xc0);
        m.memory.write8(0x04000301, 0);
        m.cpu.step();
        assertThat(m.cpu.instructions()).isZero();
        assertThat(m.clock.now()).isEqualTo(4);
        m.cpu.step();
        assertThat(m.memory.halted()).isFalse();
        assertThat(m.cpu.register(0)).isEqualTo(7);
    }

    @Test
    void bootsSuppliedBiosAtResetAndDiagnosesMissingIrqBios() {
        var bios = new byte[0x4000];
        word(bios, 0, 0xe3a00009);
        var boot = new Machine(blankRom(), bios);
        assertThat(boot.cpu.cpsr()).isEqualTo(0xd3);
        assertThat(boot.cpu.pc()).isZero();
        boot.cpu.step();
        assertThat(boot.cpu.register(0)).isEqualTo(9);
        var m = arm(0xe1a00000);
        m.memory.write16(0x04000200, 1);
        m.memory.write16(0x04000208, 1);
        m.memory.interrupts().request(1);
        assertThatThrownBy(m.cpu::step).isInstanceOf(EmulationException.class).hasMessageContaining("BIOS");
    }

    @Test
    void keyControlSupportsAndChordsAndReleases() {
        var m = arm(0xe1a00000);
        m.memory.write16(0x04000132, 0xc003);
        m.memory.setPressedKeys(1);
        assertThat(m.memory.read16(0x04000202)).isZero();
        m.memory.setPressedKeys(3);
        assertThat(m.memory.read16(0x04000202)).isEqualTo(0x1000);
        m.memory.write16(0x04000202, 0x1000);
        m.memory.setPressedKeys(3);
        assertThat(m.memory.read16(0x04000202)).isZero();
        m.memory.setPressedKeys(0);
        m.memory.setPressedKeys(3);
        assertThat(m.memory.read16(0x04000202)).isEqualTo(0x1000);
    }

    private void word(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
    }
}

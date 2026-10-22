package berryboy.serial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import berryboy.EmulationException;
import berryboy.timing.CycleScheduler;
import berryboy.timing.InterruptController;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class SerialUnitTest {
    @Test
    void normalInternalClockShiftsDisconnectedHighBitsAndRaisesIrqAtDeadline() {
        var clock = new CycleScheduler(); var irq = new InterruptController(); var serial = new SerialUnit(clock, irq);
        serial.write8(0x0400012a, 0);
        serial.write8(0x04000128, 0x81);
        clock.advance(200);
        serial.write8(0x04000129, 0x40); // IRQ enable must not restart the shift clock.
        clock.advanceTo(511);
        assertThat(serial.read8(0x04000128) & 0x80).isEqualTo(0x80);
        clock.advance(1);
        assertThat(serial.read8(0x04000128) & 0x80).isZero();
        assertThat(serial.read8(0x0400012a)).isEqualTo(255);
        irq.write8(0x04000200, 0x80);
        assertThat(irq.wakePending()).isTrue();
    }

    @Test
    void fastThirtyTwoBitTransfersFinishButExternalClockWaitsForACable() {
        var clock = new CycleScheduler(); var serial = new SerialUnit(clock, new InterruptController());
        serial.write8(0x04000129, 0x10);
        serial.write8(0x04000128, 0x83);
        clock.advanceTo(256);
        for (int i = 0; i < 4; i++) assertThat(serial.read8(0x04000120 + i)).isEqualTo(255);
        serial.write8(0x04000128, 0x80);
        clock.advance(10000);
        assertThat(serial.read8(0x04000128) & 0x80).isEqualTo(0x80);
    }

    @Test
    void resetPortsAcceptWritesButUnsupportedLinkTransfersFailExplicitly() {
        var serial = new SerialUnit(new CycleScheduler(), new InterruptController());
        serial.write8(0x04000134, 0); serial.write8(0x04000135, 0x80);
        assertThat(serial.read8(0x04000134) & 15).isEqualTo(15);
        serial.write8(0x04000154, 42);
        assertThat(serial.read8(0x04000158) & 2).isEqualTo(2);
        serial.write8(0x04000129, 0x30);
        assertThatThrownBy(() -> serial.write8(0x04000128, 0x80)).isInstanceOf(EmulationException.class)
                .hasMessageContaining("link backend");
    }
}

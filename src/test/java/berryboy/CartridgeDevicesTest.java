package berryboy;

import static berryboy.EmulatorFixtures.arm;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class CartridgeDevicesTest {
    @Test
    void rtcSerialCalendarCrossesLeapDayUsingGuestTimeAndAcceptsControlWrites() {
        var clock = new CycleScheduler();
        var gpio = gpio(clock, CartridgeGpio.RTC);
        writeRtc(gpio, 0x62, new int[]{0x40});
        gpio.rtc().setDateTime(24, 2, 28, 3, 23, 59, 59);
        clock.advance(2L * CycleScheduler.CLOCK_HZ);
        assertThat(readRtc(gpio, 0x65, 7)).containsExactly(0x24, 0x02, 0x29, 4, 0, 0, 1);
        writeRtc(gpio, 0x64, new int[]{0x25, 0x12, 0x31, 3, 0x23, 0x59, 0x59});
        clock.advance(CycleScheduler.CLOCK_HZ);
        assertThat(readRtc(gpio, 0x65, 7)).containsExactly(0x26, 1, 1, 4, 0, 0, 0);
    }

    @Test
    void rtcPowerLossFlagClearsOnReadAndTwelveHourPmUsesBitSeven() {
        var gpio = gpio(new CycleScheduler(), CartridgeGpio.RTC);
        assertThat(readRtc(gpio, 0x63, 1)).containsExactly(0x82);
        assertThat(readRtc(gpio, 0x63, 1)).containsExactly(2);
        gpio.rtc().setDateTime(24, 1, 1, 1, 13, 2, 3);
        assertThat(readRtc(gpio, 0x67, 3)).containsExactly(0x81, 2, 3);
    }

    @Test
    void solarCounterThresholdAndResetAreDeterministic() {
        var gpio = gpio(new CycleScheduler(), CartridgeGpio.SOLAR);
        gpio.setSensors(0, 0, 0, 3);
        gpio.write16(0xc6, 7);
        gpio.write16(0xc4, 2); gpio.write16(0xc4, 0);
        for (int i = 0; i < 2; i++) { gpio.write16(0xc4, 1); gpio.write16(0xc4, 0); }
        assertThat(gpio.read8(0xc4) & 8).isZero();
        gpio.write16(0xc4, 1); gpio.write16(0xc4, 0);
        assertThat(gpio.read8(0xc4) & 8).isEqualTo(8);
        gpio.write16(0xc4, 2);
        assertThat(gpio.read8(0xc4) & 8).isZero();
    }

    @Test
    void gyroSerialFrameContainsFourDummyBitsAndLatchedTwelveBitInput() {
        var gpio = gpio(new CycleScheduler(), CartridgeGpio.GYRO | CartridgeGpio.RUMBLE);
        gpio.setSensors(0, 0, 0x6c0, 0);
        gpio.write16(0xc6, 0xb);
        gpio.write16(0xc4, 0xb); gpio.write16(0xc4, 0xa);
        gpio.setSensors(0, 0, 0xabc, 0); // Current conversion keeps its previous value.
        int word = 0;
        for (int i = 0; i < 16; i++) {
            word = (word << 1) | ((gpio.read8(0xc4) >>> 2) & 1);
            gpio.write16(0xc4, 8); gpio.write16(0xc4, 10);
        }
        assertThat(word).isEqualTo(0x6c0);
        assertThat(gpio.rumbling()).isTrue();
        gpio.write16(0xc6, 3);
        assertThat(gpio.rumbling()).isFalse();
    }

    @Test
    void tiltSamplingUsesUnlockAndGpioReadsRespectControlAndRomMapping() {
        var m = arm(0xe1a00000);
        m.memory.gpio().configure(CartridgeGpio.TILT | CartridgeGpio.SOLAR);
        m.memory.gpio().setSensors(0x123, 0xabc, 0, 0);
        m.memory.write8(0x0e008100, 0xaa); // Missing unlock leaves default conversion.
        assertThat(m.memory.read8(0x0e008200)).isEqualTo(0x92);
        m.memory.write8(0x0e008000, 0x55); m.memory.write8(0x0e008100, 0xaa);
        assertThat(m.memory.read8(0x0e008200)).isEqualTo(0x23);
        assertThat(m.memory.read8(0x0e008300)).isEqualTo(0x81);
        assertThat(m.memory.read8(0x0e008400)).isEqualTo(0xbc);
        assertThat(m.memory.read8(0x0e008500)).isEqualTo(0xa);
        m.memory.write16(0x080000c8, 1);
        m.memory.write16(0x080000c6, 7);
        assertThat(m.memory.read16(0x080000c6)).isEqualTo(7);
        m.memory.write16(0x080000c8, 0);
        assertThat(m.memory.read16(0x080000c6)).isZero();
    }

    private CartridgeGpio gpio(CycleScheduler clock, int mask) {
        var gpio = new CartridgeGpio(clock); gpio.configure(mask); gpio.write16(0xc8, 1); return gpio;
    }
    private void start(CartridgeGpio gpio) { gpio.write16(0xc6, 7); gpio.write16(0xc4, 1); gpio.write16(0xc4, 5); }
    private void send(CartridgeGpio gpio, int value) {
        for (int i = 0; i < 8; i++) {
            int pins = 4 | (((value >>> i) & 1) << 1);
            gpio.write16(0xc4, pins); gpio.write16(0xc4, pins | 1);
        }
    }
    private void writeRtc(CartridgeGpio gpio, int command, int[] data) {
        start(gpio); send(gpio, command); for (int value : data) send(gpio, value); gpio.write16(0xc4, 1);
    }
    private int[] readRtc(CartridgeGpio gpio, int command, int count) {
        start(gpio); send(gpio, command); gpio.write16(0xc6, 5);
        int[] result = new int[count];
        for (int i = 0; i < count * 8; i++) {
            gpio.write16(0xc4, 4); gpio.write16(0xc4, 5);
            result[i / 8] |= ((gpio.read8(0xc4) >>> 1) & 1) << (i & 7);
        }
        gpio.write16(0xc4, 1); return result;
    }
}

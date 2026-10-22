package berryboy;

/** Configurable RTC/solar/gyro/rumble GPIO and Yoshi-style tilt ADC registers. */
public final class CartridgeGpio {
    public static final int RTC = 1;
    public static final int SOLAR = 2;
    public static final int GYRO = 4;
    public static final int TILT = 8;
    public static final int RUMBLE = 16;
    private final CartridgeRtc rtc;
    private int devices;
    private int pins;
    private int direction;
    private int readable;
    private int solar = 0xe8;
    private int solarCount;
    private int gyro = 0x6c0;
    private int latchedGyro;
    private int gyroBit = -1;
    private int tiltX = 0x392;
    private int tiltY = 0x3a0;
    private int latchedX = tiltX;
    private int latchedY = tiltY;
    private boolean tiltUnlock;

    public CartridgeGpio(CycleScheduler clock) { rtc = new CartridgeRtc(clock); }
    public void configure(int mask) {
        if ((mask & ~31) != 0 || ((mask & GYRO) != 0 && (mask & (RTC | SOLAR)) != 0)) {
            throw new IllegalArgumentException("Conflicting cartridge GPIO devices");
        }
        devices = mask; pins = 0; direction = 0; readable = 0;
    }
    public CartridgeRtc rtc() { return rtc; }
    public boolean rumbling() { return (devices & RUMBLE) != 0 && (pins & direction & 8) != 0; }

    public void setSensors(int x, int y, int z, int sunlightThreshold) {
        if (x < 0 || x > 4095 || y < 0 || y > 4095 || z < 0 || z > 4095
                || sunlightThreshold < 0 || sunlightThreshold > 255) throw new IllegalArgumentException("Sensor range");
        tiltX = x; tiltY = y; gyro = z; solar = sunlightThreshold;
    }

    boolean handles(int address) {
        var offset = address & 0x1ffffff;
        return devices != 0 && offset >= 0xc4 && offset <= 0xc9;
    }

    boolean readable() { return readable != 0; }

    int read8(int address) {
        if ((address & 1) != 0) return 0;
        var offset = address & 0x1ffffff;
        if (offset == 0xc6) return direction;
        if (offset == 0xc8) return readable;
        int input = 0;
        if ((devices & RTC) != 0) input |= rtc.output() << 1;
        if ((devices & SOLAR) != 0 && (solarCount & 255) >= solar) input |= 8;
        if ((devices & GYRO) != 0 && gyroBit >= 0 && gyroBit < 16) input |= ((latchedGyro >>> (15 - gyroBit)) & 1) << 2;
        return (pins & direction) | (input & ~direction);
    }

    void write16(int address, int value) {
        var offset = address & 0x1ffffff;
        if (offset == 0xc8) { readable = value & 1; return; }
        if (offset == 0xc6) { direction = value & 15; return; }
        var previous = pins;
        pins = value & direction & 15;
        if ((devices & RTC) != 0) rtc.pins(previous, pins, direction);
        if ((devices & SOLAR) != 0) {
            if ((pins & 2) != 0) solarCount = 0;
            else if ((previous & 1) == 0 && (pins & 1) != 0) solarCount = (solarCount + 1) & 0xfff;
        }
        if ((devices & GYRO) != 0) {
            if ((previous & 1) == 0 && (pins & 1) != 0) { latchedGyro = gyro; gyroBit = 0; }
            else if ((previous & 2) != 0 && (pins & 2) == 0 && gyroBit < 16) gyroBit++;
        }
    }

    boolean tiltAddress(int address) {
        var offset = address & 0xffff;
        return (devices & TILT) != 0 && offset >= 0x8000 && offset <= 0x8500;
    }

    int readTilt(int address) {
        return switch (address & 0xffff) {
            case 0x8200 -> latchedX & 255;
            case 0x8300 -> (latchedX >>> 8) | 0x80;
            case 0x8400 -> latchedY & 255;
            case 0x8500 -> latchedY >>> 8;
            default -> 255;
        };
    }

    void writeTilt(int address, int value) {
        var offset = address & 0xffff;
        if (offset == 0x8000) tiltUnlock = (value & 255) == 0x55;
        else if (offset == 0x8100) {
            if (tiltUnlock && (value & 255) == 0xaa) { latchedX = tiltX; latchedY = tiltY; }
            tiltUnlock = false;
        }
    }
}

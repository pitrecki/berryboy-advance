package berryboy.cartridge;

import berryboy.timing.CycleScheduler;

/** Deterministic S-3511-style serial RTC. Calendar advances from guest cycles. */
public final class CartridgeRtc {
    private final CycleScheduler clock;
    private final int[] calendar = {0, 1, 1, 6, 0, 0, 0};
    private final int[] parameters = new int[7];
    private long lastCycle;
    private int control = 0x82;
    private int command;
    private int bits;
    private int value;
    private int parameter;
    private int readBit;
    private int output;
    private boolean selected;
    private boolean reading;

    public CartridgeRtc(CycleScheduler clock) { this.clock = clock; lastCycle = clock.now(); }

    public void setDateTime(int year, int month, int day, int weekday, int hour, int minute, int second) {
        if (year < 0 || year > 99 || month < 1 || month > 12 || day < 1 || day > monthDays(year, month)
                || weekday < 0 || weekday > 6 || hour < 0 || hour > 23 || minute < 0 || minute > 59
                || second < 0 || second > 59) throw new IllegalArgumentException("Invalid RTC calendar");
        calendar[0] = year; calendar[1] = month; calendar[2] = day; calendar[3] = weekday;
        calendar[4] = hour; calendar[5] = minute; calendar[6] = second;
        lastCycle = clock.now();
    }

    int output() { return output; }

    void pins(int previous, int current, int direction) {
        var cs = (current & 4) != 0;
        if (cs && !selected) { bits = 0; value = 0; parameter = 0; command = -1; reading = false; output = 0; }
        selected = cs;
        if (!cs) return;
        var rising = (previous & 1) == 0 && (current & 1) != 0;
        var falling = (previous & 1) != 0 && (current & 1) == 0;
        if (reading) {
            if (falling && (direction & 2) == 0) {
                output = readBit < parameter * 8 ? (parameters[readBit / 8] >>> (readBit & 7)) & 1 : 1;
                readBit++;
            }
            return;
        }
        if (!rising || (direction & 2) == 0) return;
        value |= ((current >>> 1) & 1) << bits++;
        if (bits != 8) return;
        var received = value; bits = 0; value = 0;
        if (command < 0) {
            if ((received & 0xf0) != 0x60) received = reverse(received);
            if ((received & 0xf0) != 0x60) return;
            command = received;
            var register = (command >>> 1) & 7;
            if (register == 0) { control = 0; setDateTime(0, 1, 1, 0, 0, 0, 0); }
            if ((command & 1) != 0) prepareRead(register);
        } else {
            if (parameter < 7) parameters[parameter++] = received;
            var register = (command >>> 1) & 7;
            if (parameter == length(register)) writeParameters(register);
        }
    }

    private void prepareRead(int register) {
        synchronize();
        parameter = length(register); readBit = 0; reading = true;
        for (var i = 0; i < 7; i++) parameters[i] = 255;
        if (register == 1) { parameters[0] = control; control &= ~0x80; }
        if (register == 2 || register == 3) {
            var start = register == 2 ? 0 : 4;
            for (var i = start; i < 7; i++) parameters[i - start] = bcd(calendar[i]);
            if ((control & 0x40) == 0) {
                var index = 4 - start;
                parameters[index] = bcd(calendar[4] % 12) | (calendar[4] >= 12 ? 0x80 : 0);
            }
        }
    }

    private void writeParameters(int register) {
        if (register == 1) { control = parameters[0] & 0x6a; return; }
        if (register != 2 && register != 3) return;
        synchronize();
        var start = register == 2 ? 0 : 4;
        var next = new int[7];
        System.arraycopy(calendar, 0, next, 0, 7);
        for (var i = start; i < 7; i++) next[i] = decimal(parameters[i - start] & (i == 4 ? 0x7f : 255));
        if ((control & 0x40) == 0) next[4] = next[4] % 12 + ((parameters[4 - start] & 0x80) != 0 ? 12 : 0);
        if (next[0] > 99 || next[1] < 1 || next[1] > 12 || next[2] < 1
                || next[2] > monthDays(next[0], next[1]) || next[3] > 6 || next[4] > 23
                || next[5] > 59 || next[6] > 59) return;
        setDateTime(next[0], next[1], next[2], next[3], next[4], next[5], next[6]);
    }

    private void synchronize() {
        var seconds = (clock.now() - lastCycle) / CycleScheduler.CLOCK_HZ;
        lastCycle += seconds * CycleScheduler.CLOCK_HZ;
        seconds += calendar[6] + 60L * calendar[5] + 3600L * calendar[4];
        calendar[6] = (int) (seconds % 60); calendar[5] = (int) ((seconds / 60) % 60);
        calendar[4] = (int) ((seconds / 3600) % 24);
        var days = seconds / 86400;
        calendar[3] = (calendar[3] + (int) (days % 7)) % 7;
        days %= 36525; // Two-digit year calendar repeats every 100 years.
        while (days-- > 0) {
            if (++calendar[2] > monthDays(calendar[0], calendar[1])) {
                calendar[2] = 1;
                if (++calendar[1] > 12) { calendar[1] = 1; calendar[0] = (calendar[0] + 1) % 100; }
            }
        }
    }

    private static int length(int register) { return register == 2 ? 7 : register == 3 ? 3 : 1; }
    private static int bcd(int value) { return (value / 10) * 16 + value % 10; }
    private static int decimal(int value) { return (value >>> 4) * 10 + (value & 15); }
    private static int reverse(int value) { int out = 0; for (var i = 0; i < 8; i++) { out = (out << 1) | (value & 1); value >>>= 1; } return out; }
    private static int monthDays(int year, int month) {
        if (month == 2) return (year & 3) == 0 ? 29 : 28;
        return month == 4 || month == 6 || month == 9 || month == 11 ? 30 : 31;
    }
}

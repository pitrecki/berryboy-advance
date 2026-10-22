package berryboy.serial;

import berryboy.EmulationException;
import berryboy.timing.CycleEvent;
import berryboy.timing.CycleScheduler;
import berryboy.timing.InterruptController;

/** Disconnected serial port: reset state, normal clocked shifts and JOY/GPIO registers. */
public final class SerialUnit implements CycleEvent {
    private final CycleScheduler clock;
    private final InterruptController interrupts;
    private final byte[] data = new byte[8];
    private final byte[] joy = new byte[8];
    private int control;
    private int rcnt;
    private int send;
    private int joyControl;
    private int joyStatus;

    public SerialUnit(CycleScheduler clock, InterruptController interrupts) { this.clock = clock; this.interrupts = interrupts; }

    public static boolean handles(int address) {
        return (address >= 0x04000120 && address < 0x0400012c) || (address >= 0x04000134 && address < 0x04000136)
                || (address >= 0x04000140 && address < 0x04000142) || (address >= 0x04000150 && address < 0x0400015a);
    }

    public int read8(int address) {
        if (address < 0x04000128) return data[address - 0x04000120] & 255;
        var register = address & ~1;
        int value;
        if (register == 0x04000128) value = (control & 0x7ffb) | ((control & 0x2000) == 0 ? 4 : 0); // Disconnected SI is high.
        else if (register == 0x0400012a) value = send;
        else if (register == 0x04000134) value = (rcnt & ~15) | (rcnt & (rcnt >>> 4) & 15) | (~(rcnt >>> 4) & 15);
        else if (register == 0x04000140) value = joyControl;
        else if (register == 0x04000158) value = joyStatus;
        else { if (address < 0x04000154) joyStatus &= ~8; return joy[address - 0x04000150] & 255; }
        return (value >>> ((address & 1) * 8)) & 255;
    }

    public void write8(int address, int value) {
        value &= 255;
        if (address < 0x04000128) { data[address - 0x04000120] = (byte) value; return; }
        var register = address & ~1;
        if (register == 0x04000128) {
            var previous = control;
            var next = replace(control, address, value) & 0x7ffb;
            if ((next & 0x80) != 0 && (next & 0x2000) != 0) throw new EmulationException("Multiplayer/UART transfer requires an unimplemented link backend");
            control = next;
            var restart = (previous & 0x80) == 0 || ((previous ^ control) & 0x3003) != 0;
            if ((control & 0x80) == 0 || restart) clock.cancel(this);
            if (restart && (control & 0x81) == 0x81 && (rcnt & 0x8000) == 0) {
                var bits = (control & 0x1000) != 0 ? 32 : 8;
                clock.schedule(this, clock.now() + bits * ((control & 2) != 0 ? 8 : 64));
            }
        } else if (register == 0x0400012a) send = replace(send, address, value);
        else if (register == 0x04000134) { rcnt = replace(rcnt, address, value) & 0xc1ff; if ((rcnt & 0x8000) != 0) clock.cancel(this); }
        else if (register == 0x04000140 && (address & 1) == 0) joyControl = (joyControl & ~(value & 7)) | (value & 0x40);
        else if (register == 0x04000158 && (address & 1) == 0) joyStatus = (joyStatus & 0x0a) | (value & 0x30);
        else if (address >= 0x04000150 && address < 0x04000158) {
            joy[address - 0x04000150] = (byte) value;
            if (address >= 0x04000154) joyStatus |= 2;
        }
    }

    public void fire() {
        control &= ~0x80;
        if ((control & 0x1000) != 0) for (var i = 0; i < 4; i++) data[i] = (byte) 255;
        else send = (send & 0xff00) | 255;
        if ((control & 0x4000) != 0) interrupts.request(0x80);
    }

    private static int replace(int current, int address, int value) {
        var shift = (address & 1) * 8;
        return (current & ~(255 << shift)) | (value << shift);
    }
}

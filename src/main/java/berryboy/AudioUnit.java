package berryboy;

/**
 * Initial GBA audio path: channel 2 plus stereo mixing at 32768 Hz.
 * Output is interleaved signed PCM16, left then right. No host audio APIs are used.
 * CPU-cycle synchronization, other channels and PWM/BIAS effects are not implemented.
 */
public final class AudioUnit {
    public static final int SAMPLE_RATE = 32768;
    public static final int CHANNEL_2_CONTROL = Memory.IO_BASE + 0x68;
    public static final int CHANNEL_2_FREQUENCY = Memory.IO_BASE + 0x6c;
    public static final int STEREO_CONTROL = Memory.IO_BASE + 0x80;
    public static final int MIX_CONTROL = Memory.IO_BASE + 0x82;
    public static final int MASTER_CONTROL = Memory.IO_BASE + 0x84;
    public static final int BIAS_CONTROL = Memory.IO_BASE + 0x88;

    public static final int MASTER_ENABLE = 0x80;
    public static final int CHANNEL_2_RIGHT = 1 << 9;
    public static final int CHANNEL_2_LEFT = 1 << 13;
    public static final int FULL_PSG_VOLUME = 2;
    public static final int RESTART_CHANNEL = 0x8000;
    public static final int ENABLE_LENGTH = 0x4000;

    private static final int CHANNEL_2_ACTIVE = 1 << 1;
    private static final int VOLUME_MASK = 7;
    private static final int PCM_SCALE = 64;
    private final PulseChannel channel2 = new PulseChannel();

    private int stereoControl;
    private int mixControl;
    private int biasControl = 0x0200;
    private boolean enabled;

    static boolean handles(int address) {
        return (address >= CHANNEL_2_CONTROL && address < CHANNEL_2_CONTROL + 8)
                || (address >= STEREO_CONTROL && address < BIAS_CONTROL + 2);
    }

    int read8(int address) {
        var register = address & ~1;
        var value = registerValue(register);
        return (value >>> ((address & 1) * 8)) & 0xff;
    }

    void write8(int address, int value) {
        value &= 0xff;
        if (address == MASTER_CONTROL) {
            enabled = (value & MASTER_ENABLE) != 0;
            if (!enabled) {
                channel2.reset();
                stereoControl = 0;
            }
        } else if (address == MIX_CONTROL) {
            if ((value & 3) == 3 || (value & 0x0c) != 0) {
                throw new EmulationException("Unsupported sound mix: Direct Sound and ratio 3 are not implemented");
            }
            mixControl = value & 3;
        } else if (address == MIX_CONTROL + 1) {
            if (value != 0) {
                throw new EmulationException("Direct Sound FIFO/timers are not implemented");
            }
        } else if ((address & ~1) == BIAS_CONTROL) {
            biasControl = replaceByte(biasControl, address, value) & 0xc3fe;
        } else if (enabled) {
            writeEnabledRegister(address, value);
        }
    }

    /** Each call advances audio time by exactly the number of stereo frames supplied. */
    public void render(short[] interleaved) {
        if (interleaved == null || (interleaved.length & 1) != 0) {
            throw new IllegalArgumentException("Audio buffer must contain complete stereo frames");
        }
        for (var index = 0; index < interleaved.length; index += 2) {
            var sample = enabled ? channel2.nextSample() * PCM_SCALE : 0;
            var scaled = sample >> (2 - mixControl);
            interleaved[index] = mixSide(scaled, CHANNEL_2_LEFT, stereoControl >>> 4);
            interleaved[index + 1] = mixSide(scaled, CHANNEL_2_RIGHT, stereoControl);
        }
    }

    private short mixSide(int sample, int route, int volume) {
        return (short) ((stereoControl & route) != 0 ? sample * ((volume & VOLUME_MASK) + 1) : 0);
    }

    private int registerValue(int register) {
        return switch (register) {
            case CHANNEL_2_CONTROL -> channel2.readControl();
            case CHANNEL_2_FREQUENCY -> channel2.readFrequency();
            case STEREO_CONTROL -> stereoControl;
            case MIX_CONTROL -> mixControl;
            case MASTER_CONTROL -> (enabled ? MASTER_ENABLE : 0) | (channel2.active() ? CHANNEL_2_ACTIVE : 0);
            case BIAS_CONTROL -> biasControl;
            default -> 0;
        };
    }

    private void writeEnabledRegister(int address, int value) {
        if (address == CHANNEL_2_CONTROL) {
            channel2.writeDutyAndLength(value);
        } else if (address == CHANNEL_2_CONTROL + 1) {
            channel2.writeEnvelope(value);
        } else if ((address & ~1) == CHANNEL_2_FREQUENCY) {
            channel2.writeFrequencyByte(address & 1, value);
        } else if ((address & ~1) == STEREO_CONTROL) {
            stereoControl = replaceByte(stereoControl, address, value) & 0xff77;
        }
    }

    private static int replaceByte(int current, int address, int value) {
        var shift = (address & 1) * 8;
        return (current & ~(0xff << shift)) | (value << shift);
    }
}

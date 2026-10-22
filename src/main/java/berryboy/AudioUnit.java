package berryboy;

/**
 * Four PSG channels plus stereo mixing at 32768 Hz.
 * Output is interleaved signed PCM16, left then right. No host audio APIs are used.
 * ClockedAudio drives sample generation for cycle-synchronized machine output.
 * PWM/BIAS transfer characteristics and sequencer edge cases remain unqualified.
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
    private final SweepChannel channel1 = new SweepChannel();
    private final WaveChannel channel3 = new WaveChannel();
    private final NoiseChannel channel4 = new NoiseChannel();
    private final SoundFifo fifoA = new SoundFifo();
    private final SoundFifo fifoB = new SoundFifo();

    private int stereoControl;
    private int mixControl;
    private int biasControl = 0x0200;
    private boolean enabled;

    static boolean handles(int address) {
        return (address >= Memory.IO_BASE + 0x60 && address < Memory.IO_BASE + 0x8a)
                || (address >= Memory.IO_BASE + 0x90 && address < Memory.IO_BASE + 0xa8);
    }

    int read8(int address) {
        if (address >= 0x04000090 && address < 0x040000a0) return channel3.readWave(address - 0x04000090);
        var register = address & ~1;
        var value = registerValue(register);
        return (value >>> ((address & 1) * 8)) & 0xff;
    }

    void write8(int address, int value) {
        value &= 0xff;
        if (address >= 0x04000090 && address < 0x040000a0) {
            channel3.writeWave(address - 0x04000090, value);
            return;
        }
        if (address >= 0x040000a0 && address < 0x040000a8) {
            if (address < 0x040000a4) fifoA.push(value); else fifoB.push(value);
            return;
        }
        if (address == MASTER_CONTROL) {
            enabled = (value & MASTER_ENABLE) != 0;
            if (!enabled) {
                channel2.reset();
                channel1.reset(); channel3.reset(); channel4.reset();
                stereoControl = 0;
            }
        } else if (address == MIX_CONTROL) {
            if ((value & 3) == 3) {
                throw new EmulationException("Reserved PSG ratio 3");
            }
            mixControl = (mixControl & 0xff00) | (value & 15);
        } else if (address == MIX_CONTROL + 1) {
            mixControl = (mixControl & 255) | ((value & 0x77) << 8);
            if ((value & 8) != 0) fifoA.reset();
            if ((value & 0x80) != 0) fifoB.reset();
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
            int left = 0;
            int right = 0;
            if (enabled) {
                var first = channel1.nextSample();
                var second = channel2.nextSample();
                var third = channel3.nextSample();
                var fourth = channel4.nextSample();
                left = routed(first, second, third, fourth, stereoControl >>> 12);
                right = routed(first, second, third, fourth, stereoControl >>> 8);
            }
            left = (left * PCM_SCALE >> (2 - (mixControl & 3))) * (((stereoControl >>> 4) & 7) + 1);
            right = (right * PCM_SCALE >> (2 - (mixControl & 3))) * ((stereoControl & 7) + 1);
            if (enabled) {
                var a = fifoA.output() * ((mixControl & 4) != 0 ? 256 : 128);
                var b = fifoB.output() * ((mixControl & 8) != 0 ? 256 : 128);
                if ((mixControl & 0x200) != 0) left += a;
                if ((mixControl & 0x100) != 0) right += a;
                if ((mixControl & 0x2000) != 0) left += b;
                if ((mixControl & 0x1000) != 0) right += b;
            }
            interleaved[index] = clip(left);
            interleaved[index + 1] = clip(right);
        }
    }

    /** Returns bit 0/1 for FIFO A/B DMA refill requests. */
    int timerOverflow(int timer) {
        if (!enabled) return 0;
        int request = 0;
        if (((mixControl >>> 10) & 1) == timer) { fifoA.tick(); if (fifoA.needsDma()) request |= 1; }
        if (((mixControl >>> 14) & 1) == timer) { fifoB.tick(); if (fifoB.needsDma()) request |= 2; }
        return request;
    }

    private static short clip(int sample) { return (short) (sample > 32767 ? 32767 : sample < -32768 ? -32768 : sample); }

    private int routed(int a, int b, int c, int d, int routes) {
        return ((routes & 1) != 0 ? a : 0) + ((routes & 2) != 0 ? b : 0)
                + ((routes & 4) != 0 ? c : 0) + ((routes & 8) != 0 ? d : 0);
    }

    private int registerValue(int register) {
        return switch (register) {
            case 0x04000060 -> channel1.readSweep();
            case 0x04000062 -> channel1.pulse.readControl();
            case 0x04000064 -> channel1.pulse.readFrequency();
            case 0x04000070 -> channel3.readControl();
            case 0x04000072 -> channel3.readVolume();
            case 0x04000074 -> channel3.readFrequency();
            case 0x04000078 -> channel4.readControl();
            case 0x0400007c -> channel4.readFrequency();
            case CHANNEL_2_CONTROL -> channel2.readControl();
            case CHANNEL_2_FREQUENCY -> channel2.readFrequency();
            case STEREO_CONTROL -> stereoControl;
            case MIX_CONTROL -> mixControl;
            case MASTER_CONTROL -> (enabled ? MASTER_ENABLE : 0) | (channel2.active() ? CHANNEL_2_ACTIVE : 0)
                    | (channel1.pulse.active() ? 1 : 0) | (channel3.active() ? 4 : 0) | (channel4.active() ? 8 : 0);
            case BIAS_CONTROL -> biasControl;
            default -> 0;
        };
    }

    private void writeEnabledRegister(int address, int value) {
        if (address == 0x04000060) channel1.writeSweep(value);
        else if (address == 0x04000062) channel1.pulse.writeDutyAndLength(value);
        else if (address == 0x04000063) channel1.pulse.writeEnvelope(value);
        else if ((address & ~1) == 0x04000064) channel1.writeFrequency(address & 1, value);
        else if (address == 0x04000070) channel3.writeControl(value);
        else if (address == 0x04000072) channel3.writeLength(value);
        else if (address == 0x04000073) channel3.writeVolume(value);
        else if ((address & ~1) == 0x04000074) channel3.writeFrequency(address & 1, value);
        else if (address == 0x04000078) channel4.writeLength(value);
        else if (address == 0x04000079) channel4.writeEnvelope(value);
        else if ((address & ~1) == 0x0400007c) channel4.writeFrequency(address & 1, value);
        else if (address == CHANNEL_2_CONTROL) {
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

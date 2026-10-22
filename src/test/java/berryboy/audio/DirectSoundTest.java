package berryboy.audio;

import berryboy.EmulatorFixtures;
import berryboy.Machine;

import static berryboy.EmulatorFixtures.arm;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class DirectSoundTest {
    @Test
    void timerPopsSignedBytesAndRoutesDirectSoundWithoutPsgVolume() {
        var m = sound();
        m.memory.write32(0x040000a0, 0x807f00ff);
        m.memory.write16(0x04000100, 0xff00);
        m.memory.write16(0x04000102, 0x80);
        var sample = new short[2];
        m.clock.advance(256);
        m.audio.render(sample);
        assertThat(sample).containsExactly((short) -256, (short) -256);
        m.clock.advance(256);
        m.audio.render(sample);
        assertThat(sample).containsOnly((short) 0);
        m.clock.advance(256);
        m.audio.render(sample);
        assertThat(sample).containsExactly((short) 32512, (short) 32512);
        m.clock.advance(256);
        m.audio.render(sample);
        assertThat(sample).containsExactly((short) -32768, (short) -32768);
        m.memory.write16(0x04000082, 0x0b06); // FIFO A reset, bit self clears.
        assertThat(m.memory.read16(0x04000082)).isEqualTo(0x0306);
        m.audio.render(sample);
        assertThat(sample).containsOnly((short) 0);
    }

    @Test
    void fifoDmaRefillsFourWordsAndRepeatsFromAdvancedSource() {
        var m = sound();
        for (int i = 0; i < 32; i++) m.memory.write8(0x02000000 + i, i + 1);
        m.memory.write32(0x040000bc, 0x02000000); // DMA1 source.
        m.memory.write32(0x040000c0, 0x040000a0);
        m.memory.write16(0x040000c4, 999); // Sound DMA ignores configured count.
        m.memory.write16(0x040000c6, 0xb200); // Special + repeat; width forced to word.
        m.memory.write16(0x04000100, 0xfe00);
        m.memory.write16(0x04000102, 0x80);
        m.clock.advanceTo(512); // Empty FIFO requests DMA.
        m.runCycles(30); // Startup 2 + four words at EWRAM 6 + I/O 1 cycles.
        assertThat(m.cpu.instructions()).isZero();
        assertThat(m.memory.read16(0x040000c6) & 0x8000).isEqualTo(0x8000);
        m.clock.advanceTo(1024);
        var sample = new short[2];
        m.audio.render(sample);
        assertThat(sample).containsExactly((short) 256, (short) 256);
        m.runCycles(30); // FIFO at 15 bytes requests another 16 bytes.
        m.clock.advanceTo(1536);
        m.audio.render(sample);
        assertThat(sample).containsExactly((short) 512, (short) 512);
    }

    @Test
    void fifoBSelectsTimerOneAndMixerSaturatesInsteadOfWrapping() {
        var m = sound();
        m.memory.write16(0x04000082, 0x730e); // A/B full, B timer 1, both routes.
        m.memory.write32(0x040000a0, 0x7f7f7f7f);
        m.memory.write32(0x040000a4, 0x7f7f7f7f);
        m.memory.write16(0x04000100, 0xff00);
        m.memory.write16(0x04000102, 0x80);
        m.memory.write16(0x04000104, 0xfe00);
        m.memory.write16(0x04000106, 0x80);
        var sample = new short[2];
        m.clock.advanceTo(256);
        m.audio.render(sample);
        assertThat(sample).containsOnly((short) 32512);
        m.clock.advanceTo(512);
        m.audio.render(sample);
        assertThat(sample).containsOnly((short) 32767);
    }

    private Machine sound() {
        var m = arm(0xe1a00000);
        m.memory.write16(0x04000084, 0x80);
        m.memory.write16(0x04000082, 0x0306);
        return m;
    }
}

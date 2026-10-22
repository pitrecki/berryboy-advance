package berryboy;

import static berryboy.EmulatorFixtures.arm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
class CompletePsgTest {
    @Test
    void sweepOverflowDisablesChannelOneAndRoutesItIndependently() {
        var m = sound(0x1177);
        m.memory.write16(0x04000060, 0x11); // 1/128s, increase, shift 1.
        m.memory.write16(0x04000062, 0xf080);
        m.memory.write16(0x04000064, 0x8400); // Frequency 1024.
        var sample = new short[2];
        m.audio.render(sample);
        assertThat(sample).containsExactly((short) 7680, (short) 7680);
        m.audio.render(new short[255 * 2]);
        assertThat(m.memory.read16(0x04000084) & 1).isZero();
    }

    @Test
    void waveRamBanksAndVolumeProduceSignedNibblesInOrder() {
        var m = sound(0x4477);
        m.memory.write16(0x04000070, 0x40); // CPU accesses bank 0.
        m.memory.write8(0x04000090, 0xf0);
        assertThat(m.memory.read8(0x04000090)).isEqualTo(0xf0);
        m.memory.write16(0x04000070, 0x80); // Play bank 0, CPU accesses bank 1.
        assertThat(m.memory.read8(0x04000090)).isZero();
        m.memory.write16(0x04000072, 0x2000); // Full wave volume.
        m.memory.write16(0x04000074, 0x87c0); // One nibble per 32768 Hz sample.
        var samples = new short[4];
        m.audio.render(samples);
        assertThat(samples).containsExactly((short) 3584, (short) 3584, (short) -4096, (short) -4096);
    }

    @Test
    void sevenBitNoiseFeedsBackAtBitSixAndLengthStopsAtBoundary() {
        var m = sound(0x8877);
        m.memory.write16(0x04000078, 0xf03f); // One length tick.
        m.memory.write16(0x0400007c, 0xc048); // 7-bit, one LFSR shift/sample.
        var samples = new short[16];
        m.audio.render(samples);
        assertThat(samples).startsWith((short) -7680, (short) -7680, (short) -7680, (short) -7680);
        assertThat(samples[12]).isEqualTo((short) -7680);
        assertThat(samples[14]).isEqualTo((short) 7680);
        m.audio.render(new short[120 * 2]);
        assertThat(m.memory.read16(0x04000084) & 8).isZero();
    }

    @Test
    void clockedOutputUsesGuestDeadlinesAndCountsBufferFailures() {
        var m = sound(0x2277);
        m.memory.write16(0x04000068, 0xf080);
        m.memory.write16(0x0400006c, 0x8700);
        var stream = m.memory.clockedAudio();
        stream.setCapture(true);
        m.clock.advanceTo(511);
        assertThat(stream.availableFrames()).isZero();
        m.memory.write16(0x04000080, 0x2000); // Only left before first sample.
        m.clock.advance(1);
        var sample = new short[2];
        assertThat(stream.drain(sample)).isEqualTo(1);
        assertThat(sample).containsExactly((short) 960, (short) 0);
        assertThat(stream.drain(sample)).isZero();
        assertThat(stream.underruns()).isEqualTo(1);
        m.clock.advance(8193L * 512);
        assertSoftly(s -> {
            s.assertThat(stream.producedFrames()).isEqualTo(8194);
            s.assertThat(stream.availableFrames()).isEqualTo(8192);
            s.assertThat(stream.overruns()).isEqualTo(1);
            s.assertThat(stream.consumedFrames()).isEqualTo(1);
        });
    }

    private Machine sound(int routes) {
        var m = arm(0xe1a00000);
        m.memory.write16(0x04000084, 0x80);
        m.memory.write16(0x04000080, routes);
        m.memory.write16(0x04000082, 2);
        return m;
    }
}

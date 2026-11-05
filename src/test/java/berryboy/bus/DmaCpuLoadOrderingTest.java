package berryboy.bus;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import berryboy.EmulatorFixtures;
import berryboy.Machine;
import berryboy.cpu.Cpu;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Retirement ordering, without freezing the unqualified activation latency. */
@Tag("core")
class DmaCpuLoadOrderingTest {
    static Stream<Arguments> scenarios() {
        return Stream.of(false, true).flatMap(thumb ->
                Stream.of(0x03000000, 0x02000000, 0x08000000).flatMap(source ->
                        Stream.of(1, 2).map(channels -> Arguments.of(thumb, source, channels))));
    }

    @ParameterizedTest(name = "thumb={0}, source={1}, channels={2}")
    @MethodSource("scenarios")
    void firstLoadRetiresAfterTheRequestedFifoBurst(boolean thumb, int source, int channels) {
        // NBA's real CPU completes FIFO DMA before this LDR retires. A delay-only
        // activation change wrongly retires the load first; exact latency is
        // separately unqualified by tools/nba-dma-activation-probe.cc.
        var image = EmulatorFixtures.blankRom();
        for (var i = 0; i < 16; i++) image[0x100 + i] = (byte) (i + 1);
        var m = new Machine(image);
        for (var i = 0; i < 16; i++) {
            m.memory.write8(0x03000100 + i, i + 1);
            m.memory.write8(0x02000100 + i, i + 1);
        }
        if (thumb) m.memory.write16(0x03000000, 0x6810); // LDR r0,[r2].
        else m.memory.write32(0x03000000, 0xe5920000);
        m.cpu.setCpsr(Cpu.SYSTEM | 0xc0 | (thumb ? Cpu.T : 0));
        m.cpu.setPc(0x03000000);
        m.cpu.setRegister(2, 0x03000200);
        m.memory.write32(0x03000200, 0x12345678);
        m.memory.write16(0x04000084, 0x80);
        m.memory.write16(0x04000082, 0x210c); // A right, B left, full volume, timer 0.
        for (var c = 1; c <= channels; c++) {
            var base = 0x040000b0 + c * 12;
            m.memory.write32(base, source + 0x100);
            m.memory.write32(base + 4, 0x040000a0 + (c - 1) * 4);
            m.memory.write16(base + 8, 4);
            m.memory.write16(base + 10, 0xb640);
            m.memory.dma().requestFifo(0x040000a0 + (c - 1) * 4);
        }
        while (m.cpu.instructions() == 0) {
            if (m.clock.now() > 1000) throw new IllegalStateException("Load did not retire");
            m.cpu.step();
        }
        assertSoftly(softly -> {
            softly.assertThat(m.cpu.instructions()).isEqualTo(1);
            softly.assertThat(m.cpu.pc()).isEqualTo(0x03000000 + (thumb ? 2 : 4));
            softly.assertThat(m.cpu.register(0)).isEqualTo(0x12345678);
        });
        var frame = new short[2];
        var right = new short[16];
        var left = new short[16];
        var expectedRight = new short[16];
        var expectedLeft = new short[16];
        for (var i = 0; i < 16; i++) {
            m.audio.timerOverflow(0); // Inspect without forwarding another DMA request.
            m.audio.render(frame);
            left[i] = frame[0]; right[i] = frame[1];
            expectedRight[i] = (short) ((i + 1) * 256);
            expectedLeft[i] = channels == 2 ? expectedRight[i] : 0;
        }
        assertSoftly(softly -> {
            softly.assertThat(right).as("FIFO A complete at first retirement").containsExactly(expectedRight);
            softly.assertThat(left).as("FIFO B complete at first retirement").containsExactly(expectedLeft);
        });
    }
}

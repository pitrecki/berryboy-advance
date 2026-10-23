package berryboy.bus;

import berryboy.EmulatorFixtures;
import berryboy.Machine;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

@Tag("core")
class DmaGamePakSourceTest {
    private static final int DMA_3 = 0x040000d4;
    private static final int DESTINATION = 0x03000100;
    private static final int TRANSFERS = 3;

    static Stream<Arguments> sourceModes() {
        return Stream.of(0x02000000, 0x08000000, 0x0a000000, 0x0c000000)
                .flatMap(region -> Stream.of(2, 4).flatMap(width -> Stream.of(0, 1, 2)
                        .map(mode -> Arguments.of(region, width, mode))));
    }

    @ParameterizedTest(name = "region={0}, width={1}, sourceMode={2}")
    @MethodSource("sourceModes")
    void gamePakAlwaysIncrementsWhileRamHonorsSourceMode(int region, int width, int mode) {
        // Same original pattern as tools/mgba-dma-source-probe.c; no commercial assets.
        var image = EmulatorFixtures.blankRom();
        for (var offset = 0; offset < image.length; offset += 2) {
            EmulatorFixtures.writeHalfword(image, offset, 0x1000 + offset / 2);
        }
        var machine = new Machine(image);
        for (var offset = 0; offset < image.length; offset += 2) {
            machine.memory.write16(0x02000000 + offset, 0x1000 + offset / 2);
        }
        var control = 0x8000 | (width == 4 ? 0x400 : 0) | (mode << 7);
        machine.memory.write32(DMA_3, region + 0x100);
        machine.memory.write32(DMA_3 + 4, DESTINATION);
        machine.memory.write16(DMA_3 + 8, TRANSFERS);
        machine.memory.write16(DMA_3 + 10, control);
        for (var transfer = 0; transfer < TRANSFERS; transfer++) machine.cpu.step();

        var sourceStep = region >= 0x08000000 || mode == 0 ? width : mode == 1 ? -width : 0;
        assertSoftly(softly -> {
            for (var transfer = 0; transfer < TRANSFERS; transfer++) {
                for (var halfword = 0; halfword < width / 2; halfword++) {
                    var sourceOffset = 0x100 + transfer * sourceStep + halfword * 2;
                    softly.assertThat(machine.memory.read16(DESTINATION + transfer * width + halfword * 2))
                            .as("transfer %s, halfword %s", transfer, halfword)
                            .isEqualTo(0x1000 + sourceOffset / 2);
                }
            }
            softly.assertThat(machine.cpu.instructions()).isZero();
            // The programmed address mode remains readable even when Game Pak overrides it.
            softly.assertThat(machine.memory.read16(DMA_3 + 10)).isEqualTo(control & ~0x8000);
            softly.assertThat(machine.memory.dma().runPending()).isFalse();
        });
    }
}

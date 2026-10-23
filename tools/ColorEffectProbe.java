package berryboy.video;

import berryboy.Machine;
import java.util.Arrays;

/** Original brightness cases matching nba-color-probe.cc; no external assets. */
public final class ColorEffectProbe {
    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Usage: berryboy.video.ColorEffectProbe");
        System.out.println("color,effect,coefficient,high_bit,rgb");
        for (var index = 0; index < 33; index++) {
            var color = index == 32 ? 0x7e51 : index * 0x421;
            for (var effect : new int[]{2, 3}) {
                for (var coefficient = 0; coefficient <= 16; coefficient++) {
                    for (var highBit = 0; highBit <= 1; highBit++) capture(color, effect, coefficient, highBit);
                }
            }
        }
        if (System.out.checkError()) throw new IllegalStateException("Cannot write probe output");
    }

    private static void capture(int color, int effect, int coefficient, int highBit) {
        var machine = new Machine(new byte[512]);
        machine.memory.write16(0x04000000, 0); // Mode 0, backdrop only, unblank.
        machine.memory.write16(0x05000000, color | (highBit << 15));
        machine.memory.write16(0x04000050, 0x20 | (effect << 6));
        machine.memory.write16(0x04000054, coefficient);
        machine.clock.advance(2L * LcdTiming.FRAME_CYCLES);
        var pixels = machine.memory.raster().completedFrame();
        var rgb = pixels[0];
        if (machine.memory.raster().completedFrames() != 2 || Arrays.stream(pixels).anyMatch(pixel -> pixel != rgb)) {
            throw new IllegalStateException("Settled backdrop frame is incomplete or nonuniform");
        }
        var software = new int[240];
        new SoftwarePpu(machine.memory).renderLine(0, software, 0);
        if (Arrays.stream(software).anyMatch(pixel -> pixel != rgb)) {
            throw new IllegalStateException("Software/raster brightness differs");
        }
        System.out.printf("0x%04x,%d,%d,%d,0x%06x%n", color, effect, coefficient, highBit, rgb);
    }
}

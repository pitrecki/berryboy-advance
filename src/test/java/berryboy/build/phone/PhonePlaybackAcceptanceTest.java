package berryboy.build.phone;

import static org.assertj.core.api.Assertions.assertThat;

import berryboy.DemoRom;
import berryboy.MachineState;
import berryboy.build.PhoneHarness;
import berryboy.cartridge.Cartridge;
import berryboy.video.LcdTiming;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Combined output policy through production UI commands; no physical audio/display claim. */
@Tag("build")
class PhonePlaybackAcceptanceTest {
    @TempDir Path temporary;

    @Test
    void combinesAudioSpeedScalingAndFrameSkipWithoutDroppingGuestWorkAndRetainsPreferences() throws Exception {
        var rom = toneCartridge(); var bios = new byte[16384];
        word(bios, 0, 0xe59ff018); word(bios, 0x20, 0x08000000);
        var game = temporary.resolve("tone.gba"); var biosFile = temporary.resolve("bios.bin");
        Files.write(game, rom); Files.write(biosFile, bios);
        var classes = temporary.resolve("classes");
        var root = Path.of(System.getProperty("berryboy.projectRoot", ".")).toAbsolutePath();
        assertThat(PhoneHarness.compileUi(root, classes)).isZero();
        var audio = new ArrayBlockingQueue<short[]>(128);
        try (var h = new PhoneUiDriver(classes, game, new Cartridge(rom).checksum())) {
            h.captureAudio(packet -> { if (!audio.offer(packet)) throw new IllegalStateException("Unconsumed owned PCM"); });
            h.select("Open game"); h.text(0, game.toUri().toASCIIString()); h.text(1, biosFile.toUri().toASCIIString());
            h.command("Start game"); h.frames(12); h.pause();
            var stereo = drain(audio);
            assertThat(peak(stereo, 0)).as("left-only test tone at default 80 percent volume").isEqualTo(6144);
            assertThat(peak(stereo, 1)).isZero();

            h.select("Display & audio"); h.select("Volume");
            for (int i = 0; i < 4; i++) { h.press('j'); h.release('j'); }
            assertThat(h.screen().value(0)).isEqualTo("50%"); h.command("Back");
            var half = play(h, audio, 3);
            assertThat(peak(half, 0)).isEqualTo(3840); assertThat(peak(half, 1)).isZero();
            h.select("Display & audio"); h.select("Sound"); h.command("Back");
            var mono = play(h, audio, 3); assertMono(mono);
            assertThat(peak(mono, 0)).isEqualTo(1920);

            h.select("Display & audio"); h.select("Mute"); h.command("Back");
            assertThat(play(h, audio, 3)).isEmpty();
            for (int speed : new int[]{2, 4, 8, 1}) {
                h.select("Speed"); assertThat(h.screen().value(1)).isEqualTo(speed + "x");
                assertThat(play(h, audio, 3)).as("independent mute survives returning to 1x").isEmpty();
            }
            h.select("Display & audio"); assertThat(h.screen().value(1)).isEqualTo("On");
            h.select("Mute"); h.command("Back");
            assertThat(peak(play(h, audio, 3), 0)).isEqualTo(1920);
            for (int speed : new int[]{2, 4, 8, 1}) {
                h.select("Speed");
                var packets = play(h, audio, 3);
                if (speed == 1) assertThat(peak(packets, 0)).as("1x restores enabled audio").isEqualTo(1920);
                else assertThat(packets).as("accelerated %sx produces no output packets", speed).isEmpty();
            }

            // Native/integer/fit/stretch must retain every source pixel, including the last row.
            for (var view : new int[][]{{2, 0, 27, 640, 426}, {3, 0, 0, 640, 480},
                    {0, 200, 160, 240, 160}, {1, 80, 80, 480, 320}, {2, 0, 27, 640, 426}}) {
                if (h.screen().scaling() != view[0]) {
                    h.select("Display & audio"); h.select("Game image"); h.command("Back");
                }
                audio.clear(); h.select("Resume game"); h.frames(3);
                assertThat(h.screen().scaling()).isEqualTo(view[0]);
                assertWholeFrame(h, view); h.pause(); drain(audio);
            }

            h.select("Display & audio"); h.select("FPS counter");
            for (int i = 0; i < 5; i++) h.select("Frame skip");
            assertThat(h.screen().value(3)).isEqualTo("1 in 6"); h.command("Back");
            var before = MachineState.load(rom, bios, h.save(0));
            var presented = h.presentations(); audio.clear();
            h.select("Resume game"); h.frames(12);
            assertThat(h.screen().fps()).matches("GBA [0-9]+\\.[0-9]+ / Draw .* fps");
            h.render(); h.pause();
            var after = MachineState.load(rom, bios, h.save(1));
            var shown = h.presentations() - presented;
            var packets = drain(audio);
            var guest = after.clock.now() / LcdTiming.FRAME_CYCLES - before.clock.now() / LcdTiming.FRAME_CYCLES;
            assertThat(guest).as("frame skip changes submission, every hidden guest frame still executes")
                    .isBetween(1L + (shown - 1L) * 6, shown * 6L);
            assertThat(packets).hasSize((int) guest);
            assertThat(packets.stream().mapToLong(p -> p.length / 2).sum())
                    .isEqualTo(after.memory.clockedAudio().producedFrames() - before.memory.clockedAudio().producedFrames());
            assertThat(after.memory.clockedAudio().overruns()).isZero();
            assertThat(after.memory.clockedAudio().underruns()).isZero();

            h.reopen(); h.select("Display & audio");
            var values = new String[]{"50%", "Off", "Mono", "1 in 6", "On", "Full screen", "Show", "1x"};
            for (int i = 0; i < values.length; i++) assertThat(h.screen().value(i)).isEqualTo(values[i]);
            h.command("Back"); h.select("Open game"); h.command("Start game"); h.frames(3); h.pause();
            assertThat(peak(drain(audio), 0)).as("reopened game uses the persisted mixer preferences").isEqualTo(1920);
        }
    }

    private static List<short[]> play(PhoneUiDriver h, ArrayBlockingQueue<short[]> audio, int frames) throws Exception {
        audio.clear(); h.select("Resume game"); h.frames(frames); h.pause(); return drain(audio);
    }
    private static List<short[]> drain(ArrayBlockingQueue<short[]> audio) {
        var packets = new ArrayList<short[]>(); audio.drainTo(packets); return packets;
    }
    private static int peak(List<short[]> packets, int channel) {
        int peak = 0;
        for (var packet : packets) for (int i = channel; i < packet.length; i += 2) peak = Math.max(peak, Math.abs((int) packet[i]));
        return peak;
    }
    private static void assertMono(List<short[]> packets) {
        assertThat(packets).isNotEmpty();
        for (var packet : packets) {
            boolean equal = true;
            for (int i = 0; i < packet.length; i += 2) equal &= packet[i] == packet[i + 1];
            assertThat(equal).as("every output stereo pair contains the same mono sample").isTrue();
        }
    }
    private static void assertWholeFrame(PhoneUiDriver h, int[] view) throws Exception {
        var image = h.render(); var expected = h.screen().frame(); var actual = new int[240 * 160];
        for (int y = 0; y < 160; y++) for (int x = 0; x < 240; x++) {
            int screenX = view[1] + (x * view[3] + 239) / 240;
            int screenY = view[2] + (y * view[4] + 159) / 160;
            actual[y * 240 + x] = image.getRGB(screenX, screenY) & 0xffffff;
        }
        assertThat(actual).as("all 240x160 source pixels remain visible in mode %s", view[0]).containsExactly(expected);
    }
    private static byte[] toneCartridge() {
        var rom = DemoRom.create();
        half(rom, 0xfa, 0xe011); // After the gradient, jump to Thumb trampoline at 0x120.
        half(rom, 0x120, 0x4b01); half(rom, 0x122, 0x4718); word(rom, 0x128, 0x08000140);
        word(rom, 0x140, 0xe59f0078); word(rom, 0x1c0, 0x04000060);
        word(rom, 0x144, 0xe59f1078); word(rom, 0x1c4, 0x80); word(rom, 0x148, 0xe1c012b4); // Master on.
        word(rom, 0x14c, 0xe59f1074); word(rom, 0x1c8, 0x1077); word(rom, 0x150, 0xe1c012b0); // Left only.
        word(rom, 0x154, 0xe59f1070); word(rom, 0x1cc, 2); word(rom, 0x158, 0xe1c012b2); // Full PSG ratio.
        word(rom, 0x15c, 0xe59f106c); word(rom, 0x1d0, 0xf080); word(rom, 0x160, 0xe1c010b2); // Fixed-volume square.
        word(rom, 0x164, 0xe59f1068); word(rom, 0x1d4, 0x8400); word(rom, 0x168, 0xe1c010b4); // Trigger.
        word(rom, 0x16c, 0xeafffffe);
        return rom;
    }
    private static void half(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value; bytes[at + 1] = (byte) (value >>> 8);
    }
    private static void word(byte[] bytes, int at, int value) {
        half(bytes, at, value); half(bytes, at + 2, value >>> 16);
    }
}

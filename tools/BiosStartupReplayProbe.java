package berryboy.desktop;

import berryboy.Machine;
import berryboy.MachineState;
import berryboy.boot.BiosStartup;
import berryboy.desktop.capture.JsonReport;
import berryboy.video.LcdTiming;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Properties;

/** Local assets only: compare authentic visible and hidden BIOS execution at every guest frame. */
public final class BiosStartupReplayProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: BiosStartupReplayProbe report.json scenario.properties...");
        var results = new LinkedHashMap<String, Object>();
        for (var i = 1; i < args.length; i++) {
            var properties = new Properties();
            try (var reader = Files.newBufferedReader(Path.of(args[i]))) { properties.load(reader); }
            var rom = Files.readAllBytes(Path.of(properties.getProperty("rom")));
            var bios = Files.readAllBytes(Path.of(properties.getProperty("bios")));
            var romHash = ScenarioRunner.hash(rom); var biosHash = ScenarioRunner.hash(bios);
            if (!romHash.equals(properties.getProperty("rom_sha256")) || !biosHash.equals(properties.getProperty("bios_sha256"))) {
                throw new IllegalArgumentException("Asset mismatch: " + args[i]);
            }
            var hidden = new Machine(rom, bios); var visible = new Machine(rom, bios);
            hidden.memory.clockedAudio().setCapture(true); visible.memory.clockedAudio().setCapture(true);
            var startup = new BiosStartup(hidden); var frames = 0; long samples = 0;
            var hiddenPcm = new short[2048]; var visiblePcm = new short[2048];
            while (!startup.complete()) {
                var target = (hidden.clock.now() / LcdTiming.FRAME_CYCLES + 1) * LcdTiming.FRAME_CYCLES;
                startup.runCycles(target - hidden.clock.now()); visible.runCycles(target - visible.clock.now());
                var count = hidden.memory.clockedAudio().availableFrames();
                if (visible.memory.clockedAudio().availableFrames() != count || count > hiddenPcm.length / 2
                        || hidden.memory.clockedAudio().overruns() != 0 || visible.memory.clockedAudio().overruns() != 0) {
                    throw new IllegalStateException("Audio capture mismatch");
                }
                hidden.memory.clockedAudio().drain(hiddenPcm, count); visible.memory.clockedAudio().drain(visiblePcm, count);
                if (!Arrays.equals(hiddenPcm, visiblePcm)
                        || !Arrays.equals(hidden.memory.raster().completedFrame(), visible.memory.raster().completedFrame())
                        || !Arrays.equals(MachineState.save(hidden), MachineState.save(visible))) {
                    throw new IllegalStateException("Visible/hidden execution diverged at frame " + frames);
                }
                samples += count;
                if (++frames > 610) throw new IllegalStateException("Startup exceeded bounded guest duration");
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("rom_sha256", romHash); result.put("bios_sha256", biosHash);
            result.put("guest_frames_compared", frames); result.put("stereo_pcm_frames_compared", samples);
            result.put("guest_cycles", hidden.clock.now()); result.put("state_sha256", ScenarioRunner.hash(MachineState.save(hidden)));
            result.put("rgb_pcm_and_complete_state_identical_at_every_frame", true);
            results.put(hidden.cartridge.title(), result);
            System.out.println(hidden.cartridge.title() + ": " + frames + " frames identical");
        }
        Files.writeString(Path.of(args[0]), JsonReport.encode(results) + "\n");
    }
}

package berryboy.desktop;

import berryboy.Machine;
import berryboy.MachineState;
import berryboy.cartridge.Cartridge;
import berryboy.desktop.capture.JsonReport;
import berryboy.input.Keypad;
import berryboy.mobile.audio.OutputMixer;
import berryboy.mobile.library.LibraryProfile;
import berryboy.mobile.settings.PhoneOptions;
import berryboy.phone.PhoneAudio;
import berryboy.phone.UiHarness;
import berryboy.video.LcdTiming;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.microedition.rms.RecordStore;

/** M1: cold application launch, player route and UI state round trip, with owned-buffer replay. */
public final class CaptureGoldenSunSession {
    private final Path output;
    private final ArrayBlockingQueue<Integer> ready = new ArrayBlockingQueue<>(1);
    private final ArrayBlockingQueue<short[]> audio = new ArrayBlockingQueue<>(1);
    private final Semaphore next = new Semaphore(0);
    private final LinkedHashMap<String,Object> timeline = new LinkedHashMap<>();
    private final LinkedHashMap<String,Object> checkpoints = new LinkedHashMap<>();
    private static final int REPLAY_FRAMES = 120;
    private int images, presented, held;
    private boolean gated;

    private CaptureGoldenSunSession(Path output) { this.output = output; }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("Usage: CaptureGoldenSunSession AGSE.properties input.tsv freshOutput sourceCommit sourceDirty");
        var config = Path.of(args[0]).toAbsolutePath(); var props = new Properties();
        try (var reader = Files.newBufferedReader(config)) { props.load(reader); }
        var rom = verified(config, props, "rom"); var bios = verified(config, props, "bios");
        var input = Path.of(args[1]); var inputBytes = Files.readAllBytes(input);
        var events = inputs(new String(inputBytes, StandardCharsets.UTF_8));
        var sources = sourceHashes();
        var out = Path.of(args[2]).toAbsolutePath(); Files.createDirectory(out);
        Files.createDirectory(out.resolve("checkpoints")); Files.createDirectory(out.resolve("states"));
        var assets = Files.createDirectory(out.resolve("local-files"));
        var romCopy = assets.resolve("Golden Sun.gba"); var biosCopy = assets.resolve("gba_bios.bin");
        Files.write(romCopy, rom); Files.write(biosCopy, bios);
        var url = romCopy.toUri().toASCIIString(); var checksum = new Cartridge(rom).checksum();
        var profile = new LibraryProfile(); profile.setDirectory(assets.toUri().toASCIIString());
        var record = RecordStore.openRecordStore("berryboy-library", true); var bytes = profile.encode();
        record.addRecord(bytes, 0, bytes.length); record.closeRecordStore();
        var capture = new CaptureGoldenSunSession(out);
        try (var ui = new UiHarness()) {
            ui.canvas.presentationHook = () -> {
                try { capture.ready.put(++capture.presented); capture.next.acquire(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            };
            try {
                capture.hold(ui, "Home", 8);
                ui.select("Display & audio"); ui.select("BIOS file");
                ui.text(0, biosCopy.toUri().toASCIIString()); ui.command("Browse BIOS");
                waitFor(() -> ui.display.getCurrent().getTitle().equals("Choose GBA BIOS"));
                capture.hold(ui, "External BIOS picker", 6); ui.choose("gba_bios.bin");
                capture.hold(ui, "Selected BIOS", 5); ui.command("Load BIOS");
                waitFor(() -> ui.screen().message().contains("BIOS saved")); ui.awaitIdle();
                capture.hold(ui, "Validated BIOS preference", 6); back(ui);
                ui.select("Open game"); capture.hold(ui, "Open game", 5);
                ui.command("Browse ROM"); waitFor(() -> ui.display.getCurrent().getTitle().equals("Choose GBA ROM"));
                capture.hold(ui, "External ROM picker", 6); ui.choose("Golden Sun.gba");
                capture.hold(ui, "Selected ROM and BIOS", 6); ui.command("Start game");
                capture.mark("Cold BIOS boot at 1x");
                for (var frame = 1; frame <= 300; frame++) {
                    capture.await(ui);
                    if (frame % 6 == 0) capture.frame(ui);
                    if (frame < 300) capture.release();
                }
                capture.pause(ui); capture.hold(ui, "Pause after BIOS boot", 5);
                ui.select("Speed"); ui.awaitIdle(); capture.hold(ui, "Speed 2x", 3);
                ui.select("Speed"); ui.awaitIdle(); capture.hold(ui, "Speed 4x / muted", 5);
                ui.select("Resume game"); capture.mark("Same cold-started game, opening and player input at 4x");
                var index = 0; var lastFrame = events.getLast()[0];
                var named = new LinkedHashMap<Integer,String>();
                named.put(901,"wake-up-dialogue"); named.put(5449,"psynergy-menu"); named.put(5481,"controlled-field");
                named.put(5529,"walked-to-door"); named.put(5673,"entered-house"); named.put(5813,"examined-barrel");
                named.put(lastFrame,"ready-for-save");
                for (var frame = 301; frame <= lastFrame; frame += 4) {
                    capture.await(ui);
                    if (((frame - 301) / 4) % 6 == 0) capture.frame(ui);
                    if (named.containsKey(frame)) capture.checkpoint(ui, named.get(frame), frame);
                    if (index < events.size() && events.get(index)[0] == frame) capture.keys(ui, events.get(index++)[1]);
                    if (index < events.size() && events.get(index)[0] < frame) throw new IllegalStateException("Missed input boundary");
                    if (frame < lastFrame) capture.release();
                }
                if (index != events.size() || capture.held != 0) throw new IllegalStateException("Incomplete/unreleased input route");
                capture.pause(ui); ui.select("Speed"); ui.awaitIdle(); capture.hold(ui, "Speed 8x", 3);
                ui.select("Speed"); ui.awaitIdle(); capture.hold(ui, "Normal speed / sound restored", 5);
                var initial = capture.save(ui, url, checksum, 0);
                var snapshot = MachineState.load(rom, bios, initial);
                if (snapshot.clock.now()/LcdTiming.FRAME_CYCLES != lastFrame) throw new IllegalStateException("Guest-frame identity changed");
                var first = capture.window(ui, rom, bios, initial, "first");
                var future = capture.save(ui, url, checksum, 1);
                requireSame(future, first.state(), "First UI continuation state");
                ui.select("Load state"); ui.awaitIdle(); ui.select("Slot 1"); ui.awaitIdle();
                waitFor(() -> ui.screen().message().equals("Loaded slot 1"));
                requireSame(ui.screen().frame(), snapshot.memory.raster().completedFrame(), "Restored screen");
                capture.hold(ui, "Loaded slot 1 / original scene", 10);
                capture.checkpoint(ui,"loaded-slot-1",lastFrame);
                var second = capture.window(ui, rom, bios, initial, "resumed");
                var resumed = capture.save(ui, url, checksum, 2);
                requireSame(resumed, second.state(), "Restored UI continuation state");
                requireSame(future, resumed, "Complete state after both UI continuations");
                if (!first.report().equals(second.report())) throw new IllegalStateException("RGB/PCM replay reports differ");
                Files.write(out.resolve("states/initial.bbs"), initial); Files.write(out.resolve("states/future.bbs"), future);
                Files.write(out.resolve("states/resumed.bbs"), resumed);
                capture.hold(ui,"Continued play / matching save and load result",8);
                var report = new LinkedHashMap<String,Object>();
                report.put("frames",capture.images); report.put("source_commit",args[3]); report.put("source_dirty",Boolean.parseBoolean(args[4]));
                report.put("rom_sha256",ScenarioRunner.hash(rom)); report.put("bios_sha256",ScenarioRunner.hash(bios));
                report.put("input_sha256",ScenarioRunner.hash(inputBytes)); report.put("cold_route_guest_frames",lastFrame);
                report.put("presentation_frames",capture.presented); report.put("timeline",capture.timeline); report.put("checkpoints",capture.checkpoints);
                report.put("capture","Production PhoneCanvas/PhoneSession/FilePicker and renderer with headless LCDUI/filesystem/RMS/audio adapters. Host fonts; no device/simulator execution or physical FPS/audio claim.");
                report.put("continuity","One cold-started game with fresh backup, full BIOS boot, recorded movement, house entry and barrel interaction. Every guest frame executes. Explicit UI Load slot 1 is the only state restore; no startup snapshot or scene substitution. 1x boot, visible 4x control, then 1x replay. Six presentations per 100ms video image; menu holds are scripted dwell times, not measured host playback performance.");
                report.put("replay",first.report()); report.put("saved_state_sha256",ScenarioRunner.hash(initial));
                report.put("future_state_sha256",ScenarioRunner.hash(future)); report.put("resumed_state_sha256",ScenarioRunner.hash(resumed));
                report.put("both_ui_continuations_and_owned_clone_exact",true); report.put("original_assets_unchanged",true);
                report.put("source_sha256",sources);
                requireSame(inputBytes,Files.readAllBytes(input),"Input recording during capture");
                if (!sources.equals(sourceHashes())) throw new IllegalStateException("Capture source changed during recording");
                requireSame(rom,verified(config,props,"rom"),"Original ROM"); requireSame(bios,verified(config,props,"bios"),"Original BIOS");
                Files.writeString(out.resolve("capture.json"),JsonReport.encode(report)+"\n");
                System.out.println("PASS: M1 cold GUI route; barrel interaction; identical 120-frame RGB/PCM/full-state UI replays");
            } finally { PhoneAudio.packetHook=null; ui.canvas.presentationHook=null; capture.next.release(); }
        }
    }

    private record Replay(byte[] state, LinkedHashMap<String,Object> report) {}

    private Replay window(UiHarness ui, byte[] rom, byte[] bios, byte[] state, String label) throws Exception {
        var replay = MachineState.load(rom, bios, state);
        var start = replay.clock.now();
        var first = start / LcdTiming.FRAME_CYCLES;
        var options = options();
        if (options.speed() != 1 || options.frameSkip() != 0 || options.muted()) {
            throw new IllegalStateException("Replay requires normal unmuted playback");
        }
        var rgb = MessageDigest.getInstance("SHA-256");
        var pcm = MessageDigest.getInstance("SHA-256");
        var raw = MessageDigest.getInstance("SHA-256");
        short[] expected = new short[2048];
        long samples = 0;
        PhoneAudio.packetHook = packet -> {
            if (!audio.offer(packet)) throw new IllegalStateException("Unconsumed audio packet");
        };
        ui.select("Resume game");
        mark("120-frame controlled continuation at 1x");
        try {
            for (var i = 1; i <= REPLAY_FRAMES; i++) {
                await(ui);
                var packet = audio.poll();
                if (packet == null) throw new IllegalStateException("Missing PCM packet");
                replay.memory.setPressedKeys(replayKeys(i));
                replay.runCycles((first + i) * LcdTiming.FRAME_CYCLES - replay.clock.now());
                var count = replay.memory.clockedAudio().availableFrames();
                replay.memory.clockedAudio().drain(expected, count);
                hashPcm(raw, Arrays.copyOf(expected, count * 2));
                OutputMixer.apply(expected, count, options);
                requireSame(packet, Arrays.copyOf(expected, count * 2), "PCM frame " + i);
                requireSame(ui.screen().frame(), replay.memory.raster().completedFrame(), "RGB frame " + i);
                for (var pixel : ui.screen().frame()) {
                    rgb.update((byte) (pixel >>> 16));
                    rgb.update((byte) (pixel >>> 8));
                    rgb.update((byte) pixel);
                }
                hashPcm(pcm, packet);
                samples += count;
                if (i % 6 == 0) frame(ui);
                if (i == 25 || i == 49 || i == 73 || i == 97 || i == REPLAY_FRAMES) {
                    checkpoint(ui, label + "-replay-" + i, (int) first + i);
                }
                keys(ui, replayKeys(i + 1));
                if (i < REPLAY_FRAMES) release();
            }
            pause(ui);
        } finally {
            PhoneAudio.packetHook = null;
        }
        if (!audio.isEmpty() || replay.memory.clockedAudio().overruns() != 0
                || replay.memory.clockedAudio().underruns() != 0) {
            throw new IllegalStateException("Audio continuity failed");
        }
        if (replay.memory.backup().dirty()) {
            throw new IllegalStateException("Unexpected battery-save write in this replay route");
        }
        var result = new LinkedHashMap<String,Object>();
        result.put("frames", REPLAY_FRAMES);
        result.put("audio_frames", samples);
        result.put("audio_overruns", 0);
        result.put("audio_underruns", 0);
        result.put("start_clock", start);
        result.put("final_clock", replay.clock.now());
        result.put("rgb_sha256", HexFormat.of().formatHex(rgb.digest()));
        result.put("raw_pcm_sha256", HexFormat.of().formatHex(raw.digest()));
        result.put("mixed_pcm_sha256", HexFormat.of().formatHex(pcm.digest()));
        result.put("comparison", "Every presented RGB array and owned post-mixer PCM packet matches the saved-state clone, then both complete saved states match.");
        return new Replay(MachineState.save(replay), result);
    }
    private static int replayKeys(int frame) {
        // Resume releases all buttons; subsequent changes use the presentation boundary.
        if (frame >= 2 && frame <= 25) return Keypad.RIGHT;
        if (frame >= 26 && frame <= 49) return Keypad.LEFT;
        if (frame >= 50 && frame <= 73) return Keypad.UP;
        if (frame >= 74 && frame <= 97) return Keypad.DOWN;
        return 0;
    }
    private byte[] save(UiHarness ui, String url, int checksum, int slot) throws Exception {
        ui.select("Save state");
        ui.awaitIdle();
        hold(ui, "Save menu / slot " + (slot + 1), 4);
        ui.select("Slot " + (slot + 1));
        ui.awaitIdle();
        waitFor(() -> ui.screen().message().equals("Saved slot " + (slot + 1)));
        hold(ui, "Saved slot " + (slot + 1), 6);
        var state = ui.readSlot(url, checksum, slot);
        if (state == null) throw new IllegalStateException("Missing committed slot");
        back(ui);
        return state;
    }
    private void pause(UiHarness ui) throws Exception {
        keys(ui, 0);
        back(ui);
        release();
        ui.awaitIdle();
    }
    private static void back(UiHarness ui) {
        ui.canvas.press('#');
        ui.canvas.release('#');
    }
    private void keys(UiHarness ui, int desired) {
        int[] codes = {'z','x','\b','\n','l','j','i','k','s','a'};
        for (var i = 0; i < codes.length; i++) {
            if ((held & (1 << i)) != 0 && (desired & (1 << i)) == 0) ui.canvas.release(codes[i]);
        }
        for (var i = 0; i < codes.length; i++) {
            if ((held & (1 << i)) == 0 && (desired & (1 << i)) != 0) ui.canvas.press(codes[i]);
        }
        held = desired;
    }
    private void await(UiHarness ui) throws Exception {
        if (ready.poll(30, TimeUnit.SECONDS) == null) {
            throw new IllegalStateException("No presentation: " + ui.screen().message());
        }
        gated = true;
    }
    private void release() {
        if (!gated) throw new IllegalStateException("No gated frame");
        gated = false;
        next.release();
    }
    private void mark(String label) {
        timeline.merge(Integer.toString(images * 100), label, (before, after) -> before + "; " + after);
    }
    private void hold(UiHarness ui, String label, int count) throws Exception {
        mark(label);
        for (var i = 0; i < count; i++) frame(ui);
    }
    private void frame(UiHarness ui) throws Exception {
        ImageIO.write(CaptureLaunchWalkthrough.render(ui), "png",
                output.resolve("%04d.png".formatted(images++)).toFile());
    }
    private void checkpoint(UiHarness ui,String name,int guestFrame)throws Exception{
        var image=new BufferedImage(240,160,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,240,160,ui.screen().frame(),0,240);
        var path=output.resolve("checkpoints/"+name+".png");ImageIO.write(image,"png",path.toFile());
        var item=new LinkedHashMap<String,Object>();item.put("guest_frame",guestFrame);item.put("png_sha256",ScenarioRunner.hash(Files.readAllBytes(path)));
        item.put("video_ms",images*100);checkpoints.put(name,item);mark(name);frame(ui);
        System.out.println("Checkpoint "+name+" at guest frame "+guestFrame);
    }
    private static PhoneOptions options()throws Exception{var r=RecordStore.openRecordStore("berryboy-options",true);try{return PhoneOptions.decode(r.getRecord(1));}finally{r.closeRecordStore();}}
    private static ArrayList<int[]> inputs(String text){
        var result=new ArrayList<int[]>();int previous=300;
        for(var line:text.lines().toList()){
            if(line.isBlank()||line.stripLeading().startsWith("#"))continue;var parts=line.strip().split("\\s+");
            if(parts.length!=2)throw new IllegalArgumentException("Expected after_guest_frame and keys");
            int frame=Integer.parseInt(parts[0]),keys=Integer.decode(parts[1]);
            if(frame<=previous||frame>12000||(frame-301)%4!=0||keys<0||keys>1023)throw new IllegalArgumentException("Invalid input boundary");
            result.add(new int[]{frame,keys});previous=frame;
        }
        if(result.isEmpty()||result.getFirst()[0]!=301||result.getLast()[1]!=0)throw new IllegalArgumentException("Incomplete route");return result;
    }
    private static LinkedHashMap<String,Object> sourceHashes()throws Exception{
        var sources=new LinkedHashMap<String,Object>();
        for(var path:new String[]{"tools/CaptureGoldenSunSession.java","tools/CaptureLaunchWalkthrough.java",
                "tools/capture-golden-sun-session.sh","src/test/java/berryboy/build/PhoneHarness.java",
                "src/test/fixtures/phone-ui/berryboy/phone/UiHarness.java",
                "src/test/fixtures/phone/berryboy/phone/PhoneAudio.java",
                "platform/midp/berryboy/phone/PhoneCanvas.java","platform/midp/berryboy/phone/PhoneSession.java"}){
            sources.put(path,ScenarioRunner.hash(Files.readAllBytes(Path.of(path))));
        }
        return sources;
    }
    private static byte[] verified(Path config,Properties props,String key)throws Exception{
        var path=Path.of(props.getProperty(key));if(!path.isAbsolute())path=config.getParent().resolve(path);var data=Files.readAllBytes(path);
        if(!ScenarioRunner.hash(data).equals(props.getProperty(key+"_sha256")))throw new IllegalArgumentException(key+" hash mismatch");return data;
    }
    private static void hashPcm(MessageDigest digest,short[] samples){for(var sample:samples){digest.update((byte)sample);digest.update((byte)(sample>>>8));}}
    private static void requireSame(byte[] a,byte[] b,String label){if(!Arrays.equals(a,b))throw new IllegalStateException(label+" differs");}
    private static void requireSame(short[] a,short[] b,String label){if(!Arrays.equals(a,b))throw new IllegalStateException(label+" differs");}
    private static void requireSame(int[] a,int[] b,String label){if(!Arrays.equals(a,b))throw new IllegalStateException(label+" differs");}
    private interface Condition{boolean ready()throws Exception;}
    private static void waitFor(Condition condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!condition.ready()&&System.nanoTime()<end)Thread.sleep(2);if(!condition.ready())throw new IllegalStateException("UI callback timed out");}
}

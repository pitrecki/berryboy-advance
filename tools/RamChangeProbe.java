package berryboy.desktop;

import berryboy.Machine;
import berryboy.MachineState;
import berryboy.cartridge.CartridgeBackup;
import berryboy.desktop.capture.JsonReport;
import berryboy.desktop.scenario.RecordedInputs;
import berryboy.video.LcdTiming;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Properties;

/** Read-only RAM changes between public CPU steps; schedules no observation events. */
public final class RamChangeProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: RamChangeProbe scenario freshOutput");
        var path = Path.of(args[0]).toAbsolutePath(); var output = Path.of(args[1]);
        var config = new Properties(); try (var reader = Files.newBufferedReader(path)) { config.load(reader); }
        var frames = Integer.parseInt(required(config,"frames"));
        var address = Integer.decode(required(config,"trace_ram_address"));
        var capacity = Integer.parseInt(config.getProperty("trace_ram_capacity","16384"));
        if (frames < 1 || frames > 7000 || capacity < 1 || capacity > 65536 || (address & 3) != 0
                || !((address >= 0x02000000 && address <= 0x0203fffc) || (address >= 0x03000000 && address <= 0x03007ffc))) {
            throw new IllegalArgumentException("Invalid RAM trace bounds: aligned EWRAM/IWRAM word, frames 1..7000, capacity 1..65536");
        }
        if (Files.exists(output)) throw new IllegalArgumentException("Use a fresh output folder");
        if (!config.getProperty("devices","0").equals("0") || !config.getProperty("warmup_frames","0").equals("0")
                || !config.getProperty("rtc","2000-01-01T00:00:00").equals("2000-01-01T00:00:00")) {
            throw new IllegalArgumentException("Requires default RTC, no peripherals and zero warmup");
        }
        var commit = required(config,"commit"); var dirty = required(config,"source_dirty");
        var rom = asset(path,config,"rom"); var bios = asset(path,config,"bios"); var input = asset(path,config,"input");
        var recording = RecordedInputs.parse(input);
        var machine = new Machine(rom,bios);
        if (config.containsKey("backup")) machine.memory.setBackup(new CartridgeBackup(Integer.parseInt(config.getProperty("backup"))));
        Path save = null; String seedHash = null;
        if (config.containsKey("save") || config.containsKey("initial_save_sha256")) {
            save = path.getParent().resolve(required(config,"save")); seedHash = required(config,"initial_save_sha256");
            SaveFile.load(save,machine.memory.backup(),seedHash);
        }
        recording.install(machine); machine.memory.clockedAudio().setCapture(true);
        Files.createDirectories(output);
        var rows = new ArrayList<String>();
        rows.add("cycle_before,cycle_after,pc_before,pc_after,lr_before,lr_after,old,value,cpsr_after,instruction_in_flight");
        var video = MessageDigest.getInstance("SHA-256"); var audio = MessageDigest.getInstance("SHA-256");
        var rgb = new byte[240 * 160 * 3]; var pcmBytes = new byte[4096]; var pcm = new short[2048];
        var previous = word(machine,address); long audioFrames = 0;
        for (var frame = 1; frame <= frames; frame++) {
            while (machine.clock.now() < frame * (long) LcdTiming.FRAME_CYCLES) {
                var before = machine.clock.now(); var pc = machine.cpu.pc(); var lr = machine.cpu.register(14);
                machine.cpu.step(); var value = word(machine,address);
                if (value == previous) continue;
                if (rows.size() > capacity) throw new IllegalStateException("RAM change trace capacity exceeded");
                rows.add(before + "," + machine.clock.now() + "," + unsigned(pc) + "," + unsigned(machine.cpu.pc())
                        + "," + unsigned(lr) + "," + unsigned(machine.cpu.register(14)) + "," + unsigned(previous) + ","
                        + unsigned(value) + "," + unsigned(machine.cpu.cpsr()) + "," + machine.cpu.instructionInFlight());
                previous = value;
            }
            if (machine.memory.raster().completedFrames() != frame) throw new IllegalStateException("Guest frame continuity failed");
            var count = machine.memory.clockedAudio().availableFrames();
            machine.memory.clockedAudio().drain(pcm,count); audioFrames += count;
            for (var i = 0; i < count * 2; i++) { pcmBytes[i * 2] = (byte) pcm[i]; pcmBytes[i * 2 + 1] = (byte) (pcm[i] >> 8); }
            audio.update(pcmBytes,0,count * 4);
            var pixels = machine.memory.raster().completedFrame();
            for (var i = 0; i < pixels.length; i++) {
                rgb[i * 3] = (byte) (pixels[i] >> 16); rgb[i * 3 + 1] = (byte) (pixels[i] >> 8); rgb[i * 3 + 2] = (byte) pixels[i];
            }
            video.update(rgb);
        }
        if (save != null && !hash(Files.readAllBytes(save)).equals(seedHash)) throw new IllegalStateException("Input save changed");
        if (machine.memory.clockedAudio().overruns() != 0 || machine.memory.clockedAudio().underruns() != 0) {
            throw new IllegalStateException("Audio buffer loss");
        }
        Files.write(output.resolve("ram.csv"),rows);
        var backup = machine.memory.backup().snapshot(); Files.write(output.resolve("final-backup.sav"),backup);
        var result = new LinkedHashMap<String,Object>();
        result.put("source_commit",commit); result.put("source_dirty",dirty); result.put("frames",frames);
        result.put("rom_sha256",hash(rom)); result.put("bios_sha256",hash(bios)); result.put("input_sha256",hash(input));
        result.put("initial_save_sha256",seedHash); result.put("backup_sha256",hash(backup)); result.put("original_save_unchanged",true);
        result.put("address",Integer.toUnsignedLong(address)); result.put("capacity",capacity); result.put("rows",rows.size() - 1);
        result.put("guest_cycles",machine.clock.now()); result.put("audio_frames",audioFrames);
        result.put("video_sha256",HexFormat.of().formatHex(video.digest())); result.put("audio_sha256",HexFormat.of().formatHex(audio.digest()));
        result.put("final_state_sha256",recording.lastCycle() <= machine.clock.now() ? hash(MachineState.save(machine)) : null);
        result.put("scope","RAM changes observed between public CPU steps, not exact bus timestamps or retirement events; unchanged stores are absent. "
                + "No observation callbacks or guest writes. Input save is read only; full RGB/PCM hashes are retained.");
        Files.writeString(output.resolve("report.json"),JsonReport.encode(result)+"\n"); System.out.println(JsonReport.encode(result));
    }

    private static int word(Machine machine, int address) { return machine.memory.peek16(address) | (machine.memory.peek16(address + 2) << 16); }
    private static String unsigned(int value) { return Integer.toUnsignedString(value); }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static byte[] asset(Path path, Properties config, String key) throws Exception {
        var bytes = Files.readAllBytes(path.getParent().resolve(required(config,key)));
        if (!hash(bytes).equals(required(config,key + "_sha256"))) throw new IllegalArgumentException("Hash mismatch: " + key);
        return bytes;
    }
    private static String required(Properties config, String key) {
        var value = config.getProperty(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key); return value;
    }
}

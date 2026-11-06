import berryboy.MachineState;
import berryboy.video.LcdTiming;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

/** Verify a recorded save state's continuation on host OR isolated actual CLDC classes. */
public final class VerifyStateResume {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("Usage: VerifyStateResume scenario state.bbs snapshotFrame resumedFrames expectedFinalSha256");
        var config = Path.of(args[0]).toAbsolutePath(); var properties = new Properties();
        try (var reader = Files.newBufferedReader(config)) { properties.load(reader); }
        var rom = verified(config, properties, "rom"); var bios = verified(config, properties, "bios");
        var state = Files.readAllBytes(Path.of(args[1])); var machine = MachineState.load(rom, bios, state);
        if (!sha(MachineState.save(machine)).equals(sha(state))) throw new IllegalStateException("State changed immediately after loading");
        var first = Integer.parseInt(args[2]); var count = Integer.parseInt(args[3]);
        if (first < 0 || count < 1 || count > 3600) throw new IllegalArgumentException("Invalid replay interval");
        var pcm = new short[2048];
        for (var frame = first + 1; frame <= first + count; frame++) {
            machine.runCycles(frame * (long) LcdTiming.FRAME_CYCLES - machine.clock.now());
            machine.memory.clockedAudio().drain(pcm, machine.memory.clockedAudio().availableFrames());
        }
        var actual = sha(MachineState.save(machine));
        if (!actual.equals(args[4])) throw new IllegalStateException("Full future state mismatch: " + actual);
        System.out.println(machine.cartridge.title() + ": exact " + count + "-frame continuation; " + actual);
    }
    private static byte[] verified(Path config, Properties properties, String key) throws Exception {
        var bytes = Files.readAllBytes(config.getParent().resolve(properties.getProperty(key)));
        if (!sha(bytes).equals(properties.getProperty(key + "_sha256"))) throw new IllegalArgumentException("Asset hash mismatch: " + key);
        return bytes;
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

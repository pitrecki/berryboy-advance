package berryboy.desktop.reliability;

import berryboy.desktop.scenario.FrameInputs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

/** Explicit identity and bounded workload for a local-assets reliability experiment. */
record ReliabilityConfig(Path file, Properties values, byte[] rom, byte[] bios, byte[] state,
                         FrameInputs inputs, int frames, int interval) {
    static ReliabilityConfig read(Path file) throws Exception {
        file=file.toAbsolutePath();var p=new Properties();try(var reader=Files.newBufferedReader(file)){p.load(reader);}
        for(var key:new String[]{"rom","rom_sha256","bios","bios_sha256","state","state_sha256","frame_input","frame_input_sha256","frames","checkpoint_interval","oracle","hardware"})
            if(p.getProperty(key)==null||p.getProperty(key).isBlank())throw new IllegalArgumentException("Missing scene property: "+key);
        int frames=Integer.parseInt(p.getProperty("frames")),interval=Integer.parseInt(p.getProperty("checkpoint_interval"));
        if(frames<1||frames>108000||interval<1||frames%interval!=0||frames/interval<6||frames/interval>60)
            throw new IllegalArgumentException("Use 1..108000 frames and 6..60 evenly spaced checkpoints");
        return new ReliabilityConfig(file,p,verified(file,p,"rom"),verified(file,p,"bios"),verified(file,p,"state"),
                FrameInputs.parse(verified(file,p,"frame_input")),frames,interval);
    }
    Path path(String key) { return file.getParent().resolve(values.getProperty(key)).normalize(); }
    static String hash(byte[] data) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    private static byte[] verified(Path file,Properties p,String key) throws Exception {
        var data=Files.readAllBytes(file.getParent().resolve(p.getProperty(key)));
        if(!hash(data).equals(p.getProperty(key+"_sha256")))throw new IllegalArgumentException("Identity mismatch: "+key);
        return data;
    }
}

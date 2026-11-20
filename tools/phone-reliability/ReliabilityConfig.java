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
        if(p.containsKey("legacy_save"))verified(file,p,"legacy_save");
        if(p.containsKey("cold_frame_input")||p.containsKey("cold_frame_input_sha256")||p.containsKey("cold_frames")) {
            if(!p.containsKey("cold_frame_input")||!p.containsKey("cold_frames"))throw new IllegalArgumentException("Cold replay needs controls and frame count");
            int cold=Integer.parseInt(p.getProperty("cold_frames"));
            var controls=FrameInputs.parse(verified(file,p,"cold_frame_input"));
            if(cold<2||cold>7000||controls.firstFrame()>0||controls.lastFrame()>=cold)
                throw new IllegalArgumentException("Cold replay needs 2..7000 frames and controls from frame zero inside that interval");
            int[] mask={0};var cursor=controls.playbackTo(value->mask[0]=value);
            for(int frame=0;frame<cold;frame++) {
                cursor.afterFrame(frame);
                if((frame<2||frame==cold-1)&&mask[0]!=0)throw new IllegalArgumentException("Cold boot/resume/endpoint controls must release every button");
            }
        }
        return new ReliabilityConfig(file,p,verified(file,p,"rom"),verified(file,p,"bios"),verified(file,p,"state"),
                FrameInputs.parse(verified(file,p,"frame_input")),frames,interval);
    }
    Path path(String key) { return file.getParent().resolve(values.getProperty(key)).normalize(); }
    byte[] verified(String key) throws Exception { return verified(file,values,key); }
    static String hash(byte[] data) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    private static byte[] verified(Path file,Properties p,String key) throws Exception {
        var data=Files.readAllBytes(file.getParent().resolve(p.getProperty(key)));
        if(!hash(data).equals(p.getProperty(key+"_sha256")))throw new IllegalArgumentException("Identity mismatch: "+key);
        return data;
    }
}

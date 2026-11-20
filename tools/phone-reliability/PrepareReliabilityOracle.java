package berryboy.desktop.reliability;

import berryboy.MachineState;
import berryboy.desktop.capture.JsonReport;
import berryboy.video.LcdTiming;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import javax.imageio.ImageIO;

/** Independent single-owner replay; expected checkpoints come before UI save/load commands. */
public final class PrepareReliabilityOracle {
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Usage: PrepareReliabilityOracle scene.properties freshOutput");
        var c=ReliabilityConfig.read(Path.of(args[0]));var out=Path.of(args[1]);Files.createDirectory(out);
        var m=MachineState.load(c.rom(),c.bios(),c.state());long first=m.clock.now()/LcdTiming.FRAME_CYCLES;
        var keys=c.inputs().playback(m);var pcm=new short[2048];var digest=new FrameDigest();
        var checkpoints=new LinkedHashMap<String,Object>();
        for(int frame=1;frame<=c.frames();frame++) {
            keys.afterFrame((int)first+frame-1);m.runCycles((first+frame)*LcdTiming.FRAME_CYCLES-m.clock.now());
            int count=m.memory.clockedAudio().availableFrames();m.memory.clockedAudio().drain(pcm,count);
            digest.add(m.memory.raster().completedFrame(),pcm,count);
            if(frame%c.interval()==0) {
                if(m.memory.peek16(0x04000130)!=0x3ff)throw new IllegalStateException("Checkpoints require released keys");
                var state=MachineState.save(m);Files.write(out.resolve("frame-"+frame+".bbs"),state);
                var image=new BufferedImage(240,160,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,240,160,m.memory.raster().completedFrame(),0,240);
                ImageIO.write(image,"png",out.resolve("frame-"+frame+".png").toFile());
                checkpoints.put(Integer.toString(frame),ReliabilityConfig.hash(state));System.out.println("Oracle frame "+frame);
            }
        }
        if(m.memory.clockedAudio().overruns()!=0||m.memory.clockedAudio().underruns()!=0)throw new IllegalStateException("Oracle PCM loss");
        var result=digest.finish();var report=new LinkedHashMap<String,Object>();report.put("frames",result.frames());report.put("audio_frames",result.audioFrames());
        report.put("rgb_chain_sha256",result.rgbChainSha256());report.put("pcm_sha256",result.pcmSha256());report.put("checkpoints",checkpoints);
        report.put("start_frame",first);report.put("final_state_sha256",ReliabilityConfig.hash(MachineState.save(m)));
        report.put("rom_sha256",c.values().getProperty("rom_sha256"));report.put("bios_sha256",c.values().getProperty("bios_sha256"));
        report.put("state_sha256",c.values().getProperty("state_sha256"));report.put("frame_input_sha256",c.values().getProperty("frame_input_sha256"));
        Files.writeString(out.resolve("report.json"),JsonReport.encode(report)+"\n");
        var expected=new java.util.Properties();
        for(var key:new String[]{"frames","audio_frames","rgb_chain_sha256","pcm_sha256","start_frame","final_state_sha256","rom_sha256","bios_sha256","state_sha256","frame_input_sha256"})
            expected.setProperty(key,report.get(key).toString());
        try(var writer=Files.newBufferedWriter(out.resolve("expected.properties"))){expected.store(writer,"Independent replay identities and outputs");}
    }
}

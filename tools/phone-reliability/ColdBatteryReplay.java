package berryboy.desktop.reliability;

import berryboy.Machine;
import berryboy.MachineState;
import berryboy.cartridge.CartridgeBackup;
import berryboy.desktop.scenario.FrameInputs;
import berryboy.video.LcdTiming;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/** Cold BIOS/game startup from the UI's committed battery file, with no machine-state load. */
final class ColdBatteryReplay {
    private ColdBatteryReplay() { }
    static LinkedHashMap<String,Object> run(PhoneCommands phone,ReliabilityConfig c,Path output) throws Exception {
        byte[] battery=phone.backup();
        if(battery==null)throw new IllegalStateException("Cold replay requires a committed battery payload");
        Files.write(output.resolve("committed-battery.sav"),battery);
        var expected=new Machine(c.rom(),c.bios());
        if(expected.memory.backup().isEeprom() && (battery.length==512||battery.length==8192))
            expected.memory.setBackup(new CartridgeBackup(battery.length==512?CartridgeBackup.EEPROM_512:CartridgeBackup.EEPROM_8K));
        expected.memory.backup().restore(battery);expected.memory.clockedAudio().setCapture(true);
        var controls=FrameInputs.parse(c.verified("cold_frame_input"));var held=new AtomicInteger();var input=controls.playbackTo(held::set);
        input.afterFrame(0);if(held.get()!=0)throw new IllegalArgumentException("Cold boot must start with released buttons");
        var pcm=new short[2048];var digest=new FrameDigest();
        expected.runCycles(LcdTiming.FRAME_CYCLES);int count=expected.memory.clockedAudio().availableFrames();expected.memory.clockedAudio().drain(pcm,count);
        var bootstrap=phone.coldReopen();compare(bootstrap,expected,pcm,count,1);digest.add(bootstrap);
        input.afterFrame(1);if(held.get()!=0)throw new IllegalArgumentException("First resumed cold frame must have released buttons");
        phone.resume();int frames=Integer.parseInt(c.values().getProperty("cold_frames"));
        for(int frame=2;frame<=frames;frame++) {
            var actual=phone.await();expected.memory.setPressedKeys(held.get());
            expected.runCycles(frame*(long)LcdTiming.FRAME_CYCLES-expected.clock.now());count=expected.memory.clockedAudio().availableFrames();
            expected.memory.clockedAudio().drain(pcm,count);compare(actual,expected,pcm,count,frame);digest.add(actual);
            if(frame%300==0)System.out.println("Cold UI frame "+frame+" exact");
            if(frame<frames){input.afterFrame(frame);phone.keys(held.get());phone.proceed();}
        }
        if(held.get()!=0)throw new IllegalArgumentException("Cold endpoint requires released buttons");
        if(expected.memory.backup().dirty()||!Arrays.equals(expected.memory.backup().snapshot(),battery))
            throw new IllegalStateException("Cold read-only route changed the battery payload");
        ImageIO.write(phone.surface(),"png",output.resolve("cold-endpoint.png").toFile());
        phone.pause();var state=phone.save(4);
        if(!Arrays.equals(state,MachineState.save(expected)))throw new IllegalStateException("Cold UI complete state differs");
        if(!Arrays.equals(phone.backup(),battery))throw new IllegalStateException("Committed battery changed during cold replay");
        if(expected.memory.clockedAudio().overruns()!=0||expected.memory.clockedAudio().underruns()!=0)
            throw new IllegalStateException("Cold replay lost captured audio");
        Files.write(output.resolve("cold-state.bbs"),state);var result=digest.finish();
        var report=new LinkedHashMap<String,Object>();report.put("frames",result.frames());report.put("state_loads",0);report.put("state_writes",1);
        report.put("audio_frames",result.audioFrames());report.put("rgb_chain_sha256",result.rgbChainSha256());report.put("pcm_sha256",result.pcmSha256());
        report.put("committed_battery_sha256",ReliabilityConfig.hash(battery));report.put("cold_state_sha256",ReliabilityConfig.hash(state));
        report.put("controls_sha256",c.values().getProperty("cold_frame_input_sha256"));report.put("all_rgb_pcm_and_complete_state_exact",true);
        report.put("committed_battery_unchanged",true);report.put("audio_overruns",0);report.put("audio_underruns",0);
        report.put("scope","Fresh production-controller boot from its own committed sidecar; no slot load. Every boot/game RGB/PCM frame and final owned state matches an independent battery-only machine. Game-level checkpoint acceptance belongs to the scenario evidence.");
        return report;
    }
    private static void compare(FrameGate.Frame actual,Machine expected,short[] pcm,int count,int frame) {
        if(!Arrays.equals(actual.rgb(),expected.memory.raster().completedFrame())||!Arrays.equals(actual.pcm(),Arrays.copyOf(pcm,count*2)))
            throw new IllegalStateException("Cold boot RGB/PCM differs at frame "+frame);
    }
}

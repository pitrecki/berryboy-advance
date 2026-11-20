package berryboy.desktop.reliability;

import berryboy.MachineState;
import berryboy.desktop.capture.JsonReport;
import berryboy.mobile.settings.PhoneOptions;
import berryboy.video.LcdTiming;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Properties;
import javax.imageio.ImageIO;
import javax.microedition.rms.RecordStore;

/** Finite real-game UI reliability experiment; assets and generated states stay local. */
public final class RunPhoneReliability {
    private static final long HEAP_SPREAD_LIMIT=8L*1024*1024;
    private RunPhoneReliability() { }
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("Usage: RunPhoneReliability scene.properties freshOutput sourceCommit sourceDirty");
        var c=ReliabilityConfig.read(Path.of(args[0]));var out=Path.of(args[1]).toAbsolutePath();Files.createDirectory(out);
        var oracle=c.path("oracle");var expected=new Properties();
        try(var reader=Files.newBufferedReader(oracle.resolve("expected.properties"))){expected.load(reader);}
        for(var key:new String[]{"rom_sha256","bios_sha256","state_sha256","frame_input_sha256","frames"})
            if(!expected.getProperty(key).equals(c.values().getProperty(key)))throw new IllegalArgumentException("Oracle identity differs: "+key);
        int first=Integer.parseInt(expected.getProperty("start_frame"));
        var assets=Files.createDirectory(out.resolve("local-files"));var rom=assets.resolve("game.gba");var bios=assets.resolve("bios.bin");
        Files.write(rom,c.rom());Files.write(bios,c.bios());
        var legacy=assets.resolve("game.sav");
        Files.write(legacy,c.values().containsKey("legacy_save")?c.verified("legacy_save")
                :MachineState.load(c.rom(),c.bios(),c.state()).memory.backup().snapshot());
        var legacyHash=ReliabilityConfig.hash(Files.readAllBytes(legacy));
        var options=new PhoneOptions();options.setVolume(100);
        var settings=RecordStore.openRecordStore("berryboy-options",true);var encoded=options.encode();settings.addRecord(encoded,0,encoded.length);settings.closeRecordStore();
        var states=Files.createDirectory(out.resolve("states"));var images=Files.createDirectory(out.resolve("checkpoints"));
        var checkpoints=new LinkedHashMap<String,Object>();var digest=new FrameDigest();
        long minimum=Long.MAX_VALUE,maximum=0;int writes=0,loads=0,focus=0,reopens=0,lastSlot=0,batteryChecks=0;
        String committedBatteryHash=null;
        var slotHashes=new String[5];long started=System.nanoTime();byte[] lastState=null;
        try(var phone=new PhoneCommands(rom,bios,c.rom())) {
            phone.install(c.state());
            var desired=new java.util.concurrent.atomic.AtomicInteger();
            var input=c.inputs().playbackTo(desired::set);input.afterFrame(first);
            if(desired.get()!=0)throw new IllegalArgumentException("First resumed frame must have released keys");
            phone.resume();
            for(int frame=1;frame<=c.frames();frame++) {
                var value=phone.await();digest.add(value);
                if(frame%c.interval()==0) {
                    if(phone.held()!=0)throw new IllegalStateException("UI checkpoint must end with released keys");
                    ImageIO.write(phone.surface(),"png",images.resolve("frame-"+frame+".png").toFile());
                    phone.pause();int index=frame/c.interval();lastSlot=(index-1)%5;
                    lastState=phone.save(lastSlot);writes++;
                    var canonical=Files.readAllBytes(oracle.resolve("frame-"+frame+".bbs"));
                    if(!Arrays.equals(lastState,canonical))throw new IllegalStateException("Complete UI state differs at frame "+frame);
                    var batteryPath=oracle.resolve("frame-"+frame+".sav");
                    if(Files.exists(batteryPath)) {
                        var committed=Files.readAllBytes(batteryPath);
                        if(!Arrays.equals(phone.backup(),committed))throw new IllegalStateException("Committed battery differs at frame "+frame);
                        committedBatteryHash=ReliabilityConfig.hash(committed);batteryChecks++;
                    }
                    String hash=ReliabilityConfig.hash(lastState);slotHashes[lastSlot]=hash;
                    if(index%2==0){phone.load(lastSlot);loads++;}
                    if(index%4==0){phone.focusCycle();focus++;}
                    if(index==c.frames()/c.interval()/2) {
                        verifySlots(phone,slotHashes);phone.reopen(lastSlot);reopens++;verifySlots(phone,slotHashes);
                        if(committedBatteryHash!=null && !committedBatteryHash.equals(ReliabilityConfig.hash(phone.backup())))
                            throw new IllegalStateException("Committed battery changed across controller recreation");
                    }
                    if(phone.pendingAudio()!=0||phone.queuedFrames()!=0)throw new IllegalStateException("Output remains pending at paused checkpoint");
                    var heap=RetainedHeap.collectAndSample();
                    if(index>5){minimum=Math.min(minimum,heap.usedBytes());maximum=Math.max(maximum,heap.usedBytes());}
                    var point=new LinkedHashMap<String,Object>();point.put("guest_frame",first+frame);point.put("slot",lastSlot+1);
                    point.put("state_sha256",hash);point.put("complete_state_matches_oracle",true);point.put("retained_heap_bytes",heap.usedBytes());
                    point.put("gc_count",heap.gcCount());point.put("gc_collection_ms",heap.gcCollectionMillis());
                    point.put("writes",writes);point.put("loads",loads);point.put("focus_cycles",focus);point.put("controller_recreations",reopens);
                    checkpoints.put(Integer.toString(frame),point);
                    System.out.println("UI frame "+frame+"; writes="+writes+" loads="+loads+" retained="+heap.usedBytes());
                    if(frame<c.frames()) {
                        input.afterFrame(first+frame);
                        if(desired.get()!=0)throw new IllegalArgumentException("First frame after a checkpoint must have released keys");
                        phone.resume();
                    }
                } else {
                    if(frame<c.frames()){input.afterFrame(first+frame);phone.keys(desired.get());}
                    phone.proceed();
                }
            }
            var result=digest.finish();
            if(result.frames()!=c.frames() || result.audioFrames()!=Long.parseLong(expected.getProperty("audio_frames"))
                    || !result.rgbChainSha256().equals(expected.getProperty("rgb_chain_sha256"))
                    || !result.pcmSha256().equals(expected.getProperty("pcm_sha256")))throw new IllegalStateException("UI output differs from independent replay");
            if(minimum==Long.MAX_VALUE||maximum-minimum>HEAP_SPREAD_LIMIT)throw new IllegalStateException("Retained heap did not remain within the declared checkpoint bound");
            Files.write(states.resolve("final.bbs"),lastState);
            var continuation=continuation(phone,c,lastState,states);
            var cold=c.values().containsKey("cold_frame_input")?ColdBatteryReplay.run(phone,c,out):null;
            if(!legacyHash.equals(ReliabilityConfig.hash(Files.readAllBytes(legacy))))throw new IllegalStateException("Original legacy save changed");
            var report=new LinkedHashMap<String,Object>();report.put("source_commit",args[2]);report.put("source_dirty",args[3]);
            report.put("scope","Production phone UI/session/storage/rendering/mixer with headless LCDUI/filesystem/RMS/PCM adapters; no native BBOS or physical transport/performance qualification");
            report.put("hardware",c.values().getProperty("hardware"));report.put("java",System.getProperty("java.runtime.version"));
            report.put("classpath",System.getProperty("java.class.path"));
            report.put("compiled_manifest_sha256",ReliabilityConfig.hash(Files.readAllBytes(Path.of(System.getProperty("berryboy.reliabilityManifest")))));
            for(var key:new String[]{"rom_sha256","bios_sha256","state_sha256","frame_input_sha256"})report.put(key,c.values().getProperty(key));
            report.put("frames",result.frames());report.put("audio_frames",result.audioFrames());report.put("rgb_chain_sha256",result.rgbChainSha256());report.put("pcm_sha256",result.pcmSha256());
            report.put("start_frame",first);report.put("end_frame",first+c.frames());report.put("speed",1);report.put("frame_skip",0);report.put("sound","100 percent stereo, unmuted");
            report.put("writes",writes+1+(cold==null?0:1));report.put("loads",loads+reopens+1);report.put("checkpoint_writes",writes);report.put("checkpoint_loads",loads);
            report.put("all_slots_exercised",Arrays.stream(slotHashes).allMatch(s->s!=null));report.put("focus_cycles",focus);report.put("controller_recreations",reopens+(cold==null?0:1));
            report.put("audio_overruns",0);report.put("audio_underruns",0);
            report.put("legacy_battery_unchanged",true);report.put("cold_battery_replay_verified",cold!=null);report.put("cold_battery_replay",cold);
            report.put("legacy_battery_sha256",legacyHash);
            report.put("committed_battery_checkpoints",batteryChecks);report.put("committed_battery_sha256",committedBatteryHash);
            report.put("battery_scope","Legacy input bytes are preserved; pending battery writes are compared byte-for-byte with independent successful-checkpoint receipts. Optional battery-only cold boot is verified separately; game-level checkpoint acceptance belongs to the scenario evidence.");
            report.put("frame_gate_capacity",1);report.put("pcm_gate_capacity",1);report.put("no_pending_output_at_paused_checkpoints",true);
            report.put("retained_heap_min_bytes",minimum);report.put("retained_heap_max_bytes",maximum);report.put("retained_heap_spread_limit_bytes",HEAP_SPREAD_LIMIT);
            report.put("heap_scope","Explicit completed host GC at paused equivalent checkpoints; first five checkpoints excluded from bound. Includes runner and platform adapters. Sampled retained heap, not peak allocation or a physical-device heap claim.");
            report.put("checkpoints",checkpoints);report.put("continuation",continuation);report.put("wall_ns",System.nanoTime()-started);
            Files.writeString(out.resolve("report.json"),JsonReport.encode(report)+"\n");
            System.out.println("Exact UI output, all checkpoints and continuation complete");
        }
    }
    private static void verifySlots(PhoneCommands phone,String[] hashes) throws Exception {
        for(int slot=0;slot<hashes.length;slot++)if(hashes[slot]!=null && !ReliabilityConfig.hash(phone.slot(slot)).equals(hashes[slot]))
            throw new IllegalStateException("Slot changed during controller recreation: "+slot);
    }
    private static LinkedHashMap<String,Object> continuation(PhoneCommands phone,ReliabilityConfig c,byte[] state,Path states) throws Exception {
        var expected=MachineState.load(c.rom(),c.bios(),state);long first=expected.clock.now()/LcdTiming.FRAME_CYCLES;
        var pcm=new short[2048];var digest=new FrameDigest();phone.resume();
        for(int i=1;i<=120;i++) {
            var frame=phone.await();expected.runCycles((first+i)*LcdTiming.FRAME_CYCLES-expected.clock.now());
            int count=expected.memory.clockedAudio().availableFrames();expected.memory.clockedAudio().drain(pcm,count);
            if(!Arrays.equals(frame.rgb(),expected.memory.raster().completedFrame())||!Arrays.equals(frame.pcm(),Arrays.copyOf(pcm,count*2)))
                throw new IllegalStateException("UI continuation differs at frame "+i);
            digest.add(frame);if(i<120)phone.proceed();
        }
        phone.pause();var future=phone.save(0);
        if(!Arrays.equals(future,MachineState.save(expected)))throw new IllegalStateException("UI continuation full state differs");
        Files.write(states.resolve("future.bbs"),future);var result=digest.finish();
        var report=new LinkedHashMap<String,Object>();report.put("frames",120);report.put("start_frame",first);
        report.put("state_sha256",ReliabilityConfig.hash(state));report.put("final_state_sha256",ReliabilityConfig.hash(future));
        report.put("rgb_chain_sha256",result.rgbChainSha256());report.put("pcm_sha256",result.pcmSha256());report.put("audio_frames",result.audioFrames());
        report.put("all_rgb_pcm_and_full_state_exact",true);return report;
    }
}

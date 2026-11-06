package berryboy.desktop;

import berryboy.Machine;
import berryboy.cartridge.CartridgeBackup;
import berryboy.video.LcdTiming;
import berryboy.desktop.capture.JsonReport;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Capture fresh local-game frame checkpoints from deterministic input, without save-file writes. */
public final class CaptureGalleryScenes {
 public static void main(String[] args)throws Exception {
  if(args.length<4||args.length>5)throw new IllegalArgumentException("Usage: CaptureGalleryScenes scenario output extraInput sourceCommit [frames]");
  var frames=args.length==5?Integer.parseInt(args[4]):9000;
  if(frames<3600||frames>20000)throw new IllegalArgumentException("Invalid gallery frame budget");
  var cfg=Path.of(args[0]).toAbsolutePath();var out=Path.of(args[1]);
  if(Files.exists(out))throw new IllegalArgumentException("Fresh output required");Files.createDirectories(out);
  var p=new Properties();try(var r=Files.newBufferedReader(cfg)){p.load(r);}
  var rom=checked(cfg,p,"rom");var bios=checked(cfg,p,"bios");
  var input=checked(cfg,p,"input");var extra=Files.readAllBytes(Path.of(args[2]));
  var m=new Machine(rom,bios);
  if(p.containsKey("backup"))m.memory.setBackup(new CartridgeBackup(Integer.parseInt(p.getProperty("backup"))));
  var trace=new String(input,StandardCharsets.UTF_8)+"\n"+new String(extra,StandardCharsets.UTF_8);
  for(var line:trace.split("\\R")){
   if(line.isBlank()||line.startsWith("#"))continue;var v=line.strip().split("\\s+");
   long cycle=Long.parseLong(v[0]);int keys=Integer.decode(v[1]);int x=Integer.decode(v[2]),y=Integer.decode(v[3]),z=Integer.decode(v[4]),solar=Integer.decode(v[5]);
   m.clock.schedule(()->{m.memory.setPressedKeys(keys);m.memory.gpio().setSensors(x,y,z,solar);},cycle);
  }
  for(int frame=1;frame<=frames;frame++){
   m.runCycles(frame*(long)LcdTiming.FRAME_CYCLES-m.clock.now());
   if(frame>=3600&&frame%600==0){
    var image=new BufferedImage(240,160,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,240,160,m.memory.raster().completedFrame(),0,240);
    var file=out.resolve("frame-"+frame+".png");ImageIO.write(image,"png",file.toFile());
    var report=new LinkedHashMap<String,Object>();
    report.put("game",m.cartridge.gameCode());report.put("title",m.cartridge.title());
    report.put("rom_sha256",ScenarioRunner.hash(rom));report.put("bios_sha256",ScenarioRunner.hash(bios));report.put("input_sha256",ScenarioRunner.hash(input));report.put("extra_input_sha256",ScenarioRunner.hash(extra));
    report.put("initial_save_sha256",null);report.put("guest_frame",frame);report.put("guest_cycles",m.clock.now());report.put("rgb_png_sha256",ScenarioRunner.hash(Files.readAllBytes(file)));
    report.put("source_commit",args[3]);report.put("source_dirty",true);
    Files.writeString(out.resolve("frame-"+frame+".json"),JsonReport.encode(report)+"\n");
    System.out.println(m.cartridge.gameCode()+" "+frame);
   }
  }
 }
 private static byte[]checked(Path cfg,Properties p,String key)throws Exception{var b=Files.readAllBytes(cfg.getParent().resolve(p.getProperty(key)));if(!ScenarioRunner.hash(b).equals(p.getProperty(key+"_sha256")))throw new IllegalArgumentException("Hash mismatch "+key);return b;}
}

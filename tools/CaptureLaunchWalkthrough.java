package berryboy.desktop;

import berryboy.MachineState;
import berryboy.mobile.ui.PhoneMenus;
import berryboy.mobile.ui.PhoneScreen;
import berryboy.video.LcdTiming;
import java.nio.file.*;
import java.util.*;

/** Scripted UI walkthrough followed by actual guest execution. */
public final class CaptureLaunchWalkthrough {
 public static void main(String[] args)throws Exception {
  var out=Path.of(args[0]);Files.createDirectory(out);
  var p=new Properties();try(var r=Files.newBufferedReader(Path.of("target/final-long-compatibility-2026-10-22/AGSE.properties"))){p.load(r);}
  var rom=Files.readAllBytes(Path.of(p.getProperty("rom")));var bios=Files.readAllBytes(Path.of(p.getProperty("bios")));
  if(!ScenarioRunner.hash(rom).equals(p.getProperty("rom_sha256"))||!ScenarioRunner.hash(bios).equals(p.getProperty("bios_sha256")))throw new IllegalArgumentException("Asset mismatch");
  var state=Files.readAllBytes(Path.of("target/phone-states/golden-sun/state.bbs"));
  var machine=MachineState.load(rom,bios,state);
  var screen=new PhoneScreen();var painter=new CapturePhone();int image=0;
  PhoneMenus.home(screen,false);screen.setMessage("Select a game to play");
  for(int i=0;i<30;i++)painter.write(screen,out.resolve("%04d.png".formatted(image++)));
  screen.menu("Open game","Local games",new String[]{"Golden Sun","Metroid: Zero Mission","Wario Land 4"},new String[]{">",">",">"},new boolean[]{true,true,true});
  screen.setMessage("Golden Sun selected");
  for(int i=0;i<20;i++)painter.write(screen,out.resolve("%04d.png".formatted(image++)));
  PhoneMenus.game(screen,"Golden Sun");
  for(int frame=0;frame<600;frame++){
   machine.memory.setPressedKeys(frame%120<12?1:0);
   machine.runCycles(LcdTiming.FRAME_CYCLES);
   if(frame%6==0){screen.setFrame(machine.memory.raster().completedFrame().clone());painter.write(screen,out.resolve("%04d.png".formatted(image++)));}
  }
  painter.close();
  Files.writeString(out.resolve("capture.json"),"{\"frames\":"+image+",\"capture\":\"Scripted production-renderer walkthrough; chooser is illustrative, not a recording of native JSR-75 file browsing. Game segment is 600 actual guest frames resumed from verified Golden Sun state, sampled every six frames. Transition omits BIOS boot.\",\"rom_sha256\":\""+ScenarioRunner.hash(rom)+"\",\"bios_sha256\":\""+ScenarioRunner.hash(bios)+"\",\"state_sha256\":\""+ScenarioRunner.hash(state)+"\"}\n");
 }
}

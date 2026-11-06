package berryboy.desktop;

import berryboy.mobile.PhoneGraphics;
import berryboy.mobile.PhoneMenus;
import berryboy.mobile.PhoneOptions;
import berryboy.mobile.PhoneRenderer;
import berryboy.mobile.PhoneScreen;
import berryboy.MachineState;
import berryboy.desktop.capture.JsonReport;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Properties;
import javax.imageio.ImageIO;

/** Paint the production phone renderer at Bold geometry, with real restored game pixels. */
public final class CapturePhone implements PhoneGraphics {
    private final BufferedImage image = new BufferedImage(640,480,BufferedImage.TYPE_INT_RGB);
    private final Graphics2D graphics = image.createGraphics();
    private final Font regular = new Font("Dialog",Font.PLAIN,16);
    private final Font heading = new Font("Dialog",Font.BOLD,24);
    private CapturePhone() { graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON); }
    public void fill(int x,int y,int width,int height,int rgb) { graphics.setColor(new Color(rgb));graphics.fillRect(x,y,width,height); }
    public void text(String value,int x,int y,int rgb,boolean large) {
        graphics.setFont(large?heading:regular);graphics.setColor(new Color(rgb));
        graphics.drawString(value,x,y+graphics.getFontMetrics().getAscent());
    }
    public int textWidth(String value,boolean large) { return graphics.getFontMetrics(large?heading:regular).stringWidth(value); }
    public void pixels(int[] owned,int x,int y,int scale) {
        var frame = new BufferedImage(240,160,BufferedImage.TYPE_INT_RGB);frame.setRGB(0,0,240,160,owned,0,240);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(frame,x,y,240*scale,160*scale,null);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Usage: CapturePhone scenario state.bbs outputFolder");
        var config=Path.of(args[0]).toAbsolutePath();var output=Path.of(args[2]);
        if(Files.exists(output))throw new IllegalArgumentException("Use a fresh capture folder");
        Files.createDirectories(output);
        var p=new Properties();try(var reader=Files.newBufferedReader(config)){p.load(reader);}
        var rom=verified(config,p,"rom");var bios=verified(config,p,"bios");var state=Files.readAllBytes(Path.of(args[1]));
        var machine=MachineState.load(rom,bios,state);
        var screen=new PhoneScreen();var painter=new CapturePhone();var options=new PhoneOptions();
        var report=new LinkedHashMap<String,Object>();
        report.put("capture","Production PhoneMenus/PhoneRenderer painted headlessly through the PhoneGraphics port; fonts use Java2D, not the device SDK");
        report.put("size","640x480 Bold 9900 geometry");report.put("qualification","Component captures; no simulator/device execution or physical FPS/audio claim");
        report.put("rom_sha256",ScenarioRunner.hash(rom));report.put("bios_sha256",ScenarioRunner.hash(bios));report.put("state_sha256",ScenarioRunner.hash(state));
        report.put("guest_cycles",machine.clock.now());
        PhoneMenus.home(screen,false);screen.setMessage("Navigate: trackpad / I,K / Select: Enter / Back");
        capture(painter,screen,output,"home",report);
        var pixels=machine.memory.raster().completedFrame().clone();screen.setFrame(pixels);
        PhoneMenus.pause(screen,machine.cartridge.title());screen.setMessage("Navigate: trackpad / I,K / Select: Enter / Back");
        capture(painter,screen,output,"pause",report);
        options.setShowFps(true);PhoneMenus.settings(screen,options);screen.select(4);
        capture(painter,screen,output,"settings",report);
        PhoneMenus.controls(screen,options);screen.select(0);
        capture(painter,screen,output,"controls",report);
        PhoneMenus.slots(screen,false,new String[]{"Saved","Empty","Empty","Empty","Empty"},true);
        screen.setMessage("Saved slot 1");
        capture(painter,screen,output,"slots",report);
        PhoneMenus.game(screen,machine.cartridge.title());screen.setMessage("GBA -- fps / Draw -- fps / Back: pause");
        capture(painter,screen,output,"game",report);
        Files.writeString(output.resolve("capture.json"),JsonReport.encode(report)+"\n");painter.graphics.dispose();
    }
    private static void capture(CapturePhone painter,PhoneScreen screen,Path output,String name,LinkedHashMap<String,Object> report)throws Exception {
        PhoneRenderer.paint(painter,screen,640,480);var file=output.resolve(name+".png");ImageIO.write(painter.image,"png",file.toFile());
        report.put(name+"_sha256",ScenarioRunner.hash(Files.readAllBytes(file)));
    }
    private static byte[] verified(Path config,Properties p,String key)throws Exception {
        var bytes=Files.readAllBytes(config.getParent().resolve(p.getProperty(key)));
        if(!ScenarioRunner.hash(bytes).equals(p.getProperty(key+"_sha256")))throw new IllegalArgumentException("Hash mismatch: "+key);
        return bytes;
    }
}

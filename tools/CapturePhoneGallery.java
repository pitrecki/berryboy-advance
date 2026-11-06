package berryboy.desktop;

import berryboy.mobile.PhoneMenus;
import berryboy.mobile.PhoneOptions;
import berryboy.mobile.PhoneScreen;
import berryboy.mobile.GameViewport;
import berryboy.desktop.capture.JsonReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Apply the production phone UI to verified local emulation checkpoints. */
public final class CapturePhoneGallery {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: CapturePhoneGallery scenesFolder outputFolder");
        var source = Path.of(args[0]); var output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("Use a fresh output folder");
        Files.createDirectories(output);
        var painter = new CapturePhone(); var screen = new PhoneScreen();
        int[] menuFrame = null; String menuTitle = null;
        for (var line : Files.readAllLines(Path.of("benchmarks/phone-gallery.tsv"))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            var fields = line.split("\t", -1);
            if (fields.length != 4) throw new IllegalArgumentException("Invalid gallery row");
            var code = fields[0]; var frame = Integer.parseInt(fields[1]); var title = fields[2]; var slug = fields[3];
            var png = source.resolve(code).resolve("frame-" + frame + ".png");
            var details = Files.readString(source.resolve(code).resolve("frame-" + frame + ".json"));
            var rgbHash = stringField(details, "rgb_png_sha256");
            if (!ScenarioRunner.hash(Files.readAllBytes(png)).equals(rgbHash)) throw new IllegalArgumentException("Frame hash mismatch: " + code);
            var extra = Path.of("benchmarks/inputs/phone-gallery-" + code + ".tsv");
            if (!ScenarioRunner.hash(Files.readAllBytes(extra)).equals(stringField(details,"extra_input_sha256"))) throw new IllegalArgumentException("Input hash mismatch: " + code);
            var image = ImageIO.read(png.toFile());
            if (image == null || image.getWidth() != 240 || image.getHeight() != 160) throw new IllegalArgumentException("GBA frame required");
            var pixels = image.getRGB(0,0,240,160,null,0,240);
            PhoneMenus.game(screen, title); screen.setFrame(pixels); screen.setFps("");
            painter.write(screen, output.resolve(slug + ".png"));
            var view = GameViewport.create(640,480,screen.scaling());
            var metadata = new LinkedHashMap<String,Object>();
            metadata.put("game",code); metadata.put("title",title); metadata.put("guest_frame",frame);
            metadata.put("rom_sha256",stringField(details,"rom_sha256")); metadata.put("bios_sha256",stringField(details,"bios_sha256"));
            metadata.put("input_sha256",stringField(details,"input_sha256")); metadata.put("extra_input_sha256",stringField(details,"extra_input_sha256"));
            metadata.put("rgb_png_sha256",rgbHash); metadata.put("capture_png_sha256",ScenarioRunner.hash(Files.readAllBytes(output.resolve(slug+".png"))));
            metadata.put("viewport",view.x()+","+view.y()+","+view.width()+","+view.height());
            metadata.put("scaling","Fit; nearest neighbor; full canvas; no menu/header/footer");
            Files.writeString(output.resolve(slug+".json"),JsonReport.encode(metadata)+"\n");
            if (menuFrame == null) { menuFrame=pixels; menuTitle=stringField(details,"title"); }
        }
        screen = new PhoneScreen(); PhoneMenus.home(screen,false);
        screen.setMessage("Navigate: trackpad / I,K / Select: Enter / Back"); painter.write(screen,output.resolve("home.png"));
        screen.setFrame(menuFrame); PhoneMenus.pause(screen,menuTitle); painter.write(screen,output.resolve("pause.png"));
        var options=new PhoneOptions(); options.setShowFps(true); PhoneMenus.settings(screen,options); screen.select(5);
        painter.write(screen,output.resolve("settings.png"));
        PhoneMenus.controls(screen,options); painter.write(screen,output.resolve("controls.png"));
        PhoneMenus.slots(screen,false,new String[]{"Empty","Empty","Empty","Empty","Empty"},true);
        painter.write(screen,output.resolve("slots.png")); painter.close();
    }
    // Read only fixed SHA fields from the companion report written by our capture tool.
    private static String stringField(String json,String key) {
        var matcher=Pattern.compile("\""+Pattern.quote(key)+"\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if(!matcher.find())throw new IllegalArgumentException("Missing capture field: "+key);
        return matcher.group(1);
    }
}

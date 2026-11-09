package berryboy.desktop;

import berryboy.desktop.capture.JsonReport;
import berryboy.mobile.library.LibraryProfile;
import berryboy.phone.UiHarness;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.TextField;
import javax.microedition.rms.RecordStore;

/** One cold launch through production PhoneCanvas, PhoneSession and FilePicker. No state loading. */
public final class CaptureLaunchWalkthrough {
    private final Path output;
    private final LinkedHashMap<String,Object> timeline = new LinkedHashMap<>();
    private int images;
    private CaptureLaunchWalkthrough(Path output) { this.output = output; }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: CaptureLaunchWalkthrough AGSE.properties freshOutput sourceCommit sourceDirty");
        var config = Path.of(args[0]).toAbsolutePath(); var out = Path.of(args[1]);
        Files.createDirectory(out);
        var props = new Properties(); try (var reader = Files.newBufferedReader(config)) { props.load(reader); }
        var rom = verified(config, props, "rom"); var bios = verified(config, props, "bios");
        var assets = Files.createDirectory(out.resolve("local-files")).toAbsolutePath();
        var romCopy = assets.resolve("Golden Sun.gba"); var biosCopy = assets.resolve("gba_bios.bin");
        Files.write(romCopy, rom); Files.write(biosCopy, bios);
        var profile = new LibraryProfile(); profile.setDirectory(assets.toUri().toASCIIString());
        var record = RecordStore.openRecordStore("berryboy-library", true);
        var encoded = profile.encode(); record.addRecord(encoded, 0, encoded.length); record.closeRecordStore();
        var capture = new CaptureLaunchWalkthrough(out);
        var ready = new ArrayBlockingQueue<Integer>(1); var next = new Semaphore(0);
        int[] presented = {0};
        try (var ui = new UiHarness()) {
            // The worker blocks outside the Canvas monitor; all buffers remain owned snapshots.
            ui.canvas.presentationHook = () -> {
                try { ready.put(++presented[0]); next.acquire(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            };
            try {
                capture.hold(ui, "Home", 10);
                ui.select("Display & audio"); ui.select("BIOS file");
                ui.text(0, biosCopy.toUri().toASCIIString()); capture.hold(ui, "BIOS settings", 8);
                ui.command("Browse BIOS"); waitTitle(ui, "Choose GBA BIOS"); capture.hold(ui, "BIOS file picker", 8);
                ui.choose("gba_bios.bin"); capture.hold(ui, "Selected BIOS", 6);
                ui.command("Load BIOS"); waitMessage(ui, "BIOS saved"); capture.hold(ui, "Validated BIOS preference", 8);
                ui.canvas.press('#'); ui.canvas.release('#');
                ui.select("Open game"); capture.hold(ui, "Open game form", 8);
                ui.command("Browse ROM"); waitTitle(ui, "Choose GBA ROM"); capture.hold(ui, "ROM file picker", 10);
                ui.choose("Golden Sun.gba"); capture.hold(ui, "Selected ROM and BIOS", 8);
                ui.command("Start game");
                capture.mark("Cold BIOS boot at 1x");
                for (int i = 1; i <= 300; i++) {
                    awaitFrame(ready, ui);
                    if (i % 6 == 0) capture.frame(ui);
                    if (i < 300) next.release();
                }
                // Continue the same machine. Show the app's actual speed control before accelerating.
                ui.canvas.press('#'); ui.canvas.release('#'); capture.hold(ui, "Pause after BIOS boot", 6);
                ui.select("Speed"); capture.hold(ui, "Speed 2x", 4);
                ui.select("Speed"); capture.hold(ui, "Speed 4x", 6);
                ui.select("Resume game");
                capture.mark("Golden Sun continues at 4x / sound muted");
                next.release();
                // 960 presentations at 4x execute 3837 more guest frames, in strict forward order.
                // Alternating Start/A taps reproduce the opening input, then advance dialogue with A.
                int held = 0;
                var input = new LinkedHashMap<String,Object>();
                for (int i = 1; i <= 960; i++) {
                    awaitFrame(ready, ui);
                    int guestFrame = 301 + (i - 1) * 4;
                    int desired = 0;
                    if (guestFrame >= 360 && guestFrame < 1800 && (guestFrame - 360) % 60 < 4)
                        desired = ((guestFrame - 360) / 60) % 2 == 0 ? '\n' : 'z';
                    if (guestFrame >= 1800 && (guestFrame - 1800) % 60 < 4) desired = 'z';
                    if (held != desired) {
                        if (held != 0) ui.canvas.release(held);
                        if (desired != 0) ui.canvas.press(desired);
                        held = desired; input.put(Integer.toString(guestFrame), desired == 0 ? "release" : desired == '\n' ? "Start" : "A");
                    }
                    if (i % 6 == 0) capture.frame(ui);
                    if (i < 960) next.release();
                }
                ui.canvas.press('#'); ui.canvas.release('#'); // stop the guest before releasing backpressure
                ui.canvas.presentationHook = null; next.release();
                var report = new LinkedHashMap<String,Object>();
                report.put("frames", capture.images);
                report.put("capture", "Continuous cold launch through production PhoneCanvas, PhoneSession, MidpGraphics and FilePicker. Java2D LCDUI/filesystem/RMS/audio doubles; not a device/simulator recording. Native Form/List widgets use a host rendering adapter.");
                report.put("continuity", "One fresh machine; original BIOS animation; no MachineState restore or scene jumps; every guest frame executes. 1x boot, then the visible pause-menu control selects 4x. Six presented frames per 100ms GIF image; static UI holds are scripted user dwell times.");
                report.put("source_commit", args[2]); report.put("source_dirty", Boolean.parseBoolean(args[3]));
                report.put("rom_sha256", ScenarioRunner.hash(rom)); report.put("bios_sha256", ScenarioRunner.hash(bios));
                report.put("initial_save", "Fresh backup; no adjacent save or snapshot supplied. IO restricted to owned copies.");
                report.put("guest_frames", 4137); report.put("presentation_frames", presented[0]);
                report.put("timeline", capture.timeline); report.put("keys_after_presented_guest_frame", input);
                var sources = new LinkedHashMap<String,Object>();
                for (var source : new String[]{"tools/CaptureLaunchWalkthrough.java", "tools/capture-launch-walkthrough.sh", "src/test/java/berryboy/build/PhoneHarness.java", "platform/midp/berryboy/phone/PhoneCanvas.java", "platform/midp/berryboy/phone/PhoneSession.java", "platform/midp/berryboy/phone/settings/BiosSettings.java"}) sources.put(source, ScenarioRunner.hash(Files.readAllBytes(Path.of(source))));
                report.put("source_sha256", sources);
                if (!java.util.Arrays.equals(rom, verified(config, props, "rom")) || !java.util.Arrays.equals(bios, verified(config, props, "bios"))) throw new IllegalStateException("Original assets changed");
                Files.writeString(out.resolve("capture.json"), JsonReport.encode(report) + "\n");
                System.out.println("Captured " + capture.images + " images from a continuous 4137-frame cold launch");
            } finally { ui.canvas.presentationHook = null; next.release(); }
        }
    }
    private void mark(String label) { timeline.put(Integer.toString(images * 100), label); }
    private void hold(UiHarness ui, String label, int frames) throws Exception { mark(label); for (int i = 0; i < frames; i++) frame(ui); }
    private void frame(UiHarness ui) throws Exception { ImageIO.write(render(ui), "png", output.resolve("%04d.png".formatted(images++)).toFile()); }
    private static void awaitFrame(ArrayBlockingQueue<Integer> queue, UiHarness ui) throws Exception {
        if (queue.poll(30, TimeUnit.SECONDS) == null) throw new IllegalStateException("No guest presentation: " + ui.screen().message());
    }
    private static byte[] verified(Path config, Properties props, String key) throws Exception {
        var path = Path.of(props.getProperty(key)); if (!path.isAbsolute()) path = config.getParent().resolve(path);
        var bytes = Files.readAllBytes(path);
        if (!ScenarioRunner.hash(bytes).equals(props.getProperty(key + "_sha256"))) throw new IllegalArgumentException(key + " hash mismatch");
        return bytes;
    }
    private static void waitTitle(UiHarness ui, String title) throws Exception { waitFor(() -> ui.display.getCurrent().getTitle().equals(title)); }
    private static void waitMessage(UiHarness ui, String message) throws Exception { waitFor(() -> { synchronized (ui.canvas) { return ui.screen().message().contains(message); } }); }
    private interface Condition { boolean ready() throws Exception; }
    private static void waitFor(Condition condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.ready() && System.nanoTime() < end) Thread.sleep(5);
        if (!condition.ready()) throw new IllegalStateException("UI callback timed out");
    }
    /** Paint only the actual LCDUI model. This adapter does not create menu items or game pixels. */
    private static BufferedImage render(UiHarness ui) {
        var current = ui.display.getCurrent(); if (current instanceof Canvas canvas) return canvas.render();
        var image = new BufferedImage(640,480,BufferedImage.TYPE_INT_RGB); var g = image.createGraphics();
        try {
            g.setColor(new Color(0x10141e)); g.fillRect(0,0,640,480);
            g.setColor(new Color(0xf15b79)); g.fillRect(0,0,640,4);
            g.setColor(Color.WHITE); g.setFont(new Font("Dialog",Font.BOLD,24)); g.drawString(current.getTitle(),20,42);
            g.setFont(new Font("Dialog",Font.PLAIN,16)); int y=85;
            if (current instanceof List list) {
                for(int i=0;i<list.size() && y<400;i++,y+=40){
                    if(i==list.getSelectedIndex()){g.setColor(new Color(0x353045));g.fillRect(16,y-24,608,36);}
                    g.setColor(Color.WHITE);g.drawString(list.getString(i),28,y);
                }
            } else if (current instanceof Form form) {
                for(int i=0;i<form.size() && y<405;i++) {
                    var item=form.get(i);
                    if(item instanceof TextField field){g.setColor(new Color(0xf15b79));g.drawString(field.getLabel(),20,y);y+=26;g.setColor(Color.WHITE);y=wrapped(g,field.getString(),y,2)+16;}
                    else {g.setColor(new Color(0x9cabc0));y=wrapped(g,item.toString(),y,6)+12;}
                }
            }
            g.setColor(new Color(0x1a2230));g.fillRect(0,435,640,45);g.setColor(Color.WHITE);
            g.drawString(String.join("  /  ",current.commands().stream().map(c->c.getLabel()).toList()),20,462);
        } finally {g.dispose();}
        return image;
    }
    private static int wrapped(java.awt.Graphics2D g,String text,int y,int maximum) {
        for(int line=0;!text.isEmpty()&&line<maximum;line++) {
            int end=Math.min(text.length(),72);while(end>1 && g.getFontMetrics().stringWidth(text.substring(0,end))>600)end--;
            if(end<text.length()){int space=text.lastIndexOf(' ',end);if(space>0)end=space;}
            g.drawString(text.substring(0,end),20,y);text=text.substring(end).stripLeading();y+=23;
        }
        return y;
    }
}

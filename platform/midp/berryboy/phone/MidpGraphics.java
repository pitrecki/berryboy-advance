package berryboy.phone;

import berryboy.mobile.ui.PhoneGraphics;
import berryboy.mobile.video.FrameScaler;
import java.io.IOException;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/** SDK drawing adapter; scaled buffers belong to the canvas, never to the guest. */
final class MidpGraphics implements PhoneGraphics {
    private final Font regular = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_MEDIUM);
    private final Font heading = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE);
    private Graphics graphics;
    private final FrameScaler scaler = new FrameScaler();
    private final Image logo;
    MidpGraphics() {
        try { logo = Image.createImage("/berryboy-mark.png"); }
        catch (IOException missing) { throw new IllegalStateException("Missing application logo"); }
    }
    void attach(Graphics graphics) { this.graphics = graphics; }
    public void fill(int x, int y, int width, int height, int rgb) {
        graphics.setColor(rgb); graphics.fillRect(x, y, width, height);
    }
    public void text(String text, int x, int y, int rgb, boolean large) {
        graphics.setFont(large ? heading : regular); graphics.setColor(rgb);
        graphics.drawString(text, x, y, Graphics.TOP | Graphics.LEFT);
    }
    public int textWidth(String text, boolean large) { return (large ? heading : regular).stringWidth(text); }
    public void logo(int x, int y) { graphics.drawImage(logo, x, y, Graphics.TOP | Graphics.LEFT); }
    public void pixels(int[] rgb, int x, int y, int width, int height) {
        graphics.drawRGB(scaler.scale(rgb, width, height), 0, width, x, y, width, height, false);
    }
}

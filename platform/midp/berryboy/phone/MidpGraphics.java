package berryboy.phone;

import berryboy.mobile.PhoneGraphics;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/** SDK drawing adapter; scaled buffers belong to the canvas, never to the guest. */
final class MidpGraphics implements PhoneGraphics {
    private final Font regular = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_MEDIUM);
    private final Font heading = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE);
    private Graphics graphics;
    private int[] scaled;
    void attach(Graphics graphics) { this.graphics = graphics; }
    public void fill(int x, int y, int width, int height, int rgb) {
        graphics.setColor(rgb); graphics.fillRect(x, y, width, height);
    }
    public void text(String text, int x, int y, int rgb, boolean large) {
        graphics.setFont(large ? heading : regular); graphics.setColor(rgb);
        graphics.drawString(text, x, y, Graphics.TOP | Graphics.LEFT);
    }
    public int textWidth(String text, boolean large) { return (large ? heading : regular).stringWidth(text); }
    public void pixels(int[] rgb, int x, int y, int scale) {
        if (scale == 1) { graphics.drawRGB(rgb, 0, 240, x, y, 240, 160, false); return; }
        int width = 240 * scale; int height = 160 * scale;
        if (scaled == null || scaled.length != width * height) scaled = new int[width * height];
        for (int row = 0; row < height; row++) for (int col = 0; col < width; col++) {
            scaled[row * width + col] = rgb[(row / scale) * 240 + col / scale];
        }
        graphics.drawRGB(scaled, 0, width, x, y, width, height, false);
    }
}

package berryboy.mobile;

/** Production phone UI, independent of SDK drawing and emulator internals. */
public final class PhoneRenderer {
    private static final int BACKGROUND = 0x10141e;
    private static final int PANEL = 0x1a2230;
    private static final int ACCENT = 0xf15b79;
    private static final int TEXT = 0xf3f5fa;
    private static final int MUTED = 0x9cabc0;
    public static final int HEADER = 96;
    public static final int ROW = 44;
    public static final int FOOTER = 40;
    private PhoneRenderer() { }
    public static void paint(PhoneGraphics g, PhoneScreen screen, int width, int height) {
        g.fill(0, 0, width, height, BACKGROUND);
        g.fill(0, 0, width, 4, ACCENT);
        g.fill(16, 17, 34, 34, ACCENT);
        g.text("B", 25, 19, BACKGROUND, true);
        g.text("BerryBoy Advance", 62, 17, TEXT, true);
        g.text(fit(g, screen.title() + " / " + screen.subtitle(), width - 32, false), 16, 62, MUTED, false);
        if (screen.count() == 0) {
            var pixels = screen.frame();
            if (pixels != null) {
                var scale = width / 240;
                var vertical = (height - HEADER - FOOTER) / 160;
                if (vertical < scale) scale = vertical;
                if (!screen.integerScale() || scale < 1) scale = 1;
                g.pixels(pixels, (width - 240 * scale) / 2,
                        HEADER + (height - HEADER - FOOTER - 160 * scale) / 2, scale);
            }
        } else {
            var preview = width >= 600 && screen.frame() != null;
            var menuWidth = preview ? width - 280 : width - 32;
            var visible = (height - HEADER - FOOTER) / ROW;
            if (visible < 1) visible = 1;
            var first = screen.selection() >= visible ? screen.selection() - visible + 1 : 0;
            for (var i = first; i < screen.count() && i < first + visible; i++) {
                var y = HEADER + (i - first) * ROW;
                var selected = i == screen.selection();
                var color = screen.enabled(i) ? TEXT : MUTED;
                g.fill(16, y, menuWidth, ROW - 4, selected ? 0x353045 : PANEL);
                if (selected) g.fill(16, y, 4, ROW - 4, ACCENT);
                var value = screen.value(i);
                var valueWidth = g.textWidth(value, false);
                g.text(fit(g, screen.label(i), menuWidth - valueWidth - 38, false), 30, y + 10, color, false);
                g.text(value, 16 + menuWidth - valueWidth - 12, y + 10, selected ? ACCENT : MUTED, false);
            }
            if (screen.count() > visible) {
                var track = visible * ROW - 4;
                var thumb = track * visible / screen.count();
                g.fill(16 + menuWidth - 3, HEADER, 3, track, PANEL);
                g.fill(16 + menuWidth - 3, HEADER + (track - thumb) * first / (screen.count() - visible), 3, thumb, ACCENT);
            }
            if (preview) {
                g.text("CURRENT GAME", width - 256, HEADER + 8, MUTED, false);
                g.pixels(screen.frame(), width - 256, HEADER + 40, 1);
                g.text("Paused", width - 256, HEADER + 210, ACCENT, false);
            }
        }
        g.fill(0, height - FOOTER, width, FOOTER, PANEL);
        g.text(fit(g, screen.message(), width - 32, false), 16, height - FOOTER + 10, MUTED, false);
    }
    private static String fit(PhoneGraphics g, String text, int width, boolean heading) {
        if (g.textWidth(text, heading) <= width) return text;
        var out = new StringBuffer();
        for (var i = 0; i < text.length(); i++) {
            if (g.textWidth(out.toString() + text.charAt(i) + "...", heading) > width) break;
            out.append(text.charAt(i));
        }
        return out.toString() + "...";
    }
}

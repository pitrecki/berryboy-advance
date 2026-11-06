package berryboy.mobile.video;

import berryboy.mobile.settings.PhoneOptions;

/** Full-canvas placement of the 240x160 GBA image. Menus do not reserve game space. */
public final class GameViewport {
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private GameViewport(int screenWidth, int screenHeight, int width, int height) {
        x = (screenWidth - width) / 2; y = (screenHeight - height) / 2;
        this.width = width; this.height = height;
    }
    public static GameViewport create(int screenWidth, int screenHeight, int mode) {
        if (screenWidth < 1 || screenHeight < 1 || screenWidth > 4096 || screenHeight > 4096
                || mode < PhoneOptions.SCALE_NATIVE || mode > PhoneOptions.SCALE_STRETCH) {
            throw new IllegalArgumentException("Invalid game viewport");
        }
        var width = screenWidth;
        var height = screenWidth * 160 / 240;
        if (height > screenHeight) { height = screenHeight; width = screenHeight * 240 / 160; }
        if (width < 1) width = 1;
        if (height < 1) height = 1;
        if (mode == PhoneOptions.SCALE_STRETCH) { width = screenWidth; height = screenHeight; }
        else if (mode == PhoneOptions.SCALE_NATIVE && screenWidth >= 240 && screenHeight >= 160) { width = 240; height = 160; }
        else if (mode == PhoneOptions.SCALE_INTEGER) {
            var scale = screenWidth / 240;
            if (screenHeight / 160 < scale) scale = screenHeight / 160;
            if (scale > 0) { width = 240 * scale; height = 160 * scale; }
        }
        return new GameViewport(screenWidth, screenHeight, width, height);
    }
    public int x() { return x; }
    public int y() { return y; }
    public int width() { return width; }
    public int height() { return height; }
}

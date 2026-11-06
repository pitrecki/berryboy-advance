package berryboy.mobile;

/** Phone drawing boundary; MIDP and documentation capture use the same renderer. */
public interface PhoneGraphics {
    void fill(int x, int y, int width, int height, int rgb);
    void text(String text, int x, int y, int rgb, boolean heading);
    int textWidth(String text, boolean heading);
    void logo(int x, int y);
    void pixels(int[] ownedRgb, int x, int y, int width, int height);
}

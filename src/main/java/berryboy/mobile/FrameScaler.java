package berryboy.mobile;

/** UI-owned nearest-neighbor output with cached coordinate maps for fractional scaling. */
public final class FrameScaler {
    private int[] scaled;
    private int[] columns;
    private int[] rows;
    public int[] scale(int[] rgb, int width, int height) {
        if (rgb == null || rgb.length != 240 * 160 || width < 1 || height < 1 || width > 4096 || height > 4096) {
            throw new IllegalArgumentException("Invalid game image dimensions");
        }
        if (width == 240 && height == 160) return rgb;
        if (columns == null || columns.length != width || rows.length != height) {
            columns = new int[width]; rows = new int[height]; scaled = new int[width * height];
            for (var x = 0; x < width; x++) columns[x] = x * 240 / width;
            for (var y = 0; y < height; y++) rows[y] = (y * 160 / height) * 240;
        }
        for (var y = 0; y < height; y++) {
            var destination = y * width;
            for (var x = 0; x < width; x++) scaled[destination + x] = rgb[rows[y] + columns[x]];
        }
        return scaled;
    }
}

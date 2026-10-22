package berryboy;

/** Reference software PPU. Composes one scanline from the current register state. */
public final class SoftwarePpu {
    private static final int TRANSPARENT = -1;
    private static final int SEMITRANSPARENT = 0x8000;
    private static final int[] OBJECT_WIDTHS = {8, 16, 32, 64, 16, 32, 32, 64, 8, 8, 16, 32};
    private static final int[] OBJECT_HEIGHTS = {8, 16, 32, 64, 8, 8, 16, 32, 16, 32, 32, 64};
    private final Memory memory;
    private final int[] objects = new int[240];
    private final int[] objectPriority = new int[240];
    private final boolean[] objectWindow = new boolean[240];

    public SoftwarePpu(Memory memory) { this.memory = memory; }

    public void renderLine(int y, int[] output, int offset) {
        if (y < 0 || y >= 160 || output == null || offset < 0 || offset > output.length - 240) {
            throw new IllegalArgumentException("Invalid scanline destination");
        }
        var display = io(0);
        var mode = display & 7;
        if (mode > 5) throw new EmulationException("Reserved display mode");
        renderObjects(y, display);
        var backdrop = palette(0);
        for (var x = 0; x < 240; x++) {
            if ((display & 0x80) != 0) { output[offset + x] = 0xffffff; continue; }
            var mask = windowMask(x, y, display);
            var first = backdrop;
            var second = backdrop;
            var firstLayer = 5;
            var secondLayer = 5;
            var firstOrder = 100;
            var secondOrder = 101;
            for (var bg = 0; bg < 4; bg++) {
                if ((display & (0x100 << bg)) == 0 || (mask & (1 << bg)) == 0) continue;
                var color = background(bg, x, y, mode, display);
                if (color == TRANSPARENT) continue;
                var order = (io(8 + bg * 2) & 3) * 8 + bg + 1;
                if (order < firstOrder) {
                    second = first; secondLayer = firstLayer; secondOrder = firstOrder;
                    first = color; firstLayer = bg; firstOrder = order;
                } else if (order < secondOrder) {
                    second = color; secondLayer = bg; secondOrder = order;
                }
            }
            if ((mask & 0x10) != 0 && objects[x] != TRANSPARENT) {
                var order = objectPriority[x] * 8; // OBJ wins a tie with any BG.
                if (order < firstOrder) {
                    second = first; secondLayer = firstLayer;
                    first = objects[x]; firstLayer = 4;
                } else if (order < secondOrder) { second = objects[x]; secondLayer = 4; }
            }
            var color = first & 0x7fff;
            if ((mask & 0x20) != 0) {
                var blend = io(0x50);
                var effect = (blend >>> 6) & 3;
                var target1 = (blend & (1 << firstLayer)) != 0;
                var target2 = (blend & (0x100 << secondLayer)) != 0;
                if (target2 && ((first & SEMITRANSPARENT) != 0 || (effect == 1 && target1))) {
                    var alpha = io(0x52);
                    color = blend(color, second, clamp(alpha & 31), clamp((alpha >>> 8) & 31));
                } else if (target1 && effect >= 2) {
                    var amount = clamp(io(0x54) & 31);
                    color = brightness(color, amount, effect == 2);
                }
            }
            output[offset + x] = rgb888(color);
        }
    }

    private int background(int bg, int x, int y, int mode, int display) {
        var control = io(8 + bg * 2);
        if ((control & 0x40) != 0) {
            var mosaic = io(0x4c);
            x -= x % ((mosaic & 15) + 1);
            y -= y % (((mosaic >>> 4) & 15) + 1);
        }
        if (mode == 0 || (mode == 1 && bg < 2)) return textPixel(bg, x, y, control);
        if ((mode == 1 && bg == 2) || (mode == 2 && bg >= 2) || (mode >= 3 && bg == 2)) {
            var base = 0x20 + (bg - 2) * 16;
            var sx = ((read32(0x04000000 + base + 8) << 4 >> 4)
                    + (short) io(base) * x + (short) io(base + 2) * y) >> 8;
            var sy = ((read32(0x04000000 + base + 12) << 4 >> 4)
                    + (short) io(base + 4) * x + (short) io(base + 6) * y) >> 8;
            if (mode <= 2) return affinePixel(sx, sy, control);
            var width = mode == 5 ? 160 : 240;
            var height = mode == 5 ? 128 : 160;
            if ((control & 0x2000) != 0) {
                sx = wrap(sx, width); sy = wrap(sy, height);
            } else if (sx < 0 || sy < 0 || sx >= width || sy >= height) return TRANSPARENT;
            var page = mode != 3 && (display & 0x10) != 0 ? 0xa000 : 0;
            var address = 0x06000000 + page;
            if (mode == 4) {
                var index = read8(address + sy * width + sx);
                return index == 0 ? TRANSPARENT : palette(index);
            }
            return read16(address + 2 * (sy * width + sx)) & 0x7fff;
        }
        return TRANSPARENT;
    }

    private int textPixel(int bg, int x, int y, int control) {
        var size = control >>> 14;
        var width = (size & 1) != 0 ? 512 : 256;
        x = (x + io(0x10 + bg * 4)) & (width - 1);
        y = (y + io(0x12 + bg * 4)) & ((size & 2) != 0 ? 511 : 255);
        var block = x / 256 + (y / 256) * (width / 256);
        var map = 0x06000000 + ((control >>> 8) & 31) * 0x800 + block * 0x800;
        var entry = read16(map + (((y & 255) / 8) * 32 + (x & 255) / 8) * 2);
        var tx = (entry & 0x400) != 0 ? 7 - (x & 7) : x & 7;
        var ty = (entry & 0x800) != 0 ? 7 - (y & 7) : y & 7;
        var eightBit = (control & 0x80) != 0;
        var tile = 0x06000000 + ((control >>> 2) & 3) * 0x4000 + (entry & 0x3ff) * (eightBit ? 64 : 32);
        // Text character data cannot address OBJ VRAM.
        if (tile >= 0x06010000) return TRANSPARENT;
        var index = eightBit ? read8(tile + ty * 8 + tx) : (read8(tile + ty * 4 + tx / 2) >>> ((tx & 1) * 4)) & 15;
        if (index == 0) return TRANSPARENT;
        return palette(index + (eightBit ? 0 : (entry >>> 12) * 16));
    }

    private int affinePixel(int x, int y, int control) {
        var size = 128 << (control >>> 14);
        if ((control & 0x2000) != 0) { x &= size - 1; y &= size - 1; }
        else if (x < 0 || y < 0 || x >= size || y >= size) return TRANSPARENT;
        var map = 0x06000000 + ((control >>> 8) & 31) * 0x800;
        var tile = read8(map + (y / 8) * (size / 8) + x / 8);
        var address = 0x06000000 + ((control >>> 2) & 3) * 0x4000 + tile * 64 + (y & 7) * 8 + (x & 7);
        var index = read8(address);
        return index == 0 ? TRANSPARENT : palette(index);
    }

    private void renderObjects(int y, int display) {
        for (var x = 0; x < 240; x++) { objects[x] = TRANSPARENT; objectPriority[x] = 4; objectWindow[x] = false; }
        if ((display & 0x1000) == 0) return;
        for (var object = 0; object < 128; object++) {
            var address = 0x07000000 + object * 8;
            var a = read16(address);
            var b = read16(address + 2);
            var c = read16(address + 4);
            var affine = (a & 0x100) != 0;
            if ((!affine && (a & 0x200) != 0) || (a >>> 14) == 3 || ((a >>> 10) & 3) == 3) continue;
            var shape = (a >>> 14) * 4 + (b >>> 14);
            var width = OBJECT_WIDTHS[shape];
            var height = OBJECT_HEIGHTS[shape];
            var doubleSize = affine && (a & 0x200) != 0;
            var boxWidth = doubleSize ? width * 2 : width;
            var boxHeight = doubleSize ? height * 2 : height;
            var row = (y - (a & 255)) & 255;
            if (row >= boxHeight) continue;
            var positionX = b & 511;
            if (positionX >= 256) positionX -= 512;
            var priority = (c >>> 10) & 3;
            for (var column = 0; column < boxWidth; column++) {
                var x = positionX + column;
                if (x < 0 || x >= 240) continue;
                var px = column;
                var py = row;
                if ((a & 0x1000) != 0) {
                    var mosaic = io(0x4c);
                    px -= px % (((mosaic >>> 8) & 15) + 1);
                    py -= py % (((mosaic >>> 12) & 15) + 1);
                }
                if (affine) {
                    var matrix = 0x07000000 + ((b >>> 9) & 31) * 32 + 6;
                    var dx = px - boxWidth / 2;
                    var dy = py - boxHeight / 2;
                    px = (((short) read16(matrix) * dx + (short) read16(matrix + 8) * dy) >> 8) + width / 2;
                    py = (((short) read16(matrix + 16) * dx + (short) read16(matrix + 24) * dy) >> 8) + height / 2;
                } else {
                    if ((b & 0x1000) != 0) px = width - 1 - px;
                    if ((b & 0x2000) != 0) py = height - 1 - py;
                }
                if (px < 0 || py < 0 || px >= width || py >= height) continue;
                var eightBit = (a & 0x2000) != 0;
                var tileBase = (c & 0x3ff) & (eightBit ? ~1 : ~0);
                var stride = (display & 0x40) != 0 ? (width / 8) * (eightBit ? 2 : 1) : 32;
                var tile = (tileBase + (py / 8) * stride + (px / 8) * (eightBit ? 2 : 1)) & 0x3ff;
                if ((display & 7) >= 3 && tile < 512) continue;
                var data = 0x06010000 + tile * 32;
                var index = eightBit ? read8(data + (py & 7) * 8 + (px & 7))
                        : (read8(data + (py & 7) * 4 + (px & 7) / 2) >>> ((px & 1) * 4)) & 15;
                if (index == 0) continue;
                var objectMode = (a >>> 10) & 3;
                if (objectMode == 2) { objectWindow[x] = true; continue; }
                if (priority >= objectPriority[x]) continue;
                objectPriority[x] = priority;
                objects[x] = palette(256 + index + (eightBit ? 0 : (c >>> 12) * 16))
                        | (objectMode == 1 ? SEMITRANSPARENT : 0);
            }
        }
    }

    private int windowMask(int x, int y, int display) {
        if ((display & 0xe000) == 0) return 0x3f;
        if ((display & 0x2000) != 0 && inside(x, io(0x40)) && inside(y, io(0x44))) return io(0x48) & 0x3f;
        if ((display & 0x4000) != 0 && inside(x, io(0x42)) && inside(y, io(0x46))) return (io(0x48) >>> 8) & 0x3f;
        return (display & 0x8000) != 0 && objectWindow[x] ? (io(0x4a) >>> 8) & 0x3f : io(0x4a) & 0x3f;
    }

    private static boolean inside(int coordinate, int bounds) {
        var start = bounds >>> 8;
        var end = bounds & 255;
        return start <= end ? coordinate >= start && coordinate < end : coordinate >= start || coordinate < end;
    }

    private static int blend(int a, int b, int eva, int evb) {
        int result = 0;
        for (var shift = 0; shift < 15; shift += 5) {
            var channel = (((a >>> shift) & 31) * eva + ((b >>> shift) & 31) * evb) >> 4;
            result |= (channel > 31 ? 31 : channel) << shift;
        }
        return result;
    }

    private static int clamp(int coefficient) { return coefficient > 16 ? 16 : coefficient; }
    private static int brightness(int color, int amount, boolean brighten) {
        int result = 0;
        for (var shift = 0; shift < 15; shift += 5) {
            var value = (color >>> shift) & 31;
            value = brighten ? value + ((31 - value) * amount >> 4) : value - (value * amount >> 4);
            result |= value << shift;
        }
        return result;
    }
    private static int wrap(int coordinate, int size) { var value = coordinate % size; return value < 0 ? value + size : value; }
    private int io(int offset) { return read16(0x04000000 + offset); }
    private int palette(int index) { return read16(0x05000000 + index * 2) & 0x7fff; }
    private int read8(int address) { return memory.peek8(address); }
    private int read16(int address) { return memory.peek16(address); }
    private int read32(int address) { return read16(address) | (read16(address + 2) << 16); }
    private static int rgb888(int color) {
        var r = color & 31; var g = (color >>> 5) & 31; var b = (color >>> 10) & 31;
        return (((r << 3) | (r >>> 2)) << 16) | (((g << 3) | (g >>> 2)) << 8) | (b << 3) | (b >>> 2);
    }
}

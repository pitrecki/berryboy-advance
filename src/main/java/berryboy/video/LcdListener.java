package berryboy.video;

/** Routes LCD boundaries to bus devices without a video-to-DMA dependency. */
public interface LcdListener {
    void hblank(int line);
    void vblank();
}

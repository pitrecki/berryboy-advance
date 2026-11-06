import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Export the generated brand master to compact runtime and README assets. */
public final class PrepareBrandAssets {
    public static void main(String[] args) throws Exception {
        var source = ImageIO.read(Path.of("assets/branding/berryboy-icon-master.png").toFile());
        if (source == null || source.getWidth() != source.getHeight()) throw new IllegalArgumentException("Square logo master required");
        write(source, 256, "docs/images/brand/berryboy-icon.png");
        write(source, 64, "platform/midp/resources/berryboy-icon.png");
        write(source, 40, "platform/midp/resources/berryboy-mark.png");
    }
    private static void write(BufferedImage original, int size, String name) throws Exception {
        var image = original;
        while (image.getWidth() > size) {
            var nextSize = Math.max(size, image.getWidth() / 2);
            var next = new BufferedImage(nextSize, nextSize, BufferedImage.TYPE_INT_ARGB);
            var graphics = next.createGraphics();
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(image, 0, 0, nextSize, nextSize, null); graphics.dispose(); image = next;
        }
        ImageIO.write(image, "png", Path.of(name).toFile());
        System.out.println(name + " " + size + "x" + size);
    }
}

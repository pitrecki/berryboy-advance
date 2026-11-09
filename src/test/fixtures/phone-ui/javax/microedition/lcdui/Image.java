package javax.microedition.lcdui;
/** Host LCDUI double; not a BlackBerry runtime implementation. */
public final class Image {
    final java.awt.image.BufferedImage image;
    private Image(java.awt.image.BufferedImage image){this.image=image;}
    public static Image createImage(String resource)throws java.io.IOException{
        try(var in=Image.class.getResourceAsStream(resource)){if(in==null)throw new java.io.IOException(resource);return new Image(javax.imageio.ImageIO.read(in));}
    }
}

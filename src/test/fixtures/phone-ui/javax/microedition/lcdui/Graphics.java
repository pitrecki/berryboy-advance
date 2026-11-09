package javax.microedition.lcdui;
/** Host LCDUI double; not a BlackBerry runtime implementation. */
public final class Graphics {
    public static final int TOP=16,LEFT=4;
    private final java.awt.Graphics2D graphics;
    Graphics(java.awt.Graphics2D graphics){this.graphics=graphics;graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);}
    public void setColor(int rgb){graphics.setColor(new java.awt.Color(rgb));}
    public void fillRect(int x,int y,int w,int h){graphics.fillRect(x,y,w,h);}
    public void setFont(Font font){graphics.setFont(font.font);}
    public void drawString(String s,int x,int y,int anchor){graphics.drawString(s,x,y+graphics.getFontMetrics().getAscent());}
    public void drawImage(Image image,int x,int y,int anchor){graphics.drawImage(image.image,x,y,null);}
    public void drawRGB(int[] rgb,int offset,int scan,int x,int y,int w,int h,boolean alpha){var image=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,w,h,rgb,offset,scan);graphics.drawImage(image,x,y,null);}
}

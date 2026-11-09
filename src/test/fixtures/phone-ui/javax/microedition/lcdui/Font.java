package javax.microedition.lcdui;
/** Host LCDUI double; not a BlackBerry runtime implementation. */
public final class Font {
    public static final int FACE_SYSTEM=0,STYLE_PLAIN=0,STYLE_BOLD=1,SIZE_MEDIUM=0,SIZE_LARGE=1;
    final java.awt.Font font;
    private Font(int style,int size){font=new java.awt.Font("Dialog",style==STYLE_BOLD?java.awt.Font.BOLD:java.awt.Font.PLAIN,size==SIZE_LARGE?24:16);}
    public static Font getFont(int face,int style,int size){return new Font(style,size);}
    public int stringWidth(String s){return (int)Math.ceil(font.getStringBounds(s,new java.awt.font.FontRenderContext(null,true,true)).getWidth());}
}

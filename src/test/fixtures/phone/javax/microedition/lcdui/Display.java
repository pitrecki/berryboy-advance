package javax.microedition.lcdui;
public final class Display {
    private static final java.util.Map<Object,Display> displays=new java.util.WeakHashMap<>();
    private volatile Displayable current;
    public static synchronized Display getDisplay(Object app){return displays.computeIfAbsent(app,k->new Display());}
    public void setCurrent(Displayable value){current=value;}
    public Displayable getCurrent(){return current;}
}

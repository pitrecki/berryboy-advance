package javax.microedition.lcdui;
public final class Display {
    private static final java.util.Map<Object,Display> displays=new java.util.WeakHashMap<>();
    private volatile Displayable current;
    public static synchronized Display getDisplay(Object app){return displays.computeIfAbsent(app,k->new Display());}
    /** The platform drops a destroyed application's display; values must not retain weak map keys. */
    public static synchronized void release(Object app){var display=displays.remove(app);if(display!=null)display.current=null;}
    public static synchronized int liveDisplays(){return displays.size();}
    public void setCurrent(Displayable value){current=value;}
    public Displayable getCurrent(){return current;}
}

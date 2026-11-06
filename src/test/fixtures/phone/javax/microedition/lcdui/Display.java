package javax.microedition.lcdui;
public final class Display {
    private volatile Displayable current;
    public void setCurrent(Displayable value){current=value;}
    public Displayable getCurrent(){return current;}
}

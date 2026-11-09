package javax.microedition.lcdui;
/** Host LCDUI double; not a BlackBerry runtime implementation. */
public abstract class Canvas extends Displayable {
    public static final int UP=1,LEFT=2,RIGHT=5,DOWN=6,FIRE=8;
    public volatile Runnable presentationHook;
    protected Canvas(){super("");}
    public int getWidth(){return 640;} public int getHeight(){return 480;}
    public void setFullScreenMode(boolean full){} public void repaint(){}
    public void serviceRepaints(){var hook=presentationHook;if(hook!=null)hook.run();}
    public int getGameAction(int code){return 0;}
    protected abstract void paint(Graphics g);
    protected void keyPressed(int code){} protected void keyReleased(int code){}
    protected void keyRepeated(int code){} protected void pointerPressed(int x,int y){} protected void hideNotify(){}
    public void press(int code){keyPressed(code);} public void release(int code){keyReleased(code);}
    public java.awt.image.BufferedImage render(){var image=new java.awt.image.BufferedImage(640,480,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();try{paint(new Graphics(g));}finally{g.dispose();}return image;}
}

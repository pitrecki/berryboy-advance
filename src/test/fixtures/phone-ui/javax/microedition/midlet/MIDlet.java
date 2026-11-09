package javax.microedition.midlet;
public abstract class MIDlet {
    protected abstract void startApp(); protected abstract void pauseApp();
    protected abstract void destroyApp(boolean unconditional)throws MIDletStateChangeException;
    public String getAppProperty(String name){return "test";} public void notifyDestroyed(){}
}

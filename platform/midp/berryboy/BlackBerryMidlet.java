package berryboy;

import berryboy.phone.PhoneCanvas;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

/** BBOS entry point; the canvas controls UI, the session owns all guest state. */
public final class BlackBerryMidlet extends MIDlet {
    private PhoneCanvas canvas;
    protected void startApp() {
        if (canvas == null) canvas = new PhoneCanvas(this);
        canvas.open();
    }
    protected void pauseApp() { if (canvas != null) canvas.suspend(); }
    protected void destroyApp(boolean unconditional) throws MIDletStateChangeException {
        if (canvas == null) return;
        canvas.shutdown();
        try {
            if (!canvas.awaitClosed(3000) && !unconditional) throw new MIDletStateChangeException("Finishing save; retry exit");
        } catch (InterruptedException interrupted) {
            if (!unconditional) throw new MIDletStateChangeException("Interrupted while saving");
        }
    }
    public void sessionClosed() { notifyDestroyed(); }
}

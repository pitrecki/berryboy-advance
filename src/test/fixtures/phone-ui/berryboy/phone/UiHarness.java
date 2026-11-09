package berryboy.phone;

import berryboy.BlackBerryMidlet;
import berryboy.mobile.ui.PhoneScreen;
import javax.microedition.lcdui.*;

/** Drives the production controller with headless LCDUI/filesystem adapters. No guest access. */
public final class UiHarness implements AutoCloseable {
    public final BlackBerryMidlet application = new BlackBerryMidlet();
    public final PhoneCanvas canvas = new PhoneCanvas(application);
    public final Display display = Display.getDisplay(application);
    public UiHarness(){canvas.open();}
    public PhoneScreen screen() throws Exception {
        synchronized(canvas){var field=PhoneCanvas.class.getDeclaredField("screen");field.setAccessible(true);return (PhoneScreen)field.get(canvas);}
    }
    public void select(String label) throws Exception {
        synchronized(canvas){
            var screen=screen();int index=-1;
            for(int i=0;i<screen.count();i++)if(screen.label(i).equals(label))index=i;
            if(index<0)throw new IllegalArgumentException("No menu item: "+label);
            while(screen.selection()!=index)canvas.press('k');
            canvas.press('\n');canvas.release('\n');
        }
    }
    public void command(String label){display.getCurrent().fire(label);}
    public void text(int index,String value){((TextField)((Form)display.getCurrent()).get(index)).setString(value);}
    public String text(int index){return ((TextField)((Form)display.getCurrent()).get(index)).getString();}
    public void choose(String name)throws Exception{
        var end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(display.getCurrent().getTitle().equals("Reading storage...")&&System.nanoTime()<end)Thread.sleep(5);
        var list=(List)display.getCurrent();
        for(int i=0;i<list.size();i++)if(list.getString(i).equals(name)){list.setSelectedIndex(i,true);list.fire(List.SELECT_COMMAND);return;}
        throw new IllegalArgumentException("No file: "+name+" in "+list.getTitle());
    }
    public void close()throws Exception{canvas.presentationHook=null;canvas.shutdown();if(!canvas.awaitClosed(5000))throw new IllegalStateException("Session did not close");}
}

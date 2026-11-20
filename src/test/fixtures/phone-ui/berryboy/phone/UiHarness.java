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
    public void choice(int item,int index){((ChoiceGroup)((Form)display.getCurrent()).get(item)).setSelectedIndex(index,true);}
    public String[] choices(){
        var list=(List)display.getCurrent();var result=new String[list.size()];
        for(int i=0;i<result.length;i++)result[i]=list.getString(i);
        return result;
    }
    public void choose(String name)throws Exception{
        var end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(display.getCurrent().getTitle().equals("Reading storage...")&&System.nanoTime()<end)Thread.sleep(5);
        var list=(List)display.getCurrent();
        for(int i=0;i<list.size();i++)if(list.getString(i).equals(name)){list.setSelectedIndex(i,true);list.fire(List.SELECT_COMMAND);return;}
        throw new IllegalArgumentException("No file: "+name+" in "+list.getTitle());
    }
    /** Command completion barrier; no emulated machine/buffer is inspected or shared. */
    public void awaitIdle()throws Exception{
        var field=PhoneCanvas.class.getDeclaredField("session");field.setAccessible(true);var session=field.get(canvas);
        var type=session.getClass();var busy=type.getDeclaredField("busy");var command=type.getDeclaredField("command");
        var paused=type.getDeclaredField("paused");var pending=type.getDeclaredField("pendingOptions");
        var cheats=type.getDeclaredField("pendingCheats");
        for(var f:new java.lang.reflect.Field[]{busy,command,paused,pending,cheats})f.setAccessible(true);
        var end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end){
            synchronized(session){if(paused.getBoolean(session)&&!busy.getBoolean(session)&&command.getInt(session)==0&&pending.get(session)==null&&cheats.get(session)==null)return;}
            Thread.sleep(2);
        }
        throw new IllegalStateException("Session command did not finish: "+screen().message());
    }
    /** Reads a committed owned file, never the session's live machine. */
    public byte[] readSlot(String romUrl,int checksum,int slot)throws Exception{
        awaitIdle();return new PhoneStorage(romUrl,checksum).read("st"+slot);
    }
    /** Owned committed battery bytes; no live cartridge or machine access. */
    public byte[] readBackup(String romUrl,int checksum)throws Exception{
        awaitIdle();return new PhoneStorage(romUrl,checksum).read("sav");
    }
    /** Test-owned external payload preparation; production UI loading still validates every byte. */
    public void writeSlot(String romUrl,int checksum,int slot,byte[] bytes)throws Exception{
        awaitIdle();new PhoneStorage(romUrl,checksum).write("st"+slot,bytes);
    }
    public void close(){
        canvas.presentationHook=null;canvas.shutdown();
        try{if(!canvas.awaitClosed(5000))throw new IllegalStateException("Session did not close");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted while closing session",error);}
    }
}

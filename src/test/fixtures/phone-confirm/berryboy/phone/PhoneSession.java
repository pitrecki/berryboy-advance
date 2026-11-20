package berryboy.phone;

import berryboy.cheat.CheatList;
import berryboy.mobile.settings.PhoneOptions;

/** Completes a command as soon as UI publication permits, making status races deterministic. */
final class PhoneSession {
    static final int DEMO=1,OPEN=2,SAVE=3,LOAD=4,RESTART=5,CHECKS=6,SLOTS=7,BIOS=8;
    private final PhoneCanvas view;
    private Thread worker;
    private boolean closed;
    PhoneSession(PhoneCanvas view,PhoneOptions options){this.view=view;}
    boolean request(int operation,int slot,String rom,String bios){
        view.loaded("Controlled worker",new CheatList(),emptySlots(),true,1);return true;
    }
    boolean requestForGame(final int operation,final int slot,int generation){
        if(generation!=1)return false;
        if(operation==SLOTS){view.slots(emptySlots(),true);return true;}
        worker=new Thread(() -> {
            if(operation==RESTART)view.restored(new int[38400],"Game restarted");
            else if(operation==SAVE){view.slots(emptySlots(),true);view.message("Saved slot "+(slot+1));}
            else throw new IllegalArgumentException("Unexpected fixture command");
        });
        worker.start();
        // Without a UI publication transaction the worker completes before request
        // returns. With it, completion waits at the canvas until publication ends.
        long end=System.nanoTime()+2_000_000_000L;
        while(worker.getState()!=Thread.State.TERMINATED && worker.getState()!=Thread.State.BLOCKED && System.nanoTime()<end)Thread.yield();
        return true;
    }
    void pause(){} void resume(){} void foreground(boolean active){} void buttons(int value){}
    void options(PhoneOptions value){} void cheats(CheatList value,int generation){}
    void close(){
        try{if(worker!=null)worker.join(2000);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
        closed=true;view.closed();
    }
    boolean awaitClosed(long timeout){return closed;}
    static String[] emptySlots(){return new String[]{"Saved","Saved","Saved","Saved","Saved"};}
}

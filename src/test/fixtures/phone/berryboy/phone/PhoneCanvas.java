package berryboy.phone;
import berryboy.cheat.CheatList;
/** Session callback recorder, not an SDK/UI implementation. */
public final class PhoneCanvas {
    public volatile boolean loaded,closed;
    public volatile int frames,slots,generation;
    public volatile String message="",failure;
    synchronized long paintedFrames(){return frames;}
    void awaitPresentation(){}
    synchronized void loaded(String title,CheatList cheats,String[] values,boolean persistent,int generation){this.generation=generation;loaded=true;}
    synchronized void slots(String[] values,boolean supported){slots++;}
    synchronized void present(int[] owned,int gba,int draw,String audio){frames++;notifyAll();}
    synchronized void failed(String error){failure=error;notifyAll();}
    synchronized void message(String text){message=text;notifyAll();}
    synchronized void restored(int[] owned,String text){message=text;notifyAll();}
    synchronized void closeFailed(String error){failure=error;notifyAll();}
    synchronized void closed(){closed=true;notifyAll();}
}

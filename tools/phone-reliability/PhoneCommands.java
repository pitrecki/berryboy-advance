package berryboy.desktop.reliability;

import berryboy.cartridge.Cartridge;
import berryboy.phone.PhoneAudio;
import berryboy.phone.UiHarness;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Production application commands and owned capture barriers; no live guest access. */
final class PhoneCommands implements AutoCloseable {
    private static final int[] CODES = {'z','x','\b','\n','l','j','i','k','s','a'};
    private final Path rom, bios;
    private final int checksum;
    private UiHarness ui;
    private FrameGate gate;
    private volatile Thread owner;
    private int held;
    private final BufferedImage surface = new BufferedImage(640,480,BufferedImage.TYPE_INT_RGB);

    PhoneCommands(Path rom, Path bios, byte[] cartridge) throws Exception {
        this.rom=rom; this.bios=bios; checksum=new Cartridge(cartridge).checksum();
        try{openPaused();}catch(Exception error){try{close();}catch(Exception cleanup){error.addSuppressed(cleanup);}throw error;}
    }
    private void openPaused() throws Exception {
        ui=new UiHarness(); attach();
        ui.select("Open game"); ui.text(0,rom.toUri().toASCIIString()); ui.text(1,bios.toUri().toASCIIString());
        ui.command("Start game"); await(); pause(); gate.close(); attach();
    }
    private void attach() {
        gate=new FrameGate();
        var currentGate=gate;var currentUi=ui;
        PhoneAudio.packetHook=currentGate::audio;
        ui.canvas.presentationHook=() -> {
            owner=Thread.currentThread();
            try{currentGate.present(currentUi.screen().frame());}
            catch(Exception error){throw new IllegalStateException("Presentation capture failed",error);}
        };
    }
    void install(byte[] state) throws Exception { ui.writeSlot(rom.toUri().toASCIIString(),checksum,0,state); load(0); }
    FrameGate.Frame await() throws Exception {
        FrameGate.Frame frame;
        try{frame=gate.await(Duration.ofSeconds(30));}
        catch(IllegalStateException error){throw new IllegalStateException("No frame; application: "+screen().message(),error);}
        ui.canvas.renderInto(surface); return frame;
    }
    BufferedImage surface() { return surface; }
    void proceed() { gate.proceed(); }
    void resume() throws Exception { ui.select("Resume game"); held=0; }
    void keys(int desired) {
        for(int i=0;i<CODES.length;i++) if((held&(1<<i))!=0 && (desired&(1<<i))==0)ui.canvas.release(CODES[i]);
        for(int i=0;i<CODES.length;i++) if((held&(1<<i))==0 && (desired&(1<<i))!=0)ui.canvas.press(CODES[i]);
        held=desired;
    }
    int held() { return held; }
    void pause() throws Exception {
        keys(0); ui.command("Pause / Menu"); gate.proceed(); idle();
    }
    private void idle() throws Exception {
        ui.awaitIdle();
        waitFor(() -> owner!=null && owner.getState()==Thread.State.WAITING,"Owner did not reach its paused mailbox");
    }
    byte[] save(int slot) throws Exception {
        ui.select("Save state"); idle(); ui.select("Slot "+(slot+1));
        if(ui.display.getCurrent().getTitle().startsWith("Overwrite slot")) ui.command("Confirm");
        message("Saved slot "+(slot+1)); idle();
        var bytes=slot(slot); ui.command("Back"); return bytes;
    }
    void load(int slot) throws Exception {
        ui.select("Load state"); idle(); ui.select("Slot "+(slot+1));
        message("Loaded slot "+(slot+1)); idle(); held=0;
    }
    byte[] slot(int slot) throws Exception { return ui.readSlot(rom.toUri().toASCIIString(),checksum,slot); }
    void focusCycle() throws Exception { ui.canvas.suspend(); idle(); ui.canvas.open(); }
    void reopen(int slot) throws Exception { close(); openPaused(); load(slot); }
    int pendingAudio() { return gate.pendingAudioPackets(); }
    int queuedFrames() { return gate.queuedFrames(); }
    private berryboy.mobile.ui.PhoneScreen screen() {
        try{return ui.screen();}catch(Exception error){throw new IllegalStateException("Cannot inspect the owned UI screen",error);}
    }
    private void message(String value) throws Exception { waitFor(() -> screen().message().equals(value),"Expected "+value); }
    private static void waitFor(BooleanSupplier ready,String failure) throws Exception {
        long end=System.nanoTime()+10_000_000_000L;
        while(!ready.getAsBoolean() && System.nanoTime()<end)Thread.sleep(2);
        if(!ready.getAsBoolean())throw new IllegalStateException(failure);
    }
    public void close() throws Exception {
        if(ui==null)return;
        PhoneAudio.packetHook=null; ui.canvas.presentationHook=null; gate.close(); ui.close();
        if(owner!=null) { owner.join(5000); if(owner.isAlive())throw new IllegalStateException("Previous guest owner still alive"); }
        ui=null; owner=null;
    }
}

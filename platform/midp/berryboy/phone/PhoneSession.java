package berryboy.phone;

import berryboy.DemoRom;
import berryboy.Machine;
import berryboy.boot.BiosStartup;
import berryboy.RuntimeChecks;
import berryboy.cartridge.Cartridge;
import berryboy.cartridge.CartridgeBackup;
import berryboy.cheat.CheatList;
import berryboy.mobile.video.FramePresentation;
import berryboy.mobile.audio.OutputMixer;
import berryboy.mobile.settings.PhoneOptions;
import berryboy.MachineState;
import berryboy.timing.CycleScheduler;
import berryboy.video.LcdTiming;

/** One persistent guest owner. UI commands and owned settings/cheat snapshots form a bounded mailbox. */
final class PhoneSession implements Runnable {
    static final int DEMO = 1, OPEN = 2, SAVE = 3, LOAD = 4, RESTART = 5, CHECKS = 6, SLOTS = 7;
    private final PhoneCanvas view;
    private final FramePresentation presentation = new FramePresentation();
    private PhoneOptions options;
    private CheatList cheats = new CheatList();
    private PhoneOptions pendingOptions;
    private CheatList pendingCheats;
    private int pendingCheatGeneration;
    private int gameGeneration;
    private boolean alive = true;
    private boolean paused = true;
    private boolean background;
    private boolean busy;
    private int command;
    private int argument;
    private String romUrl;
    private String biosUrl;
    private int buttons;
    private Machine machine;
    private byte[] rom;
    private byte[] bios;
    private PhoneStorage storage;
    private PhoneAudio audio;
    private BiosStartup startup;

    PhoneSession(PhoneCanvas view, PhoneOptions options) {
        this.view = view; this.options = options.copy();
        new Thread(this).start();
    }
    synchronized boolean request(int operation, int value, String romPath, String biosPath) {
        if (command != 0 || busy || !alive) return false;
        paused = true; buttons = 0;
        command = operation; argument = value; romUrl = romPath; biosUrl = biosPath; notifyAll(); return true;
    }
    synchronized void pause() { paused = true; buttons = 0; notifyAll(); }
    synchronized void resume() { if (!background && !busy && command == 0) paused = false; notifyAll(); }
    synchronized void foreground(boolean active) {
        background = !active;
        if (!active) { paused = true; buttons = 0; }
        notifyAll();
    }
    synchronized void buttons(int value) { buttons = value; }
    synchronized void options(PhoneOptions value) { pendingOptions = value.copy(); notifyAll(); }
    synchronized void cheats(CheatList value, int generation) {
        if (generation != gameGeneration) return;
        pendingCheats = value.copy(); pendingCheatGeneration = generation; notifyAll();
    }
    synchronized boolean requestForGame(int operation, int value, int generation) {
        return generation == gameGeneration && request(operation, value, null, null);
    }
    synchronized void close() { alive = false; paused = true; buttons = 0; notifyAll(); }
    synchronized boolean awaitClosed(long timeout) throws InterruptedException {
        long end = System.currentTimeMillis() + timeout;
        while (!finished && System.currentTimeMillis() < end) wait(50);
        return finished;
    }
    private boolean finished;

    public void run() {
        short[] pcm = new short[2048];
        long wallOrigin = 0, cycleOrigin = 0, paintOrigin = 0, rateOrigin = 0;
        int drawTenths = 0;
        boolean wasPaused = false;
        try {
            while (true) {
                int operation, slot, held; String path, biosPath; boolean stopped, closing;
                synchronized (this) {
                    while (alive && paused && wasPaused && command == 0 && pendingOptions == null && pendingCheats == null) wait();
                    closing = !alive;
                    operation = command; slot = argument; path = romUrl; biosPath = biosUrl;
                    command = 0; busy = operation != 0;
                    held = buttons; stopped = paused;
                }
                if (closing) {
                    try { flushBackup(); break; }
                    catch (Exception error) {
                        synchronized (this) { alive = true; paused = true; busy = false; }
                        wasPaused = true; view.closeFailed("Progress not saved: " + error.toString()); continue;
                    }
                }
                try {
                    if (pendingChanges()) { if (audio != null) audio.reset(); }
                    if (operation != 0) {
                        if (audio != null) audio.reset();
                        flushBackup();
                        perform(operation, slot, path, biosPath);
                        wasPaused = true;
                        synchronized (this) { busy = false; stopped = paused; }
                    }
                    if (stopped || machine == null) {
                        if (!wasPaused) { if (audio != null) audio.reset(); flushBackup(); }
                        if (machine != null) machine.memory.setPressedKeys(0);
                        wasPaused = true; continue;
                    }
                    if (startup != null) {
                        machine.memory.setPressedKeys(held);
                        cheats.apply(machine.memory);
                        long bootTarget = (machine.clock.now() / LcdTiming.FRAME_CYCLES + 1) * LcdTiming.FRAME_CYCLES;
                        startup.runCycles(bootTarget - machine.clock.now());
                        int bootSamples = machine.memory.clockedAudio().availableFrames();
                        if (bootSamples > pcm.length / 2 || machine.memory.clockedAudio().overruns() != 0) throw new IllegalStateException("Audio capture overflow");
                        machine.memory.clockedAudio().drain(pcm, bootSamples);
                        if (startup.complete()) { startup = null; wallOrigin = 0; }
                        wasPaused = false;
                        continue; // Hidden boot has no presentation, MMAPI output or wall-clock wait.
                    }
                    if (wasPaused || wallOrigin == 0) {
                        wallOrigin = System.currentTimeMillis(); cycleOrigin = machine.clock.now();
                        rateOrigin = wallOrigin; paintOrigin = view.paintedFrames(); drawTenths = 0;
                        presentation.reset(); wasPaused = false;
                    }
                    machine.memory.setPressedKeys(held);
                    cheats.apply(machine.memory);
                    long target = (machine.clock.now() / LcdTiming.FRAME_CYCLES + 1) * LcdTiming.FRAME_CYCLES;
                    machine.runCycles(target - machine.clock.now());
                    int samples = machine.memory.clockedAudio().availableFrames();
                    if (samples > pcm.length / 2 || machine.memory.clockedAudio().overruns() != 0) throw new IllegalStateException("Audio capture overflow");
                    machine.memory.clockedAudio().drain(pcm, samples);
                    OutputMixer.apply(pcm, samples, options);
                    if (!options.muted()) {
                        if (audio == null) audio = new PhoneAudio();
                        if (audio.failure() == null) audio.append(pcm, samples);
                    }
                    long now = System.currentTimeMillis();
                    if (now - rateOrigin >= 1000) {
                        long painted = view.paintedFrames();
                        drawTenths = (int) ((painted - paintOrigin) * 10000 / (now - rateOrigin));
                        rateOrigin = now; paintOrigin = painted;
                    }
                    if (presentation.next(options.frameSkip(), now)) {
                        int[] pixels = new int[240 * 160];
                        System.arraycopy(machine.memory.raster().completedFrame(), 0, pixels, 0, pixels.length);
                        view.present(pixels, presentation.guestTenths(), drawTenths, audio == null ? null : audio.failure());
                        view.awaitPresentation();
                    }
                    long deadline = wallOrigin + (machine.clock.now() - cycleOrigin) * 1000 / CycleScheduler.CLOCK_HZ;
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining > 0) Thread.sleep(remaining > 20 ? 20 : remaining);
                } catch (Exception error) {
                    synchronized (this) { paused = true; busy = false; buttons = 0; }
                    if (audio != null) audio.reset();
                    wasPaused = true;
                    view.failed(error.toString());
                }
            }
        } catch (InterruptedException stopped) { view.failed("Session interrupted"); }
        finally {
            if (audio != null) audio.close();
            synchronized (this) { alive = false; finished = true; notifyAll(); }
            view.closed();
        }
    }
    private boolean pendingChanges() throws Exception {
        PhoneOptions settings; CheatList replacements;
        synchronized (this) {
            settings = pendingOptions; pendingOptions = null;
            replacements = pendingCheatGeneration == gameGeneration ? pendingCheats : null;
            pendingCheats = null;
        }
        if (settings != null) options = settings;
        if (replacements != null) {
            if (storage != null) storage.cheats(replacements);
            cheats = replacements;
        }
        return settings != null;
    }
    private void perform(int operation, int slot, String path, String biosPath) throws Exception {
        if (operation == DEMO || operation == OPEN) {
            byte[] nextRom = operation == DEMO ? DemoRom.create() : PhoneFiles.read(path, Cartridge.MAX_SIZE);
            byte[] nextBios = operation == DEMO ? null : PhoneFiles.read(biosPath, 16384);
            Machine candidate = new Machine(nextRom, nextBios);
            PhoneStorage nextStorage = operation == DEMO ? null : new PhoneStorage(path, candidate.cartridge.checksum());
            CheatList nextCheats = new CheatList();
            if (nextStorage != null) {
                byte[] backup = nextStorage.initialBackup(candidate.memory.backup().snapshot().length);
                if (backup != null) {
                    if (candidate.memory.backup().isEeprom() && (backup.length == 512 || backup.length == 8192)) {
                        candidate.memory.setBackup(new CartridgeBackup(backup.length == 512 ? CartridgeBackup.EEPROM_512 : CartridgeBackup.EEPROM_8K));
                    }
                    candidate.memory.backup().restore(backup);
                }
                nextCheats = nextStorage.cheats();
            }
            flushBackup();
            rom = nextRom; bios = nextBios; machine = candidate; storage = nextStorage; cheats = nextCheats;
            startup = bios != null && !options.showBoot() ? new BiosStartup(machine) : null;
            machine.memory.clockedAudio().setCapture(true);
            synchronized (this) { gameGeneration++; }
            view.loaded(machine.cartridge.title(), nextCheats.copy(), storage == null ? emptySlots() : storage.slots(), storage != null, gameGeneration);
            synchronized (this) { paused = background; }
            presentation.reset();
        } else if (operation == CHECKS) {
            int passed = RuntimeChecks.run(); view.message("PASS: " + passed + " portable runtime checks");
        } else if (operation == SLOTS) {
            view.slots(storage == null ? emptySlots() : storage.slots(), storage != null);
        } else {
            if (machine == null) throw new IllegalStateException("Open a game first");
            if (operation == RESTART) {
                flushBackup();
                Machine replacement = new Machine(rom, bios);
                replacement.memory.setBackup(new CartridgeBackup(machine.memory.backup().type()));
                replacement.memory.backup().restore(machine.memory.backup().snapshot());
                machine = replacement; machine.memory.clockedAudio().setCapture(true);
                startup = bios != null && !options.showBoot() ? new BiosStartup(machine) : null;
                showRestored("Game restarted");
            } else {
                if (storage == null) throw new IllegalStateException("State files require a game opened from storage");
                if (slot < 0 || slot >= 5) throw new IllegalArgumentException("Invalid slot");
                if (operation == SAVE) {
                    storage.write("st" + slot, MachineState.save(machine));
                    view.slots(storage.slots(), true); view.message("Saved slot " + (slot + 1));
                } else if (operation == LOAD) {
                    byte[] bytes = storage.read("st" + slot);
                    if (bytes == null) throw new IllegalStateException("This slot is empty");
                    Machine replacement = MachineState.load(rom, bios, bytes);
                    flushBackup(); machine = replacement;
                    startup = null; // Loading a state restores its exact screen; boot preference is for fresh starts.
                    machine.memory.clockedAudio().setCapture(true);
                    showRestored("Loaded slot " + (slot + 1));
                }
            }
            presentation.reset();
        }
    }
    private void flushBackup() throws Exception {
        if (machine != null && storage != null && machine.memory.backup().dirty()) {
            storage.write("sav", machine.memory.backup().snapshot()); machine.memory.backup().markSaved();
        }
    }
    private void showRestored(String message) {
        int[] pixels = new int[240 * 160];
        System.arraycopy(machine.memory.raster().completedFrame(), 0, pixels, 0, pixels.length);
        view.restored(pixels, message);
    }
    static String[] emptySlots() { return new String[]{"Empty", "Empty", "Empty", "Empty", "Empty"}; }
}

package berryboy.phone;

import berryboy.BlackBerryMidlet;
import berryboy.cheat.CheatCode;
import berryboy.cheat.CheatList;
import berryboy.input.Keypad;
import berryboy.mobile.PhoneMenus;
import berryboy.mobile.input.PhoneInput;
import berryboy.mobile.settings.PhoneOptions;
import berryboy.mobile.PhoneRenderer;
import berryboy.mobile.PhoneScreen;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.TextField;

/** UI/controller only. Machine state and storage operations belong to PhoneSession. */
public final class PhoneCanvas extends Canvas implements CommandListener {
    private static final int HOME = 0, GAME = 1, PAUSE = 2, SETTINGS = 3, CONTROLS = 4, SAVE = 5, LOAD = 6, CHEATS = 7;
    private final Command menu = new Command("Pause / Menu", Command.SCREEN, 1);
    private final Command back = new Command("Back", Command.BACK, 2);
    private final Command exit = new Command("Exit", Command.EXIT, 9);
    private final Command start = new Command("Start game", Command.OK, 1);
    private final Command browseRom = new Command("Browse ROM", Command.SCREEN, 2);
    private final Command browseBios = new Command("Browse BIOS", Command.SCREEN, 3);
    private final Command addCheat = new Command("Add", Command.OK, 1);
    private final Command removeCheat = new Command("Delete cheat", Command.ITEM, 4);
    private final BlackBerryMidlet application;
    private final Display display;
    private final PhoneScreen screen = new PhoneScreen();
    private final MidpGraphics painter = new MidpGraphics();
    private final PhoneSession session;
    private final PhoneInput input = new PhoneInput();
    private PhoneOptions options = new PhoneOptions();
    private CheatList cheats = new CheatList();
    private int page;
    private int captureButton = -1;
    private boolean loaded;
    private boolean persistent;
    private boolean exiting;
    private boolean active;
    private int gameGeneration;
    private String game = "";
    private String[] slotSummaries = PhoneSession.emptySlots();
    private Form openForm;
    private TextField romPath;
    private TextField biosPath;
    private Form cheatForm;
    private int cheatFormGeneration;
    private TextField cheatName;
    private TextField cheatSource;
    private ChoiceGroup cheatFormat;
    private long frameVersion;
    private long paintedVersion = -1;
    private long painted;

    public PhoneCanvas(BlackBerryMidlet application) {
        this.application = application; display = Display.getDisplay(application);
        String startupError = null;
        try { byte[] data = PhoneRecords.read("berryboy-options"); if (data != null) options = PhoneOptions.decode(data); }
        catch (Exception error) { startupError = "Settings: " + error.toString(); }
        input.setStickyShoulders(options.stickyShoulders());
        session = new PhoneSession(this, options);
        setFullScreenMode(true); addCommand(menu); addCommand(back); addCommand(exit); addCommand(removeCheat); setCommandListener(this);
        show(HOME);
        if (startupError != null) message(startupError);
    }
    public synchronized void open() { active = true; session.foreground(true); display.setCurrent(this); }
    public synchronized void suspend() { active = false; session.foreground(false); pause(); }
    public synchronized void pause() {
        releaseKeys(); session.pause();
        if (page == GAME) show(PAUSE);
    }
    public void shutdown() {
        synchronized (this) { exiting = true; releaseKeys(); screen.setMessage("Saving progress and closing..."); repaint(); }
        session.close();
    }
    public synchronized void closed() { if (exiting) application.sessionClosed(); }
    public boolean awaitClosed(long timeout) throws InterruptedException { return session.awaitClosed(timeout); }
    synchronized void closeFailed(String message) { exiting = false; failed(message); }
    public synchronized long paintedFrames() { return painted; }
    // Called without the canvas monitor: the UI thread must be able to paint.
    void awaitPresentation() { serviceRepaints(); }
    synchronized void loaded(String title, CheatList cheats, String[] slots, boolean persistent, int generation) {
        releaseKeys();
        loaded = true; game = title; this.cheats = cheats; slotSummaries = slots; this.persistent = persistent;
        gameGeneration = generation;
        screen.setFrame(null); frameVersion++; show(active ? GAME : PAUSE);
        if (active) display.setCurrent(this);
    }
    synchronized void slots(String[] values, boolean supported) {
        slotSummaries = values; persistent = supported;
        if (page == SAVE || page == LOAD) { int selected = screen.selection(); show(page); screen.select(selected); repaint(); }
    }
    synchronized void restored(int[] ownedRgb, String status) {
        releaseKeys();
        screen.setFrame(ownedRgb); frameVersion++; show(PAUSE); message(status);
    }
    synchronized void present(int[] ownedRgb, int gbaTenths, int drawTenths, String audioError) {
        screen.setFrame(ownedRgb); screen.setScaling(options.scaling()); frameVersion++;
        if (page == GAME) {
            screen.setFps(options.showFps() ? "GBA " + rate(gbaTenths) + " / Draw " + rate(drawTenths) + " fps" : "");
            screen.setNotice(audioError == null ? "" : "Audio unavailable");
            repaint();
        }
    }
    synchronized void failed(String error) { releaseKeys(); if (loaded) show(PAUSE); message(error); if (active) display.setCurrent(this); }
    synchronized void message(String text) { screen.setMessage(text); repaint(); }
    private synchronized void show(int target) {
        page = target; captureButton = -1;
        if (target == HOME) PhoneMenus.home(screen, loaded);
        else if (target == GAME) {
            PhoneMenus.game(screen, game);
            screen.setScaling(options.scaling());
            screen.setFps(options.showFps() ? "GBA -- / Draw -- fps" : "");
            screen.setNotice("");
        }
        else if (target == PAUSE) PhoneMenus.pause(screen, game);
        else if (target == SETTINGS) PhoneMenus.settings(screen, options);
        else if (target == CONTROLS) PhoneMenus.controls(screen, options);
        else if (target == SAVE || target == LOAD) PhoneMenus.slots(screen, target == LOAD, slotSummaries, persistent);
        else if (target == CHEATS) PhoneMenus.cheats(screen, cheats);
        screen.setMessage(target == GAME ? "Back / Menu: pause"
                : target == CONTROLS && options.stickyShoulders() ? "L/R: tap to lock, tap again to release / Back clears"
                : "Navigate: trackpad / I,K / Select: Enter / Back");
        repaint();
    }
    protected synchronized void paint(Graphics graphics) {
        painter.attach(graphics); PhoneRenderer.paint(painter, screen, getWidth(), getHeight());
        if (page == GAME && screen.frame() != null && paintedVersion != frameVersion) { paintedVersion = frameVersion; painted++; }
    }
    protected synchronized void keyPressed(int code) {
        if (exiting) return;
        if (code == -11 || code == 27 || code == '#') { goBack(); return; }
        if (captureButton >= 0) {
            try { options.bind(captureButton, code); saveOptions(); show(CONTROLS); }
            catch (Exception error) { message(error.getMessage()); }
            return;
        }
        if (page == GAME) {
            int mapped = options.buttonsFor(code);
            if (mapped == 0) {
                int action = action(code);
                mapped = action == UP ? Keypad.UP : action == DOWN ? Keypad.DOWN : action == LEFT ? Keypad.LEFT
                        : action == RIGHT ? Keypad.RIGHT : action == FIRE ? Keypad.A : 0;
            }
            input.press(code, mapped);
            updateKeys(); return;
        }
        if (code >= 'A' && code <= 'Z') code += 'a' - 'A';
        int action = action(code);
        if (code == 'i' || action == UP) screen.move(-1);
        else if (code == 'k' || action == DOWN) screen.move(1);
        else if ((code == 'j' || action == LEFT) && page == SETTINGS) changeSetting(-1);
        else if ((code == 'l' || action == RIGHT) && page == SETTINGS) changeSetting(1);
        else if (code == '\n' || code == '\r' || code == ' ' || action == FIRE) activate();
        repaint();
    }
    protected synchronized void keyRepeated(int code) { if (page != GAME) keyPressed(code); }
    protected synchronized void keyReleased(int code) {
        input.release(code);
        updateKeys();
    }
    protected synchronized void hideNotify() { pause(); }
    protected synchronized void pointerPressed(int x, int y) {
        if (page == GAME) { pause(); return; }
        int menuWidth = getWidth() >= 600 && screen.frame() != null ? getWidth() - 280 : getWidth() - 32;
        if (x < 16 || x >= 16 + menuWidth) return;
        int row = (y - PhoneRenderer.HEADER) / PhoneRenderer.ROW;
        int visible = (getHeight() - PhoneRenderer.HEADER - PhoneRenderer.FOOTER) / PhoneRenderer.ROW;
        int first = screen.selection() >= visible ? screen.selection() - visible + 1 : 0;
        if (y >= PhoneRenderer.HEADER && row >= 0 && row < visible && first + row < screen.count()) {
            screen.select(first + row); activate(); repaint();
        }
    }
    private int action(int code) { try { return getGameAction(code); } catch (IllegalArgumentException unknown) { return 0; } }
    private void updateKeys() {
        session.buttons(page == GAME ? input.pressed() : 0);
        screen.setShoulderLocks(page == GAME ? input.lockedShoulders() : 0);
        repaint();
    }
    private void releaseKeys() { input.releaseAll(); updateKeys(); }
    private void goBack() {
        if (captureButton >= 0) { show(CONTROLS); return; }
        releaseKeys();
        if (page == GAME) { session.pause(); show(PAUSE); }
        else if (page == PAUSE) resume();
        else show(loaded && page != HOME ? PAUSE : HOME);
    }
    private void resume() { if (loaded) { releaseKeys(); show(GAME); session.resume(); } }
    private void activate() {
        if (screen.count() == 0 || !screen.enabled(screen.selection())) return;
        int selected = screen.selection();
        if (page == HOME) {
            if (selected == 0) openGameForm();
            else if (selected == 1) resume();
            else if (selected == 2) show(SETTINGS);
            else if (selected == 3) show(CONTROLS);
            else if (selected == 4) diagnostics();
            else message("BerryBoy " + application.getAppProperty("MIDlet-Version") + " / Demo in Diagnostics / Raw + CodeBreaker 3,8");
        } else if (page == PAUSE) {
            if (selected == 0) resume();
            else if (selected == 1 || selected == 2) {
                show(selected == 1 ? SAVE : LOAD); request(PhoneSession.SLOTS, 0, null, null);
            } else if (selected == 3) show(CHEATS);
            else if (selected == 4) show(SETTINGS);
            else if (selected == 5) show(CONTROLS);
            else if (selected == 6) confirmRestart();
            else show(HOME);
        } else if (page == SETTINGS) changeSetting(1);
        else if (page == CONTROLS) {
            if (selected == 0) {
                options.setStickyShoulders(!options.stickyShoulders());
                releaseKeys(); show(CONTROLS); saveOptions();
            } else {
                captureButton = selected - 1;
                message("Press a new key for " + PhoneMenus.buttonName(captureButton) + " / Back cancels");
            }
        }
        else if (page == SAVE || page == LOAD) {
            if (page == SAVE && !slotSummaries[selected].equals("Empty")) confirmSave(selected);
            else request(page == SAVE ? PhoneSession.SAVE : PhoneSession.LOAD, selected, null, null);
        } else if (page == CHEATS) {
            if (selected == 0) cheatForm();
            else { cheats.toggle(selected - 1); session.cheats(cheats, gameGeneration); show(CHEATS); screen.select(selected); }
        }
    }
    private void changeSetting(int direction) {
        int selected = screen.selection();
        if (selected == 0) { int value = options.volume() + direction * 10; options.setVolume(value < 0 ? 0 : value > 100 ? 100 : value); }
        else if (selected == 1) options.setMuted(!options.muted());
        else if (selected == 2) options.setMono(!options.mono());
        else if (selected == 3) options.setFrameSkip((options.frameSkip() + direction + 6) % 6);
        else if (selected == 4) options.setShowFps(!options.showFps());
        else options.setScaling((options.scaling() + direction + 4) % 4);
        show(SETTINGS); screen.select(selected); saveOptions();
    }
    private void saveOptions() {
        input.setStickyShoulders(options.stickyShoulders());
        session.options(options);
        try { PhoneRecords.write("berryboy-options", options.encode()); }
        catch (Exception error) { message("Settings not saved: " + error.toString()); }
    }
    private synchronized void request(int operation, int slot, String rom, String bios) {
        releaseKeys();
        boolean accepted = operation == PhoneSession.OPEN || operation == PhoneSession.DEMO || operation == PhoneSession.CHECKS
                ? session.request(operation, slot, rom, bios) : session.requestForGame(operation, slot, gameGeneration);
        if (accepted) message("Working...");
        else message("An operation is already in progress");
    }
    public synchronized void commandAction(Command command, Displayable source) {
        if (command == exit) { shutdown(); return; }
        if (source == openForm) {
            if (command == back) { display.setCurrent(this); return; }
            if (command == browseRom || command == browseBios) { new PhoneBrowser(display, this, command == browseBios); return; }
            if (command == start) {
                display.setCurrent(this); request(PhoneSession.OPEN, 0, romPath.getString(), biosPath.getString()); return;
            }
        }
        if (source == cheatForm) {
            if (cheatFormGeneration != gameGeneration) {
                display.setCurrent(this); message("Game changed; open Cheats again"); return;
            }
            if (command == back) { display.setCurrent(this); return; }
            if (command == addCheat) {
                try {
                    cheats.add(cheatName.getString(), new CheatCode(cheatSource.getString(), cheatFormat.getSelectedIndex()));
                    session.cheats(cheats, gameGeneration); show(CHEATS); display.setCurrent(this);
                } catch (Exception error) { cheatForm.setTitle(error.getMessage()); }
                return;
            }
        }
        if (command == removeCheat && page == CHEATS && screen.selection() > 0) {
            cheats.remove(screen.selection() - 1); session.cheats(cheats, gameGeneration); show(CHEATS); return;
        }
        if (command == menu) { if (page == GAME) pause(); else show(HOME); return; }
        if (command == back) goBack();
    }
    private void openGameForm() {
        session.pause(); releaseKeys();
        openForm = new Form("Open game");
        romPath = new TextField("ROM (.gba)", "file:///SDCard/roms/", 512, TextField.ANY);
        biosPath = new TextField("GBA BIOS (16 KiB)", "file:///SDCard/gba_bios.bin", 512, TextField.ANY);
        openForm.append(romPath); openForm.append(biosPath);
        openForm.append("Choose unpacked local files. Save slots use sidecar files next to the ROM.");
        openForm.addCommand(start); openForm.addCommand(browseRom); openForm.addCommand(browseBios); openForm.addCommand(back);
        openForm.setCommandListener(this); display.setCurrent(openForm);
    }
    private void cheatForm() {
        cheatFormGeneration = gameGeneration;
        cheatForm = new Form("Add cheat");
        cheatName = new TextField("Name", "", 24, TextField.ANY);
        cheatSource = new TextField("Code", "", 64, TextField.ANY);
        cheatFormat = new ChoiceGroup("Format", Choice.EXCLUSIVE, new String[]{"Raw ADDRESS:8|16|32:VALUE", "CodeBreaker XXXXXXXX YYYY"}, null);
        cheatForm.append(cheatName); cheatForm.append(cheatFormat); cheatForm.append(cheatSource);
        cheatForm.append("Raw example: 02000100:16:03E7. CodeBreaker: unencrypted types 3 and 8 only.");
        cheatForm.addCommand(addCheat); cheatForm.addCommand(back); cheatForm.setCommandListener(this); display.setCurrent(cheatForm);
    }
    synchronized void browserSelected(String url, boolean bios) { if (bios) biosPath.setString(url); else romPath.setString(url); display.setCurrent(openForm); }
    synchronized void browserCancelled() { display.setCurrent(openForm); }
    synchronized void browserFailed(String error) { openForm.setTitle(error); display.setCurrent(openForm); }
    private void diagnostics() {
        final Form form = new Form("Diagnostics");
        final Command demo = new Command("Run demo", Command.SCREEN, 2);
        final Command checks = new Command("Run checks", Command.SCREEN, 1);
        form.append("Run the built-in checks, or open the demo cartridge.");
        form.addCommand(demo); form.addCommand(checks); form.addCommand(back);
        form.setCommandListener(new CommandListener() {
            public void commandAction(Command command, Displayable source) {
                display.setCurrent(PhoneCanvas.this);
                if (command == demo) request(PhoneSession.DEMO, 0, null, null);
                else if (command == checks) request(PhoneSession.CHECKS, 0, null, null);
            }
        });
        display.setCurrent(form);
    }
    private void confirmSave(final int slot) { confirm("Overwrite slot " + (slot + 1) + "?", "The previous slot remains intact if writing fails.", PhoneSession.SAVE, slot); }
    private void confirmRestart() { confirm("Restart game?", "Battery progress is saved before restart.", PhoneSession.RESTART, 0); }
    private void confirm(String title, String explanation, final int operation, final int slot) {
        final int generation = gameGeneration;
        Form form = new Form(title); final Command yes = new Command("Confirm", Command.OK, 1);
        form.append(explanation); form.addCommand(yes); form.addCommand(back);
        form.setCommandListener(new CommandListener() {
            public void commandAction(Command command, Displayable source) {
                display.setCurrent(PhoneCanvas.this);
                if (command == yes) {
                    if (session.requestForGame(operation, slot, generation)) message("Working...");
                    else message("Game changed or an operation is in progress");
                }
            }
        });
        display.setCurrent(form);
    }
    private static String rate(int tenths) { return tenths == 0 ? "--" : tenths / 10 + "." + tenths % 10; }
}

package berryboy.phone.settings;

import berryboy.phone.library.FilePicker;
import berryboy.phone.library.FileUrls;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.TextField;

/** BIOS file form only; validation and persistence remain with the session/canvas owners. */
public final class BiosSettings implements CommandListener, FilePicker.Selection {
    public interface Selection {
        void load(String url);
        void cancelled();
    }
    private final Command load = new Command("Load BIOS", Command.OK, 1);
    private final Command browse = new Command("Browse BIOS", Command.SCREEN, 2);
    private final Command back = new Command("Back", Command.BACK, 3);
    private final Display display;
    private final Selection selection;
    private final Form form = new Form("GBA BIOS");
    private final TextField path;
    private boolean closed;

    public BiosSettings(Display display, String current, Selection selection) {
        this.display = display; this.selection = selection;
        path = new TextField("BIOS (.bin, 16 KiB)", current, 512, TextField.ANY);
        form.append(path);
        form.append("Supply your own 16384-byte GBA BIOS. It is not included in the app. Load checks the file and saves its location for the next game you open. Restart and state loading keep the current game's BIOS.");
        form.addCommand(load); form.addCommand(browse); form.addCommand(back);
        form.setCommandListener(this); display.setCurrent(form);
    }
    public synchronized void commandAction(Command command, Displayable source) {
        if (closed || source != form) return;
        if (command == back) { closed = true; selection.cancelled(); }
        else if (command == load) { closed = true; selection.load(path.getString()); }
        else if (command == browse) new FilePicker(display, this, false, ".bin", FileUrls.parent(path.getString()));
    }
    public synchronized void selected(String url) { if (!closed) { path.setString(url); display.setCurrent(form); } }
    public synchronized void cancelled() { if (!closed) display.setCurrent(form); }
    public synchronized void failed(String error) { if (!closed) { form.setTitle(error); display.setCurrent(form); } }
}

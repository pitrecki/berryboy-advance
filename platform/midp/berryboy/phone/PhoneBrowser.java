package berryboy.phone;

import java.util.Enumeration;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;

/** Small asynchronous local file picker. Never downloads or extracts cartridges. */
final class PhoneBrowser implements CommandListener, Runnable {
    private final Command back = new Command("Back", Command.BACK, 1);
    private final Display display;
    private final PhoneCanvas owner;
    private final boolean bios;
    private final Vector entries = new Vector();
    private String directory = "file:///";
    private List list;
    private boolean busy;
    PhoneBrowser(Display display, PhoneCanvas owner, boolean bios) { this.display = display; this.owner = owner; this.bios = bios; refresh(); }
    private synchronized void refresh() {
        if (busy) return;
        busy = true;
        list = new List("Reading storage...", List.IMPLICIT); list.addCommand(back); list.setCommandListener(this);
        display.setCurrent(list); new Thread(this).start();
    }
    public void run() {
        try {
            Vector found = new Vector();
            if (directory.equals("file:///")) {
                Enumeration roots = FileSystemRegistry.listRoots();
                while (roots.hasMoreElements()) found.addElement(roots.nextElement());
            } else {
                FileConnection connection = (FileConnection) Connector.open(directory, Connector.READ);
                try {
                    Enumeration names = connection.list("*", false);
                    while (names.hasMoreElements()) {
                        String name = (String) names.nextElement(); String lower = name.toLowerCase();
                        if (name.endsWith("/") || (bios ? lower.endsWith(".bin") : lower.endsWith(".gba"))) found.addElement(name);
                    }
                } finally { connection.close(); }
            }
            synchronized (this) {
                entries.removeAllElements(); list.deleteAll();
                if (!directory.equals("file:///")) { entries.addElement(".."); list.append("..", null); }
                for (int i = 0; i < found.size(); i++) { String name = (String) found.elementAt(i); entries.addElement(name); list.append(name, null); }
                list.setTitle(bios ? "Choose GBA BIOS" : "Choose GBA ROM");
            }
        } catch (Exception error) { owner.browserFailed(error.toString()); }
        finally { synchronized (this) { busy = false; } }
    }
    public synchronized void commandAction(Command command, Displayable source) {
        if (command == back) { owner.browserCancelled(); return; }
        if (busy || source != list || list.getSelectedIndex() < 0) return;
        String selected = (String) entries.elementAt(list.getSelectedIndex());
        if (selected.equals("..")) {
            String parent = directory.substring(0, directory.length() - 1);
            directory = parent.substring(0, parent.lastIndexOf('/') + 1); refresh();
        } else {
            try {
                String url = PhoneFiles.childUrl(directory, selected);
                if (selected.endsWith("/")) { directory = url; refresh(); }
                else owner.browserSelected(url, bios);
            } catch (Exception error) { owner.browserFailed(error.toString()); }
        }
    }
}

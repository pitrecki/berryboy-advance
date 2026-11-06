package berryboy.phone.library;

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

/** Asynchronous file/folder picker with no dependency on the application's canvas. */
public final class FilePicker implements CommandListener, Runnable {
    public interface Selection {
        void selected(String url);
        void cancelled();
        void failed(String error);
    }
    private final Command back = new Command("Back", Command.BACK, 1);
    private final Command choose = new Command("Use this folder", Command.OK, 1);
    private final Display display;
    private final Selection selection;
    private final boolean directories;
    private final String extension;
    private final Vector entries = new Vector();
    private String directory;
    private List list;
    private boolean busy;
    private boolean closed;

    public FilePicker(Display display, Selection selection, boolean directories, String extension, String initialDirectory) {
        this.display = display; this.selection = selection; this.directories = directories; this.extension = extension;
        directory = FileUrls.directory(initialDirectory); refresh();
    }
    private synchronized void refresh() {
        if (busy || closed) return;
        busy = true;
        list = new List("Reading storage...", List.IMPLICIT); list.addCommand(back); list.setCommandListener(this);
        if (directories && !directory.equals(FileUrls.ROOTS)) list.addCommand(choose);
        display.setCurrent(list); new Thread(this).start();
    }
    public void run() {
        try {
            Vector found = new Vector();
            if (directory.equals(FileUrls.ROOTS)) {
                Enumeration roots = FileSystemRegistry.listRoots();
                while (roots.hasMoreElements()) found.addElement(roots.nextElement());
            } else {
                FileConnection connection = (FileConnection) Connector.open(directory, Connector.READ);
                try {
                    if (!connection.exists() || !connection.isDirectory()) throw new IllegalArgumentException("Folder not found: " + directory);
                    Enumeration names = connection.list("*", false);
                    while (names.hasMoreElements()) {
                        String name = (String) names.nextElement();
                        if (name.endsWith("/") || (!directories && FileUrls.matches(name, extension))) found.addElement(name);
                    }
                } finally { connection.close(); }
            }
            synchronized (this) {
                if (closed) return;
                entries.removeAllElements(); list.deleteAll();
                if (!directory.equals(FileUrls.ROOTS)) { entries.addElement(".."); list.append("..", null); }
                for (int i = 0; i < found.size(); i++) { String name = (String) found.elementAt(i); entries.addElement(name); list.append(name, null); }
                busy = false;
                list.setTitle(directories ? "Choose ROM folder" : extension.equals(".bin") ? "Choose GBA BIOS" : "Choose GBA ROM");
            }
        } catch (Exception error) {
            synchronized (this) { if (!closed) { closed = true; selection.failed(error.toString()); } }
        } finally { synchronized (this) { busy = false; } }
    }
    public synchronized void commandAction(Command command, Displayable source) {
        if (closed || source != list) return;
        if (command == back) { closed = true; selection.cancelled(); return; }
        if (busy) return;
        if (command == choose) {
            if (directories && !directory.equals(FileUrls.ROOTS)) { closed = true; selection.selected(directory); }
            return;
        }
        if (list.getSelectedIndex() < 0) return;
        String selected = (String) entries.elementAt(list.getSelectedIndex());
        if (selected.equals("..")) { directory = FileUrls.parent(directory); refresh(); }
        else {
            try {
                String url = FileUrls.child(directory, selected);
                if (selected.endsWith("/")) { directory = url; refresh(); }
                else if (!directories) { closed = true; selection.selected(url); }
            } catch (Exception error) { closed = true; selection.failed(error.toString()); }
        }
    }
}

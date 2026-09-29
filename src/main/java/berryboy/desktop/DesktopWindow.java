package berryboy.desktop;

import berryboy.Machine;
import berryboy.Memory;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFrame;

final class DesktopWindow {
    private DesktopWindow() {
    }

    static void show(Machine machine) {
        var panel = new DesktopPanel();
        var input = new KeyboardInput();
        input.bind(panel);
        panel.present(machine.video.render(), machine.memory.read16(Memory.KEY_INPUT), "Starting desktop session...");
        var session = new DesktopSession(machine, input, panel);
        var window = new JFrame("BerryBoy Advance");
        window.setContentPane(panel);
        window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        window.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent event) {
                input.releaseAll();
            }
        });
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                session.close();
            }
        });
        window.pack();
        window.setLocationByPlatform(true);
        window.setVisible(true);
        session.start();
    }
}

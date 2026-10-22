package berryboy.desktop;

import berryboy.DemoRom;
import berryboy.Machine;
import berryboy.Memory;
import java.awt.Container;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/** Paints the production Swing component with real emulator state, without a display server. */
public final class CaptureDocumentation {
    public static void main(String[] args) throws Exception {
        var output = Path.of(args.length == 0 ? "docs/images" : args[0]);
        Files.createDirectories(output);
        var machine = new Machine(DemoRom.create());
        machine.runInstructions(DemoRom.RUN_BUDGET);
        SwingUtilities.invokeAndWait(() -> {
            try {
                var panel = new DesktopPanel();
                var input = new KeyboardInput();
                input.bind(panel);
                panel.setSize(panel.getPreferredSize());
                layout(panel);
                panel.present(machine.video.render(), machine.memory.read16(Memory.KEY_INPUT),
                        "200,000 instructions / ARM to Thumb / original demo / headless UI capture");
                capture(panel, output.resolve("desktop-demo.png"));

                press(panel, KeyEvent.VK_RIGHT);
                press(panel, KeyEvent.VK_Z);
                machine.memory.setPressedKeys(input.pressedButtons());
                panel.present(machine.video.render(), machine.memory.read16(Memory.KEY_INPUT),
                        "Right + Z held through Swing bindings / headless UI capture");
                capture(panel, output.resolve("desktop-controls.png"));
                input.releaseAll();
            } catch (Exception failure) {
                throw new IllegalStateException("Documentation capture failed", failure);
            }
        });
    }

    private static void press(JComponent component, int code) {
        var stroke = KeyStroke.getKeyStroke(code, 0, false);
        var action = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke);
        component.getActionMap().get(action).actionPerformed(null);
    }

    private static void layout(Container container) {
        container.doLayout();
        for (var child : container.getComponents()) {
            if (child instanceof Container nested) {
                layout(nested);
            }
        }
    }

    private static void capture(JComponent component, Path path) throws Exception {
        var image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            component.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(image, "png", path.toFile())) {
            throw new IllegalStateException("PNG writer unavailable");
        }
    }
}

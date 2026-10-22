package berryboy.desktop;

import berryboy.video.BitmapRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** The actual desktop view; it can also be painted headlessly for documentation. */
final class DesktopPanel extends JPanel {
    private final BufferedImage image = new BufferedImage(
            BitmapRenderer.WIDTH, BitmapRenderer.HEIGHT, BufferedImage.TYPE_INT_RGB);
    private final JLabel status = new JLabel();
    private final JLabel keypad = new JLabel();
    private final JPanel screen = new JPanel() {
        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            var scale = Math.min(getWidth() / BitmapRenderer.WIDTH, getHeight() / BitmapRenderer.HEIGHT);
            var width = BitmapRenderer.WIDTH * scale;
            var height = BitmapRenderer.HEIGHT * scale;
            graphics.drawImage(image, (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
        }
    };

    DesktopPanel() {
        super(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        var title = new JLabel("BerryBoy Advance  /  Linux desktop");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        add(title, BorderLayout.NORTH);
        screen.setBackground(new Color(21, 24, 32));
        screen.setPreferredSize(new Dimension(720, 480));
        add(screen, BorderLayout.CENTER);
        var footer = new JPanel(new GridLayout(0, 1, 0, 5));
        footer.add(new JLabel("Arrows: D-pad    Z / X: A / B    A / S: L / R    Enter: Start    Backspace: Select"));
        footer.add(keypad);
        footer.add(status);
        add(footer, BorderLayout.SOUTH);
    }

    void present(int[] pixels, int keyInput, String message) {
        image.setRGB(0, 0, BitmapRenderer.WIDTH, BitmapRenderer.HEIGHT, pixels, 0, BitmapRenderer.WIDTH);
        keypad.setText("KEYINPUT  0x%03x    |    0 = pressed, 1 = released".formatted(keyInput));
        setStatus(message);
        screen.repaint();
    }

    void setStatus(String message) {
        status.setText(message);
    }
}

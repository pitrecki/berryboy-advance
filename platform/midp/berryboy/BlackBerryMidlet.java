package berryboy;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Graphics;
import javax.microedition.midlet.MIDlet;

/** Small platform adapter, compiled only against the supplied MIDP/BB SDK. */
public final class BlackBerryMidlet extends MIDlet implements CommandListener {
    private final Command rerun = new Command("Run checks", Command.SCREEN, 1);
    private final Command exit = new Command("Exit", Command.EXIT, 2);
    private DemoCanvas canvas;

    protected void startApp() {
        if (canvas == null) {
            canvas = new DemoCanvas();
            canvas.addCommand(rerun);
            canvas.addCommand(exit);
            canvas.setCommandListener(this);
        }
        Display.getDisplay(this).setCurrent(canvas);
        canvas.startChecks();
    }
    protected void pauseApp() {
    }

    protected void destroyApp(boolean unconditional) {
    }

    public void commandAction(Command command, Displayable display) {
        if (command == exit) {
            notifyDestroyed();
        } else {
            canvas.startChecks();
        }
    }

    private static final class DemoCanvas extends Canvas implements Runnable {
        private String status = "Ready";
        private String details = "";
        private int[] frame;
        private boolean running;

        synchronized void startChecks() {
            if (running) {
                return;
            }
            running = true;
            status = "Running on the device JVM...";
            new Thread(this).start();
            repaint();
        }
        public void run() {
            try {
                long started = System.currentTimeMillis();
                int passed = RuntimeChecks.run();
                Machine machine = new Machine(DemoRom.create());
                machine.runInstructions(DemoRom.RUN_BUDGET);
                int[] pixels = machine.video.render();
                synchronized (this) {
                    frame = pixels;
                    status = "PASS: " + passed + " runtime checks";
                    details = "Demo/checks: " + (System.currentTimeMillis() - started) + " ms";
                }
            } catch (Exception error) {
                synchronized (this) {
                    frame = null;
                    status = "FAILED";
                    details = error.toString();
                }
            } finally {
                synchronized (this) {
                    running = false;
                }
                repaint();
            }
        }
        protected synchronized void paint(Graphics graphics) {
            graphics.setColor(0x151820);
            graphics.fillRect(0, 0, getWidth(), getHeight());
            graphics.setColor(0xffffff);
            graphics.drawString("BerryBoy Advance / BBOS runtime", 6, 6, Graphics.TOP | Graphics.LEFT);
            graphics.drawString(status, 6, 28, Graphics.TOP | Graphics.LEFT);
            if (frame != null) {
                int left = Math.max(0, (getWidth() - BitmapRenderer.WIDTH) / 2);
                graphics.drawRGB(frame, 0, BitmapRenderer.WIDTH, left, 60,
                        BitmapRenderer.WIDTH, BitmapRenderer.HEIGHT, false);
            }
            // Wrap diagnostics rather than clipping the first failing opcode.
            int width = Math.max(10, getWidth() / 8);
            for (int start = 0, y = 232; start < details.length(); start += width, y += 18) {
                String line = details.substring(start, Math.min(details.length(), start + width));
                graphics.drawString(line, 6, y, Graphics.TOP | Graphics.LEFT);
            }
        }
    }
}

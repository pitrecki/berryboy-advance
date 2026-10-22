package berryboy;

import berryboy.bus.Memory;
import berryboy.input.Keypad;
import berryboy.video.BitmapRenderer;

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
        if (canvas != null) {
            canvas.pauseChecks();
        }
    }

    protected void destroyApp(boolean unconditional) {
        if (canvas != null) {
            canvas.pauseChecks();
        }
    }

    public void commandAction(Command command, Displayable display) {
        if (command == exit) {
            canvas.pauseChecks();
            notifyDestroyed();
        } else {
            canvas.startChecks();
        }
    }

    private static final class DemoCanvas extends Canvas implements Runnable {
        private static final int INSTRUCTIONS_PER_BATCH = 5000;
        private static final int POLL_DELAY_MILLIS = 16; // Diagnostic pacing, not GBA frame timing.

        private String status = "Ready";
        private String details = "";
        private int[] frame;
        private boolean running;
        private boolean active;
        private boolean rerunRequested;
        private int pressedKeys;

        synchronized void pauseChecks() {
            active = false;
            pressedKeys = 0;
        }

        synchronized void startChecks() {
            active = true;
            rerunRequested = true;
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
                Machine machine = null;
                while (isActive()) {
                    if (takeRerunRequest()) {
                        int passed = RuntimeChecks.run();
                        machine = new Machine(DemoRom.create());
                        machine.runInstructions(DemoRom.RUN_BUDGET);
                        synchronized (this) {
                            status = "PASS: " + passed + " runtime checks";
                        }
                    }
                    machine.memory.setPressedKeys(heldButtons());
                    machine.runInstructions(INSTRUCTIONS_PER_BATCH);
                    synchronized (this) {
                        frame = machine.video.render();
                        details = "KEYINPUT: 0x" + Integer.toHexString(machine.memory.read16(Memory.KEY_INPUT))
                                + " / Z,X: A,B / A,S: L,R";
                    }
                    repaint();
                    Thread.sleep(POLL_DELAY_MILLIS);
                }
            } catch (Exception error) {
                synchronized (this) {
                    frame = null;
                    status = "FAILED";
                    details = error.toString();
                    active = false;
                }
            } finally {
                synchronized (this) {
                    running = false;
                    if (active && rerunRequested) {
                        startChecks();
                    }
                }
                repaint();
            }
        }

        private synchronized boolean isActive() {
            return active;
        }

        private synchronized boolean takeRerunRequest() {
            boolean requested = rerunRequested;
            rerunRequested = false;
            return requested;
        }

        private synchronized int heldButtons() {
            return pressedKeys;
        }

        protected synchronized void keyPressed(int keyCode) {
            pressedKeys |= buttonFor(keyCode);
        }

        protected synchronized void keyReleased(int keyCode) {
            pressedKeys &= ~buttonFor(keyCode);
        }

        protected synchronized void hideNotify() {
            pressedKeys = 0;
        }

        private int buttonFor(int keyCode) {
            switch (keyCode) {
                case 'z': case 'Z': return Keypad.A;
                case 'x': case 'X': return Keypad.B;
                case 'a': case 'A': return Keypad.L;
                case 's': case 'S': return Keypad.R;
                case '\n': case '\r': return Keypad.START;
                case '\b': return Keypad.SELECT;
                default: break;
            }
            try {
                switch (getGameAction(keyCode)) {
                    case UP: return Keypad.UP;
                    case DOWN: return Keypad.DOWN;
                    case LEFT: return Keypad.LEFT;
                    case RIGHT: return Keypad.RIGHT;
                    case FIRE: return Keypad.A;
                    default: return 0;
                }
            } catch (IllegalArgumentException unmapped) {
                return 0;
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

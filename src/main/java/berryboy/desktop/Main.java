package berryboy.desktop;

import berryboy.BitmapRenderer;
import berryboy.Cartridge;
import berryboy.DemoRom;
import berryboy.Machine;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Java 21 desktop frontend; the BlackBerry build does not include this package. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        switch (LaunchCommand.parse(args)) {
            case LaunchCommand.Demo(var headless) -> runDemo(headless);
            case LaunchCommand.Header(var rom) -> printHeader(rom);
            case LaunchCommand.Run(var rom, var instructions, var headless) ->
                    run(readRom(rom), instructions, Path.of("target/frame.png"), headless);
        }
    }

    private static void runDemo(boolean headless) throws IOException {
        var image = DemoRom.create();
        run(image, DemoRom.RUN_BUDGET, Path.of("target/demo.png"), headless);
        Files.write(Path.of("target/demo.gba"), image);
    }

    private static void run(byte[] image, int instructions, Path output, boolean headless) throws IOException {
        var result = execute(image, instructions);
        saveFrame(result.frame(), output);
        printResult(result, output);
        if (!headless && !GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeLater(() -> show(result.frame()));
        }
    }

    private static ExecutionResult execute(byte[] image, int instructions) {
        var machine = new Machine(image);
        var started = System.nanoTime();
        machine.runInstructions(instructions);
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000.0;
        var frame = new BufferedImage(BitmapRenderer.WIDTH, BitmapRenderer.HEIGHT, BufferedImage.TYPE_INT_RGB);
        frame.setRGB(0, 0, BitmapRenderer.WIDTH, BitmapRenderer.HEIGHT,
                machine.video.render(), 0, BitmapRenderer.WIDTH);
        return new ExecutionResult(frame, machine.cpu.instructions(), machine.cpu.pc(), machine.cpu.isThumb(), elapsedMillis);
    }

    private static void saveFrame(BufferedImage frame, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        if (!ImageIO.write(frame, "png", output.toFile())) {
            throw new IOException("PNG writer is unavailable");
        }
    }

    private static void printResult(ExecutionResult result, Path output) {
        System.out.printf("%,d guest instructions; PC=0x%08x; %s; desktop elapsed %.2f ms%n",
                result.instructions(), result.programCounter(), result.thumb() ? "Thumb" : "ARM", result.elapsedMillis());
        System.out.println("Frame: " + output.toAbsolutePath());
        System.out.println("This is instruction throughput, not emulated GBA timing or a Bold benchmark.");
    }

    private static void printHeader(Path rom) throws IOException {
        var cartridge = new Cartridge(readRom(rom));
        System.out.printf("Title: %s%nCode: %s%nSize: %,d bytes%nHeader checksum: %s%n",
                cartridge.title(), cartridge.gameCode(), cartridge.size(), cartridge.headerChecksumValid());
    }

    private static byte[] readRom(Path path) throws IOException {
        var size = Files.size(path);
        if (size < 0xc0 || size > Cartridge.MAX_SIZE) {
            throw new IllegalArgumentException("ROM size must be 192 bytes to 32 MiB");
        }
        return Files.readAllBytes(path);
    }

    private static void show(BufferedImage image) {
        var frame = new JFrame("BerryBoy Advance | Java 21 desktop | ARM -> Thumb -> VRAM");
        var panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                graphics.drawImage(image, 0, 0, getWidth(), getHeight(), null);
            }
        };
        panel.setPreferredSize(new Dimension(720, 480));
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.add(panel);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    private record ExecutionResult(BufferedImage frame, long instructions, int programCounter,
            boolean thumb, double elapsedMillis) {
    }
}

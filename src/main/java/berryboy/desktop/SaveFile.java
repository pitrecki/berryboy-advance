package berryboy.desktop;

import berryboy.cartridge.CartridgeBackup;
import berryboy.desktop.storage.AtomicSaveWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Host persistence for portable cartridge backup bytes. */
public final class SaveFile {
    private SaveFile() {
    }

    public static void load(Path path, CartridgeBackup backup) throws IOException {
        load(path, backup, null);
    }

    /** Read once and validate before replacing cartridge bytes; false means a fresh backup. */
    public static boolean load(Path path, CartridgeBackup backup, String expectedSha256) throws IOException {
        if (!Files.exists(path)) {
            if (expectedSha256 != null) throw new IOException("Expected initial save is missing: " + path);
            return false;
        }
        var bytes = Files.readAllBytes(path);
        if (expectedSha256 != null) {
            try {
                var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!digest.equals(expectedSha256)) throw new IllegalArgumentException("Initial save SHA-256 mismatch: " + path);
            } catch (NoSuchAlgorithmException unavailable) { throw new IOException("Cannot verify initial save SHA-256", unavailable); }
        }
        backup.restore(bytes);
        return true;
    }

    public static void save(Path path, CartridgeBackup backup) throws IOException {
        new AtomicSaveWriter().save(path, backup);
    }
}

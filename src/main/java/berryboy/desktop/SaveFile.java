package berryboy.desktop;

import berryboy.CartridgeBackup;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Host persistence for portable cartridge backup bytes. */
public final class SaveFile {
    private SaveFile() {
    }

    public static void load(Path path, CartridgeBackup backup) throws IOException {
        if (Files.exists(path)) backup.restore(Files.readAllBytes(path));
    }

    public static void save(Path path, CartridgeBackup backup) throws IOException {
        if (!backup.dirty()) return;
        var target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), ".berryboy-save-", ".tmp");
        try {
            Files.write(temporary, backup.snapshot());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            backup.markSaved();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

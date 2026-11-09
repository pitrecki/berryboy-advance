package berryboy.desktop.storage;

import berryboy.cartridge.CartridgeBackup;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Publishes a complete, forced snapshot; the emulation caller retains ownership of backup state. */
public final class AtomicSaveWriter {
    interface Output extends AutoCloseable {
        void write(byte[] snapshot) throws IOException;
        void sync() throws IOException;
        @Override void close() throws IOException;
    }
    /** Filesystem boundary used to exercise failures at the real publication stages. */
    interface Storage {
        Path temporary(Path directory) throws IOException;
        Output open(Path temporary) throws IOException;
        void replace(Path temporary, Path target) throws IOException;
        void delete(Path temporary) throws IOException;
    }
    private final Storage storage;

    public AtomicSaveWriter() { this(new DiskStorage()); }
    AtomicSaveWriter(Storage storage) { this.storage = storage; }

    public void save(Path path, CartridgeBackup backup) throws IOException {
        if (!backup.dirty()) return;
        var target = path.toAbsolutePath();
        var snapshot = backup.snapshot();
        var temporary = storage.temporary(target.getParent());
        try {
            // Close must succeed before publication, including a close following force().
            try (var output = storage.open(temporary)) {
                output.write(snapshot);
                output.sync();
            }
            try {
                storage.replace(temporary, target);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic save replacement is unavailable; choose another save folder: " + target, unsupported);
            }
            backup.markSaved();
        } catch (IOException | RuntimeException failure) {
            try { storage.delete(temporary); }
            catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static final class DiskStorage implements Storage {
        public Path temporary(Path directory) throws IOException {
            Files.createDirectories(directory);
            return Files.createTempFile(directory, ".berryboy-save-", ".tmp");
        }
        public Output open(Path temporary) throws IOException {
            var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return new Output() {
                public void write(byte[] snapshot) throws IOException {
                    var buffer = ByteBuffer.wrap(snapshot);
                    while (buffer.hasRemaining()) {
                        if (channel.write(buffer) <= 0) throw new IOException("Incomplete save write");
                    }
                }
                public void sync() throws IOException { channel.force(true); }
                public void close() throws IOException { channel.close(); }
            };
        }
        public void replace(Path temporary, Path target) throws IOException {
            // Refuse an unsafe replacement when atomic rename is unavailable.
            // File force + rename does not establish physical power-loss durability.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        public void delete(Path temporary) throws IOException { Files.deleteIfExists(temporary); }
    }
}

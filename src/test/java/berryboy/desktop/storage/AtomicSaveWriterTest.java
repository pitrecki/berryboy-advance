package berryboy.desktop.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import berryboy.cartridge.CartridgeBackup;
import berryboy.desktop.SaveFile;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Real temporary files; only the selected IO stage fails. */
class AtomicSaveWriterTest {
    @TempDir Path directory;
    enum Fault { NONE, CREATE, OPEN, PARTIAL_WRITE, FORCE, CLOSE, REPLACE, UNSUPPORTED, AFTER_REPLACE }

    static Stream<Arguments> failures() {
        return Arrays.stream(Fault.values()).filter(f -> f != Fault.NONE)
                .flatMap(f -> Stream.of(Arguments.of(f, false), Arguments.of(f, true)));
    }

    @ParameterizedTest(name = "{0}, previous save={1}")
    @MethodSource("failures")
    void failuresKeepACompletePublishedImageAndAllowRetry(Fault fault, boolean existing) throws Exception {
        var target = directory.resolve("game.sav");
        var previous = new byte[32768]; previous[7] = 17;
        if (existing) Files.write(target, previous);
        var backup = new CartridgeBackup(CartridgeBackup.SRAM); backup.write8(7, 42);
        var pending = backup.snapshot();
        var storage = new FaultStorage(fault); var writer = new AtomicSaveWriter(storage);
        var expectedMessage = fault == Fault.UNSUPPORTED ? "choose another save folder" : fault.name();
        assertThatThrownBy(() -> writer.save(target, backup)).isInstanceOf(IOException.class).hasMessageContaining(expectedMessage);
        assertThat(backup.dirty()).isTrue();
        assertThat(backup.snapshot()).isEqualTo(pending);
        if (fault == Fault.AFTER_REPLACE) assertThat(Files.readAllBytes(target)).isEqualTo(pending);
        else if (existing) assertThat(Files.readAllBytes(target)).isEqualTo(previous);
        else assertThat(target).doesNotExist();
        if (fault != Fault.CREATE && fault != Fault.OPEN) assertThat(storage.closed).isTrue();
        try (var files = Files.list(directory)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList())
                    .containsExactlyElementsOf(Files.exists(target) ? java.util.List.of("game.sav") : java.util.List.of());
        }
        // A fresh loader sees only the published file, including a lost acknowledgement after rename.
        var reopened = new CartridgeBackup(CartridgeBackup.SRAM);
        assertThat(SaveFile.load(target, reopened, null)).isEqualTo(Files.exists(target));
        if (Files.exists(target)) assertThat(reopened.snapshot()).isEqualTo(fault == Fault.AFTER_REPLACE ? pending : previous);
        storage.fault = Fault.NONE;
        writer.save(target, backup);
        assertThat(backup.dirty()).isFalse();
        assertThat(Files.readAllBytes(target)).isEqualTo(pending);
        assertThat(SaveFile.load(target, reopened, null)).isTrue();
        assertThat(reopened.snapshot()).isEqualTo(pending);
    }

    @Test
    void preservesTheOriginalFailureAndAnAbandonedTemporaryCannotReplaceTheCommittedSave() throws Exception {
        var target = directory.resolve("game.sav"); var previous = new byte[32768]; previous[7] = 17;
        Files.write(target, previous);
        var backup = new CartridgeBackup(CartridgeBackup.SRAM); backup.write8(7, 42);
        var storage = new FaultStorage(Fault.PARTIAL_WRITE); storage.failDelete = true;
        var writer = new AtomicSaveWriter(storage);
        assertThatThrownBy(() -> writer.save(target, backup)).hasMessage("PARTIAL_WRITE")
                .satisfies(error -> assertThat(error.getSuppressed()).extracting(Throwable::getMessage).containsExactly("DELETE"));
        assertThat(backup.dirty()).isTrue();
        assertThat(Files.readAllBytes(storage.temporary)).hasSize(13);
        var reopened = new CartridgeBackup(CartridgeBackup.SRAM);
        assertThat(SaveFile.load(target, reopened, null)).isTrue();
        assertThat(reopened.snapshot()).isEqualTo(previous);
        storage.failDelete = false; storage.fault = Fault.NONE;
        writer.save(target, backup);
        assertThat(Files.readAllBytes(target)).isEqualTo(backup.snapshot());
        assertThat(backup.dirty()).isFalse();
    }

    @Test
    void retainsForceAndCloseFailuresInTheirOriginalOrder() {
        var backup = new CartridgeBackup(CartridgeBackup.SRAM); backup.write8(7, 42);
        var storage = new FaultStorage(Fault.FORCE); storage.failClose = true;
        assertThatThrownBy(() -> new AtomicSaveWriter(storage).save(directory.resolve("game.sav"), backup))
                .hasMessage("FORCE")
                .satisfies(error -> assertThat(error.getSuppressed()).extracting(Throwable::getMessage).containsExactly("CLOSE"));
        assertThat(backup.dirty()).isTrue();
        assertThat(storage.closed).isTrue();
    }

    @Test
    void cleanBackupDoesNotOpenOrCreateAnything() throws Exception {
        var folder = directory.resolve("unused");
        new AtomicSaveWriter(new FaultStorage(Fault.CREATE)).save(folder.resolve("game.sav"), new CartridgeBackup(CartridgeBackup.SRAM));
        assertThat(folder).doesNotExist();
    }

    private static final class FaultStorage implements AtomicSaveWriter.Storage {
        final AtomicSaveWriter.DiskStorage disk = new AtomicSaveWriter.DiskStorage();
        Fault fault;
        boolean failDelete, failClose, closed;
        Path temporary;
        FaultStorage(Fault fault) { this.fault = fault; }
        public Path temporary(Path directory) throws IOException {
            if (fault == Fault.CREATE) throw new IOException("CREATE");
            return temporary = disk.temporary(directory);
        }
        public AtomicSaveWriter.Output open(Path path) throws IOException {
            if (fault == Fault.OPEN) throw new IOException("OPEN");
            var output = disk.open(path);
            return new AtomicSaveWriter.Output() {
                public void write(byte[] bytes) throws IOException {
                    if (fault == Fault.PARTIAL_WRITE) { output.write(Arrays.copyOf(bytes, 13)); throw new IOException("PARTIAL_WRITE"); }
                    output.write(bytes);
                }
                public void sync() throws IOException {
                    if (fault == Fault.FORCE) throw new IOException("FORCE");
                    output.sync();
                }
                public void close() throws IOException {
                    output.close(); closed = true;
                    if (fault == Fault.CLOSE || failClose) throw new IOException("CLOSE");
                }
            };
        }
        public void replace(Path path, Path target) throws IOException {
            if (fault == Fault.REPLACE) throw new IOException("REPLACE");
            if (fault == Fault.UNSUPPORTED) throw new AtomicMoveNotSupportedException(path.toString(), target.toString(), "Injected unsupported rename");
            disk.replace(path, target);
            if (fault == Fault.AFTER_REPLACE) throw new IOException("AFTER_REPLACE");
        }
        public void delete(Path path) throws IOException {
            if (failDelete) throw new IOException("DELETE");
            disk.delete(path);
        }
    }
}

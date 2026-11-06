package berryboy.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import berryboy.cartridge.CartridgeBackup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveFileTest {
    @TempDir Path directory;

    @Test
    void verifiedLoadUsesTheExpectedImageWithoutChangingTheFile() throws Exception {
        var bytes = new byte[32768]; bytes[17] = 42;
        var path = directory.resolve("initial.sav"); Files.write(path, bytes);
        var backup = new CartridgeBackup(CartridgeBackup.SRAM);
        assertThat(SaveFile.load(path, backup, ScenarioRunner.hash(bytes))).isTrue();
        assertThat(backup.snapshot()).isEqualTo(bytes);
        assertThat(backup.dirty()).isFalse();
        assertThat(Files.readAllBytes(path)).isEqualTo(bytes);
    }

    @Test
    void wrongDigestPreservesBothTheLiveBackupAndTheSourceFile() throws Exception {
        var bytes = new byte[32768]; var path = directory.resolve("initial.sav"); Files.write(path, bytes);
        var backup = new CartridgeBackup(CartridgeBackup.SRAM); backup.write8(17, 42);
        var before = backup.snapshot();
        assertThatThrownBy(() -> SaveFile.load(path, backup, "0".repeat(64))).hasMessageContaining("SHA-256");
        assertThat(backup.snapshot()).isEqualTo(before);
        assertThat(backup.dirty()).isTrue();
        assertThat(Files.readAllBytes(path)).isEqualTo(bytes);
    }

    @Test
    void aRequiredMissingSaveFailsWithoutCreatingItOrReplacingLiveData() {
        var backup = new CartridgeBackup(CartridgeBackup.SRAM); backup.write8(17, 42);
        var path = directory.resolve("missing.sav"); var before = backup.snapshot();
        assertThatThrownBy(() -> SaveFile.load(path, backup, "0".repeat(64)))
                .isInstanceOf(IOException.class).hasMessageContaining("missing");
        assertThat(backup.snapshot()).isEqualTo(before);
        assertThat(backup.dirty()).isTrue();
        assertThat(path).doesNotExist();
    }

    @Test
    void anOptionalMissingSaveKeepsTheFreshBackup() throws Exception {
        var backup = new CartridgeBackup(CartridgeBackup.SRAM); var before = backup.snapshot();
        assertThat(SaveFile.load(directory.resolve("missing.sav"), backup, null)).isFalse();
        assertThat(backup.snapshot()).isEqualTo(before);
        assertThat(backup.dirty()).isFalse();
    }

    @Test
    void savesReloadsAndReplacesOnlyAfterSuccessfulSerialization() throws Exception {
        var backup = new CartridgeBackup(CartridgeBackup.SRAM);
        var path = directory.resolve("game.sav");
        backup.write8(7, 42);
        SaveFile.save(path, backup);
        assertThat(backup.dirty()).isFalse();
        var loaded = new CartridgeBackup(CartridgeBackup.SRAM);
        SaveFile.load(path, loaded);
        assertThat(loaded.read8(7)).isEqualTo(42);
        loaded.write8(8, 13);
        SaveFile.save(path, loaded);
        assertThat(Files.readAllBytes(path)[8]).isEqualTo((byte) 13);
        try (var files = Files.list(directory)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("game.sav");
        }
        Files.write(path, new byte[1]);
        assertThatThrownBy(() -> SaveFile.load(path, loaded)).isInstanceOf(IllegalArgumentException.class);
        assertThat(loaded.read8(8)).isEqualTo(13);
    }
}

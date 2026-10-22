package berryboy.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import berryboy.cartridge.CartridgeBackup;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveFileTest {
    @TempDir Path directory;

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

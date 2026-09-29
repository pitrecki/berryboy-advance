package berryboy;

import static berryboy.EmulatorFixtures.blankRom;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("core")
@DisplayName("Cartridge header")
class CartridgeTest {
    @Test
    void readsAsciiTitleAndGameCode() {
        var image = blankRom();
        var title = "BERRYBOY".getBytes(StandardCharsets.US_ASCII);
        var code = "BBA1".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, image, 0xa0, title.length);
        System.arraycopy(code, 0, image, 0xac, code.length);

        var cartridge = new Cartridge(image);

        assertAll(
                () -> assertEquals("BERRYBOY", cartridge.title()),
                () -> assertEquals("BBA1", cartridge.gameCode()));
    }

    @Test
    void acceptsTheDemoHeaderChecksum() {
        var cartridge = new Cartridge(DemoRom.create());

        assertTrue(cartridge.headerChecksumValid());
    }
}

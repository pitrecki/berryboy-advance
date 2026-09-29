package berryboy;

import static berryboy.EmulatorFixtures.blankRom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

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

        assertSoftly(softly -> {
            softly.assertThat(cartridge.title()).isEqualTo("BERRYBOY");
            softly.assertThat(cartridge.gameCode()).isEqualTo("BBA1");
        });
    }

    @Test
    void acceptsTheDemoHeaderChecksum() {
        var cartridge = new Cartridge(DemoRom.create());

        assertThat(cartridge.headerChecksumValid()).isTrue();
    }
}
